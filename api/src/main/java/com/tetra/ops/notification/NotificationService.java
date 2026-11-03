package com.tetra.ops.notification;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.domain.platform.TenantRepository;
import com.tetra.ops.domain.tenant.TenantNotification;
import com.tetra.ops.domain.tenant.TenantNotificationRepository;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.DelayStep;
import com.tetra.ops.monitoring.DlqQueue;
import com.tetra.ops.report.ReportCategory;
import com.tetra.ops.tenancy.TenantContext;

/**
 * 운영자 알림(operator_notification)·테넌트 알림(tenant_notification)을 남기는 유일한 곳 (CLAUDE.md §7.13).
 * 상태 변경은 상태 로그와 같은 트랜잭션에서 부른다(MANDATORY). 한 사건에 알림은 한 번만 남긴다.
 * 메일 발송 실패처럼 커밋 뒤에 생기는 일은 자체 트랜잭션으로 남긴다.
 * 1분 주기 확인(DLQ · 단계 지연)은 상태 전이가 없어 중복 방지 키(dedupe_key)로 한 번만 남긴다 — 자체 트랜잭션, 트랜잭션 밖에서 부른다.
 * 리포트 생성은 리포트 메타 저장 트랜잭션 안에서 남긴다(같은 리포트 메타가 있으면 남기지 않음, 운영자 리포트는 dedupe_key REPORT:…도).
 * 문구: 운영자는 [테넌트명] [id:이벤트명], 테넌트는 [id:이벤트명]으로 시작한다. 시각은 HH:mm(DB의 KST 값 그대로).
 * 고정 문구(라벨 + 정해진 문장)는 120자 이내로 두고, 반려 사유 · 원인은 자르지 않고 붙인다(1000자 상한만).
 * 반려 사유는 " 반려 사유: ", 실패 원인은 " 원인: " 뒤에 붙인다 — Slack 발송(SlackMessage)이 이 표지부터는 보내지 않는다.
 * 운영자 알림의 Slack 발송은 여기서 하지 않는다: 새 행은 slack_status 기본값(PENDING)이고 SlackNotifyJob이 따로 보낸다(Portal 행도 같다).
 */
@Service
public class NotificationService {

	static final int CONTENT_MAX = 1000;

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	/**
	 * 저장하는 세부 값. 알림센터 칩으로 묶는 것은 목록 API 몫:
	 * 신청 = APPLICATION, 이벤트 = HOSTING · EVENT · DELAY · MAIL(event_id 있음),
	 * 테넌트 = ACCOUNT · PROVISIONING · MAIL(event_id 없음), 리포트 = REPORT, 시스템 = SYSTEM
	 */
	enum Category {

		APPLICATION, HOSTING, EVENT, ACCOUNT, PROVISIONING, MAIL,
		/** 단계 지연(1분 주기 확인) */
		DELAY,
		/** DLQ 메시지처럼 이벤트 하나에 속하지 않는 자동화 문제(1분 주기 확인) */
		SYSTEM,
		/** 리포트 생성(테넌트 · 운영자 리포트 작업 — ReportStore) */
		REPORT

	}

	enum Priority {

		URGENT, WARNING, INFO

	}

	private final OperatorNotificationRepository operatorNotifications;

	private final TenantNotificationRepository tenantNotifications;

	private final TenantRepository tenants;

	private final EventRegistryRepository registries;

	private final Clock clock;

	public NotificationService(OperatorNotificationRepository operatorNotifications,
			TenantNotificationRepository tenantNotifications, TenantRepository tenants,
			EventRegistryRepository registries, Clock clock) {
		this.operatorNotifications = operatorNotifications;
		this.tenantNotifications = tenantNotifications;
		this.tenants = tenants;
		this.registries = registries;
		this.clock = clock;
	}

	// ------------------------------------------------------------ 신청 (APPLICATION)

	/** APPLIED → APPROVED (+ 호스팅 PENDING, 이벤트 SCHEDULED). 운영자·테넌트 INFO. 테넌트 스키마 라우팅 안에서 부른다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventApproved(String tenantId, Long eventId, String eventName) {
		String event = eventLabel(eventId, eventName);
		operator(tenantId, eventId, Category.APPLICATION, Priority.INFO,
				tenantLabel(tenantId) + " " + event + " 이벤트 신청을 승인했습니다.");
		tenant(tenantId, eventId, Category.APPLICATION, Priority.INFO, event + " 이벤트 신청이 승인되었습니다.");
	}

	/** APPLIED → REJECTED. 운영자 INFO(처리 완료), 테넌트 WARNING. 반려 사유를 담는다(이벤트명 100자 + 사유 500자). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventRejected(String tenantId, Long eventId, String eventName, String reason) {
		String event = eventLabel(eventId, eventName);
		operator(tenantId, eventId, Category.APPLICATION, Priority.INFO,
				tenantLabel(tenantId) + " " + event + " 이벤트 신청을 반려했습니다. 반려 사유: " + reason);
		tenant(tenantId, eventId, Category.APPLICATION, Priority.WARNING,
				event + " 이벤트 신청이 반려되었습니다. 반려 사유: " + reason);
	}

	// ------------------------------------------------------------ 호스팅 (HOSTING)

	/**
	 * 호스팅 상태 변화(승인 등록 WF1-2 · 호스팅 시작 WF2-1 · 종료 실패 WF4). 상태 변경과 같은 테넌트 스키마 라우팅 안에서 부른다.
	 * → DEPLOYED: INFO(DEPLOY_FAILED에서면 복구) / → DEPLOY_FAILED: URGENT
	 * → HOSTING: INFO(HOSTING_FAILED에서면 복구) + 테넌트 INFO(이벤트 페이지 열림, 복구 포함) / → HOSTING_FAILED: URGENT
	 * → END_FAILED: URGENT. 종료 성공(→ ENDED)은 eventEnded 한 건으로 남긴다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void hostingChanged(String tenantId, Long eventId, HostingStatus from, HostingStatus to, String detail) {
		EventRegistry registry = registry(eventId);
		String label = tenantLabel(tenantId) + " " + eventLabel(eventId, registry);
		if (to == HostingStatus.DEPLOY_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.URGENT,
					label + " 이벤트 배포에 실패했습니다. 확인이 필요합니다." + cause(detail));
		}
		else if (to == HostingStatus.DEPLOYED && from == HostingStatus.DEPLOY_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO,
					label + " 배포 실패로 표시됐던 이벤트의 배포가 완료되었습니다.");
		}
		else if (to == HostingStatus.DEPLOYED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO, label + " 이벤트 배포가 완료되었습니다.");
		}
		else if (to == HostingStatus.HOSTING) {
			operator(tenantId, eventId, Category.HOSTING, Priority.INFO, from == HostingStatus.HOSTING_FAILED
					? label + " 호스팅 시작 실패로 표시됐던 이벤트의 호스팅이 시작되었습니다."
					: label + " 호스팅을 시작했습니다(대기방 열림).");
			tenant(tenantId, eventId, Category.HOSTING, Priority.INFO, eventLabel(eventId, registry)
					+ " 이벤트 페이지가 열렸습니다. 입장은 " + time(registry == null ? null : EntryOpen.at(registry.getStartAt()))
					+ "부터 시작됩니다.");
		}
		else if (to == HostingStatus.HOSTING_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.URGENT,
					label + " 호스팅 시작(대기방 열기)에 실패했습니다. 확인이 필요합니다." + cause(detail));
		}
		else if (to == HostingStatus.END_FAILED) {
			operator(tenantId, eventId, Category.HOSTING, Priority.URGENT,
					label + " 이벤트 종료 처리에 실패했습니다. 확인이 필요합니다." + cause(detail));
		}
	}

	// ------------------------------------------------------------ 이벤트 (EVENT)

	/**
	 * 이벤트 준비 결과(WF2-2). 상태 변경과 같은 테넌트 스키마 라우팅 안에서 부른다.
	 * → OPEN: INFO(OPEN_FAILED에서면 복구) + 테넌트 INFO(입장 시작, 복구 포함) / → OPEN_FAILED: URGENT. 만료는 eventEnded.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventStatusChanged(String tenantId, Long eventId, EventStatus from, EventStatus to, String detail) {
		EventRegistry registry = registry(eventId);
		String label = tenantLabel(tenantId) + " " + eventLabel(eventId, registry);
		if (to == EventStatus.OPEN) {
			operator(tenantId, eventId, Category.EVENT, Priority.INFO, from == EventStatus.OPEN_FAILED
					? label + " 이벤트 준비 실패로 표시됐던 이벤트의 입장이 열렸습니다."
					: label + " 입장이 열렸습니다(쿠폰 발급은 이벤트 시작 시각부터).");
			tenant(tenantId, eventId, Category.EVENT, Priority.INFO, eventLabel(eventId, registry)
					+ " 이벤트 입장이 시작되었습니다. 이벤트는 " + time(registry == null ? null : registry.getStartAt())
					+ "에 시작됩니다.");
		}
		else if (to == EventStatus.OPEN_FAILED) {
			operator(tenantId, eventId, Category.EVENT, Priority.URGENT,
					label + " 이벤트 준비(입장 열기)에 실패했습니다. 확인이 필요합니다." + cause(detail));
		}
	}

	/**
	 * 이벤트 종료 성공(WF4: 호스팅 종료 · 발급 마감 · 자원 정리, HOSTING → ENDED). 운영자 HOSTING/INFO · 테넌트 HOSTING/INFO.
	 * 테넌트 스키마 라우팅 안에서 부른다. 집계 완료는 발급 이력 저장 뒤 eventAggregated가 따로 남긴다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventEnded(String tenantId, Long eventId) {
		String event = eventLabel(eventId, registry(eventId));
		operator(tenantId, eventId, Category.HOSTING, Priority.INFO, tenantLabel(tenantId) + " " + event
				+ " 호스팅이 종료되었습니다. 이벤트 앱(발급 서버·Redis)과 엣지 라우팅 키를 정리했습니다.");
		tenant(tenantId, eventId, Category.HOSTING, Priority.INFO,
				event + " 이벤트가 종료되었습니다. 결과 집계가 끝나면 다시 알려 드립니다.");
	}

	/**
	 * 이벤트 결과 집계 완료(WF4 성공 뒤 발급 이력 저장에서 history_generated_at이 처음 채워졌을 때 한 번). 테넌트 EVENT/INFO.
	 * 발급 이력 저장 트랜잭션 · 테넌트 스키마 라우팅 안에서 부른다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void eventAggregated(String tenantId, Long eventId) {
		tenant(tenantId, eventId, Category.EVENT, Priority.INFO, eventLabel(eventId, registry(eventId))
				+ " 이벤트 결과 집계가 완료되었습니다. 결과 화면에서 발급 내역을 확인하고 CSV를 내려받을 수 있습니다.");
	}

	// ------------------------------------------------------------ 계정 (ACCOUNT)

	/** 테넌트 등록 (NULL → INACTIVE). 운영자 INFO. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void tenantRegistered(String tenantId, String tenantName) {
		operator(tenantId, null, Category.ACCOUNT, Priority.INFO,
				"[" + tenantName + "] 테넌트가 등록되었습니다. 환경 준비를 시작합니다.");
	}

	/** 자동 비활성 (ACTIVE → INACTIVE). 운영자 INFO. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void tenantDeactivated(String tenantId, Long relatedEventId) {
		operator(tenantId, relatedEventId, Category.ACCOUNT, Priority.INFO,
				tenantLabel(tenantId) + " 진행 중인 이벤트가 없어 계정이 비활성으로 전환되었습니다.");
	}

	// ------------------------------------------------------------ 환경 준비 (PROVISIONING, 상태 로그 없음)

	/** PROVISIONING → READY: INFO, → FAILED: URGENT(스키마 없음 포함 — 실제로 FAILED로 바뀐 때만 부른다). */
	@Transactional(propagation = Propagation.MANDATORY)
	public void provisioningFinished(String tenantId, boolean ready, String detail) {
		if (ready) {
			operator(tenantId, null, Category.PROVISIONING, Priority.INFO,
					tenantLabel(tenantId) + " 테넌트 환경 준비가 완료되었습니다.");
		}
		else {
			operator(tenantId, null, Category.PROVISIONING, Priority.URGENT,
					tenantLabel(tenantId) + " 테넌트 환경 준비에 실패했습니다. 확인이 필요합니다." + cause(detail));
		}
	}

	// ------------------------------------------------------------ 메일 (커밋 뒤)

	/** 개인키 메일 발송 실패. 키 재발급이 필요하다. 운영자 URGENT. */
	@Transactional
	public void privateKeyMailFailed(String tenantId, Long eventId) {
		operator(tenantId, eventId, Category.MAIL, Priority.URGENT, tenantLabel(tenantId) + " "
				+ eventLabel(eventId, registry(eventId)) + " 개인키 메일 발송에 실패했습니다. 키 재발급이 필요합니다.");
	}

	/** 비밀번호 설정 메일 발송 실패(환경 준비 완료 뒤 결과 리스너에서 발송). 재처리하지 않는다. 운영자 WARNING. */
	@Transactional
	public void passwordSetupMailFailed(String tenantId) {
		operator(tenantId, null, Category.MAIL, Priority.WARNING,
				tenantLabel(tenantId) + " 비밀번호 설정 메일 발송에 실패했습니다. 담당자 이메일 주소와 SES 상태를 확인해 주세요.");
	}

	// ------------------------------------------------------------ 1분 주기 확인 (SYSTEM · DELAY, dedupe_key)

	private static final String DLQ_KEY_PREFIX = "DLQ:";

	private static final String DELAY_KEY_PREFIX = "DELAY:";

	/** DLQ 알림 중복 방지 키: DLQ:{큐 이름}:{yyyy-MM-dd(KST)} — 같은 큐는 하루 1건 */
	public static String dlqKey(String queueName, LocalDate day) {
		return DLQ_KEY_PREFIX + queueName + ":" + day;
	}

	/** 단계 지연 알림 중복 방지 키: DELAY:{event_id}:{단계 코드} — 이벤트 · 단계마다 1건 */
	public static String delayKey(Long eventId, DelayStep step) {
		return DELAY_KEY_PREFIX + eventId + ":" + step.name();
	}

	/** {@link #dlqKey}의 큐 이름(SQS 이름에는 ':'가 없다). DLQ 키가 아니면 empty */
	static Optional<String> dlqQueueOf(String dedupeKey) {
		if (dedupeKey == null || !dedupeKey.startsWith(DLQ_KEY_PREFIX)) {
			return Optional.empty();
		}
		int day = dedupeKey.lastIndexOf(':');
		return day > DLQ_KEY_PREFIX.length() ? Optional.of(dedupeKey.substring(DLQ_KEY_PREFIX.length(), day))
				: Optional.empty();
	}

	/** {@link #delayKey}의 단계. 단계 지연 키가 아니거나 모르는 단계면 empty */
	static Optional<DelayStep> delayStepOf(String dedupeKey) {
		if (dedupeKey == null || !dedupeKey.startsWith(DELAY_KEY_PREFIX)) {
			return Optional.empty();
		}
		String step = dedupeKey.substring(dedupeKey.lastIndexOf(':') + 1);
		return Arrays.stream(DelayStep.values()).filter(s -> s.name().equals(step)).findFirst();
	}

	/**
	 * DLQ에 메시지가 있음(1분 주기 확인). 운영자 SYSTEM/URGENT, 테넌트 · 이벤트 없음. 같은 키가 이미 있으면 남기지 않고 false.
	 * 자체 트랜잭션 — 트랜잭션 밖에서 부른다. 다른 태스크와 동시에 같은 키를 넣으면 1062(uk_operator_notif_dedupe)가 이 메서드 밖으로
	 * 나가고 이 트랜잭션은 롤백된다(부른 쪽이 1062만 잡아 false로 본다).
	 * @return 새로 남겼으면 true
	 */
	@Transactional
	public boolean dlqDetected(DlqQueue queue, int messages, LocalDate day) {
		String key = dlqKey(queue.name(), day);
		if (operatorNotifications.existsByDedupeKey(key)) {
			return false;
		}
		String content = switch (queue.kind()) {
			case RESULT -> "자동화 결과를 반영하지 못했습니다. " + queue.resultLabel() + " DLQ에 메시지 " + messages
					+ "건이 있습니다. 이벤트·테넌트 상태가 갱신되지 않았을 수 있습니다.";
			case DELIVERY -> "자동화 실행 결과가 Console에 전달되지 않았습니다. 결과 전달 DLQ에 메시지 " + messages + "건이 있습니다.";
			case SCHEDULER -> "예약된 자동화 실행이 시작되지 않았습니다. 스케줄러 DLQ에 메시지 " + messages + "건이 있습니다.";
		};
		operator(null, null, Category.SYSTEM, Priority.URGENT, content, key);
		return true;
	}

	/**
	 * 예정 단계 지연(1분 주기 확인). 운영자 DELAY — 호스팅 시작 · 준비 시작 · 입장은 URGENT, 종료 시작 · 종료 완료는 WARNING.
	 * planned = 그 단계의 예정 시각(호스팅 시작 · 준비 스케줄 · 입장 · 호스팅 종료). 같은 키가 이미 있으면 남기지 않고 false.
	 * 자체 트랜잭션 — 트랜잭션 밖에서 부른다(1062는 dlqDetected와 같이 밖으로 나간다).
	 * @return 새로 남겼으면 true
	 */
	@Transactional
	public boolean stepDelayed(String tenantId, Long eventId, DelayStep step, LocalDateTime planned) {
		String key = delayKey(eventId, step);
		if (operatorNotifications.existsByDedupeKey(key)) {
			return false;
		}
		EventRegistry registry = registry(eventId);
		String label = tenantLabel(tenantId) + " " + eventLabel(eventId, registry);
		long minutes = step.after().toMinutes();
		String content = switch (step) {
			case HOSTING_START -> label + " 호스팅 시작이 예정 시각(" + time(planned) + ")보다 " + minutes + "분 넘게 늦어지고 있습니다.";
			case PREPARE_START -> label + " 이벤트 준비가 예정 시각(" + time(planned) + ")보다 " + minutes
					+ "분 넘게 시작되지 않았습니다. 입장(" + time(registry == null ? null : EntryOpen.at(registry.getStartAt()))
					+ ")이 늦어질 수 있습니다.";
			case ENTRY_OPEN -> label + " 입장 시각(" + time(planned) + ")이 " + minutes + "분 지났지만 입장이 열리지 않았습니다.";
			case END_START -> label + " 호스팅 종료가 예정 시각(" + time(planned) + ")보다 " + minutes + "분 넘게 시작되지 않았습니다.";
			case END_DONE -> label + " 호스팅 종료 예정 시각(" + time(planned) + ")에서 " + minutes + "분이 지나도 종료가 끝나지 않았습니다.";
		};
		operator(tenantId, eventId, Category.DELAY, step.urgent() ? Priority.URGENT : Priority.WARNING, content, key);
		return true;
	}

	// ------------------------------------------------------------ 리포트 (REPORT)

	private static final String REPORT_KEY_PREFIX = "REPORT:";

	/** 운영자 리포트 알림 중복 방지 키: REPORT:{분류}:{yyyy-MM}(대상 기간 시작 달) — 같은 리포트는 1건 */
	public static String reportKey(ReportCategory category, YearMonth period) {
		return REPORT_KEY_PREFIX + category.name() + ":" + period;
	}

	/**
	 * 테넌트 리포트 생성(이벤트 결과 · 월간). 테넌트 REPORT/INFO — 이벤트 결과는 그 이벤트, 월간은 이벤트 없음.
	 * 리포트 메타 저장 트랜잭션 · 테넌트 스키마 라우팅 안에서 부른다(같은 리포트 메타가 이미 있으면 부르지 않는다 — ReportStore).
	 * 문구에 버킷 · 키 같은 내부 정보는 넣지 않는다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void tenantReportCreated(String tenantId, ReportCategory category, Long eventId, LocalDateTime periodStart) {
		switch (category) {
			case EVENT_RESULT -> tenant(tenantId, eventId, Category.REPORT, Priority.INFO, eventLabel(eventId,
					registry(eventId)) + " 이벤트 결과 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다.");
			case MONTHLY -> tenant(tenantId, null, Category.REPORT, Priority.INFO,
					monthLabel(periodStart) + " 월간 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다.");
			case OPERATOR_AUDIT -> throw new IllegalArgumentException("운영자 리포트는 테넌트 알림을 남기지 않습니다: " + category);
		}
	}

	/**
	 * 운영자 리포트 생성(운영자 감사). 운영자 REPORT/INFO, 테넌트 · 이벤트 없음, 중복 방지 키 {@link #reportKey}.
	 * 리포트 메타 저장 트랜잭션 안에서 부른다. 같은 키 알림이 이미 있으면(메타만 지워진 뒤 다시 만든 경우) 남기지 않고 false.
	 * @return 새로 남겼으면 true
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean operatorReportCreated(ReportCategory category, LocalDateTime periodStart) {
		if (category != ReportCategory.OPERATOR_AUDIT) {
			throw new IllegalArgumentException("테넌트 리포트는 운영자 리포트 알림을 남기지 않습니다: " + category);
		}
		String key = reportKey(category, YearMonth.from(periodStart));
		if (operatorNotifications.existsByDedupeKey(key)) {
			return false;
		}
		operator(null, null, Category.REPORT, Priority.INFO,
				monthLabel(periodStart) + " 운영자 감사 리포트가 생성되었습니다. 리포트 화면에서 내려받을 수 있습니다.", key);
		return true;
	}

	/** [yyyy년 M월] (대상 기간 시작 달) */
	static String monthLabel(LocalDateTime periodStart) {
		return "[" + periodStart.getYear() + "년 " + periodStart.getMonthValue() + "월]";
	}

	// ------------------------------------------------------------

	private void operator(String tenantId, Long eventId, Category category, Priority priority, String content) {
		operator(tenantId, eventId, category, priority, content, null);
	}

	private void operator(String tenantId, Long eventId, Category category, Priority priority, String content,
			String dedupeKey) {
		operatorNotifications.save(OperatorNotification.unread(tenantId, eventId, category.name(), priority.name(),
				Kst.now(clock), fit(content), dedupeKey));
	}

	/** tenant_notification은 테넌트 스키마 테이블이라 라우팅된 테넌트와 같아야 한다(이중 확인은 엔티티 리스너가 한다). */
	private void tenant(String tenantId, Long eventId, Category category, Priority priority, String content) {
		if (!tenantId.equals(TenantContext.current())) {
			throw new IllegalStateException("테넌트 알림은 해당 테넌트 스키마 라우팅 안에서만 남길 수 있습니다.");
		}
		tenantNotifications.save(TenantNotification.unread(tenantId, eventId, category.name(), priority.name(),
				Kst.now(clock), fit(content)));
	}

	/** [테넌트명]. 테넌트가 없으면 [tenant_id] */
	private String tenantLabel(String tenantId) {
		return tenants.findById(tenantId).map(t -> "[" + t.getName() + "]").orElse("[" + tenantId + "]");
	}

	private EventRegistry registry(Long eventId) {
		return registries.findById(eventId).orElse(null);
	}

	private static String eventLabel(Long eventId, EventRegistry registry) {
		return eventLabel(eventId, registry == null ? null : registry.getName());
	}

	/** [id:이벤트명]. 이름을 모르면 [id] */
	static String eventLabel(Long eventId, String eventName) {
		return eventName == null ? "[" + eventId + "]" : "[" + eventId + ":" + eventName + "]";
	}

	/** DB 일시(KST 벽시계)의 HH:mm. 모르면 --:-- */
	static String time(LocalDateTime value) {
		return value == null ? "--:--" : HH_MM.format(value);
	}

	private static String cause(String detail) {
		return detail == null ? "" : " 원인: " + detail;
	}

	static String fit(String content) {
		return content.length() <= CONTENT_MAX ? content : content.substring(0, CONTENT_MAX);
	}

}

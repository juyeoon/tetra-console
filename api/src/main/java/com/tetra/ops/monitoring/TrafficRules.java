package com.tetra.ops.monitoring;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.monitoring.TrafficEventResponse.RedisConnection;

/**
 * OPS-05 상태 판정(명세 2절, 초안 — 경보 8장 임계값, 부하 시험 뒤 조정). 판정은 백엔드 한 곳에서만 한다(상태 요약 · 드롭다운 기본 선택 ·
 * KPI 주의 표시가 같은 기준을 쓰고, 뒤에 붙일 경보도 이 기준을 쓴다). 화면은 level · reason · warn을 그대로 그린다.
 * 임계값은 상수(설정으로 빼지 않음, 2026-10-26 결정).
 */
final class TrafficRules {

	// ---- 이벤트 ----

	static final double ERROR_RATIO_DANGER = 0.02;

	/** 설계 D-2 확정(2026-10-26): 주의 1% · 위험 2%. 화면 KPI 문구 · 에러 그래프 기준선(web trafficMonitoringView ERROR_RATIO_*)도 같은 값 */
	static final double ERROR_RATIO_WARN = 0.01;

	static final double P95_DANGER_SECONDS = 2.0;

	/** TODO(미정): 부하 시험 뒤 확정(초안 500ms) */
	static final double P95_WARN_SECONDS = 0.5;

	static final double REDIS_MEMORY_WARN = 0.8;

	/** Redis 끊김이 이만큼 이어지면 위험(그 전은 주의). 화면 · Grafana 이벤트 인프라 · 장애 대응과 같은 1분(2026-11-02) */
	static final Duration REDIS_DOWN_FOR = Duration.ofMinutes(1);

	/** REDIS_DOWN_FOR 동안의 15초 점 수 = 1분 내내 끊김 */
	static final int REDIS_DOWN_POINTS = 4;

	// ---- 클러스터 · 플랫폼 ----

	static final int SYSTEM_NODES_MIN = 2;

	static final double RDS_CPU_WARN_PERCENT = 80;

	// ---- 자동화 ----

	static final Duration AUTOMATION_FAILURE_WINDOW = Duration.ofHours(24);

	/** 단계 지연: 호스팅 시작 · 준비 시작 · 종료 시작은 예정 + 10분 */
	static final Duration STEP_DELAY = Duration.ofMinutes(10);

	/** 입장 지연: 입장 + 5분(WF2-2는 입장 직후 OPEN으로 바꾼다) */
	static final Duration ENTRY_OPEN_DELAY = Duration.ofMinutes(5);

	/** 종료 완료 지연: hosting_end_at + 60분(WF4 최대 대기 — 닫힘 3분 · 집계 30분 · 앱 삭제 10분 — 보다 길게) */
	static final Duration END_DONE_DELAY = Duration.ofMinutes(60);

	record Judgment(MonitoringLevel level, String reason) {

		static final Judgment NO_DATA = new Judgment(MonitoringLevel.UNKNOWN, "데이터 없음");

	}

	/**
	 * 이벤트 판정 재료(Prometheus 순간값). 시리즈가 없으면 null.
	 * complete = 재료 쿼리가 모두 성공(false면 일부 쿼리 오류로 비어 있음 — 위험이 보이지 않으면 UNKNOWN).
	 * 5xx 쿼리가 실패하면 rps도 null로 넣는다(요청만 있고 5xx 없음 = 0 규칙이 실패를 0으로 바꾸지 않게).
	 * redis = {@link #redisConnection}(null이면 끊긴 점을 못 봄 — 끊김 아님으로 본다).
	 */
	record EventSignals(Double rps, Double errorRps, Double p95Seconds, Double cursorChanges, Double waiting,
			Double stock, RedisConnection redis, Integer apiReady, Integer hpaMax, boolean complete) {

		EventSignals(Double rps, Double errorRps, Double p95Seconds, Double cursorChanges, Double waiting, Double stock,
				RedisConnection redis, Integer apiReady, Integer hpaMax) {
			this(rps, errorRps, p95Seconds, cursorChanges, waiting, stock, redis, apiReady, hpaMax, true);
		}

		/** 5xx 비율. 요청 시리즈가 있고 5xx만 없으면 0, 요청 시리즈가 없으면 null */
		Double errorRatio() {
			return TrafficRules.errorRatio(rps, errorRps);
		}

		boolean redisDown() {
			return redis == RedisConnection.DOWN;
		}

		boolean redisChecking() {
			return redis == RedisConnection.CHECKING;
		}

		boolean podsAtMax() {
			return apiReady != null && hpaMax != null && hpaMax > 0 && apiReady >= hpaMax;
		}

	}

	private TrafficRules() {
	}

	static Double errorRatio(Double rps, Double errorRps) {
		if (rps == null) {
			return null;
		}
		if (rps <= 0) {
			return 0.0;
		}
		return (errorRps == null ? 0 : errorRps) / rps;
	}

	/**
	 * Redis 연결: 1분(15초 점 {@link #REDIS_DOWN_POINTS}개) 내내 끊김이면 DOWN, 지금 끊겼지만 1분이 안 됐으면 CHECKING, 그 밖은 UP.
	 * 끊김 = redis_up 또는 exporter up 0, 이벤트 정리 중은 쿼리({@link TrafficQueries#redisDown})에서 빠져 UP —
	 * 종료 때 exporter가 먼저 사라지며 낸 0 한 점(이벤트 14)을 위험으로 보지 않는다.
	 * @param downNow 지금 끊김(REDIS_DOWN 시리즈 있음)
	 * @param downPoints 최근 1분 동안 끊긴 점 수(시리즈 없으면 null)
	 */
	static RedisConnection redisConnection(boolean downNow, Double downPoints) {
		if (downPoints != null && downPoints >= REDIS_DOWN_POINTS) {
			return RedisConnection.DOWN;
		}
		return downNow ? RedisConnection.CHECKING : RedisConnection.UP;
	}

	/**
	 * 진행 중(OPEN) 이벤트 하나.
	 * 위험: 5xx 2% 이상 · p95 2초 이상 · 커서 1분 정지(시작 뒤, 대기 · 재고 있을 때) · Redis 연결 끊김(1분 이어짐, {@link #redisConnection}).
	 * 주의: Redis 연결 확인 중(끊긴 지 1분 안) · 5xx 1% 이상 · p95 500ms 이상 · Pod = KEDA 최대.
	 * 순서: 보이는 위험 → 재료 일부가 비었으면 UNKNOWN(위험을 배제할 수 없음) → 주의 → 정상.
	 * @param started 지금 ≥ 시작 시각(그전에는 커서가 움직이지 않는 게 정상)
	 */
	static Judgment event(EventSignals s, boolean started) {
		if (s == null) {
			return Judgment.NO_DATA;
		}
		Double ratio = s.errorRatio();
		if (s.redisDown()) {
			return new Judgment(MonitoringLevel.DANGER, "Redis 연결 끊김");
		}
		if (ratio != null && ratio >= ERROR_RATIO_DANGER) {
			return new Judgment(MonitoringLevel.DANGER, "5xx " + percent(ratio));
		}
		if (s.p95Seconds() != null && s.p95Seconds() >= P95_DANGER_SECONDS) {
			return new Judgment(MonitoringLevel.DANGER, "p95 " + latency(s.p95Seconds()));
		}
		if (started && cursorStalled(s)) {
			return new Judgment(MonitoringLevel.DANGER, "커서 1분 정지");
		}
		if (!s.complete()) {
			return Judgment.NO_DATA;
		}
		if (s.redisChecking()) {
			return new Judgment(MonitoringLevel.WARN, "Redis 연결 확인 중");
		}
		if (ratio != null && ratio >= ERROR_RATIO_WARN) {
			return new Judgment(MonitoringLevel.WARN, "5xx " + percent(ratio));
		}
		if (s.p95Seconds() != null && s.p95Seconds() >= P95_WARN_SECONDS) {
			return new Judgment(MonitoringLevel.WARN, "p95 " + latency(s.p95Seconds()));
		}
		if (s.podsAtMax()) {
			return new Judgment(MonitoringLevel.WARN, "Pod 최대 " + s.apiReady() + "/" + s.hpaMax());
		}
		return new Judgment(MonitoringLevel.OK, "정상");
	}

	/** 입장 직전(T−10분 ~ 입장): Redis · Pod만 본다 */
	static Judgment preEntry(EventSignals s) {
		if (s == null) {
			return Judgment.NO_DATA;
		}
		if (s.redisDown()) {
			return new Judgment(MonitoringLevel.DANGER, "Redis 연결 끊김");
		}
		if (!s.complete()) {
			return Judgment.NO_DATA;
		}
		if (s.redisChecking()) {
			return new Judgment(MonitoringLevel.WARN, "Redis 연결 확인 중");
		}
		if (s.podsAtMax()) {
			return new Judgment(MonitoringLevel.WARN, "Pod 최대 " + s.apiReady() + "/" + s.hpaMax());
		}
		return new Judgment(MonitoringLevel.OK, "정상");
	}

	/**
	 * 커서 1분 정지: 커서 변화 0 · 대기 인원(번호표 끝 − 커서, 0 하한) > 0 · 재고 > 0.
	 * 대기가 있으면 커서 < 번호표 끝이라 입장 수 = 커서 — 그래서 입장 그래프 식이 아니라 커서 변화로 본다.
	 * 커서가 번호표 끝보다 앞서 있으면(테스트 13) 대기 0이라 정지가 아니다
	 */
	static boolean cursorStalled(EventSignals s) {
		return s.cursorChanges() != null && s.cursorChanges() == 0 && s.waiting() != null && s.waiting() > 0
				&& s.stock() != null && s.stock() > 0;
	}

	/** KPI 중 주의 값(화면은 황토색 글씨). Redis 연결은 확인 중 · 끊김 둘 다 */
	static List<String> kpiWarnings(Double errorRatio, Double p95Seconds, Integer podsReady, Integer podsMax,
			Double redisMemoryRatio, Double redisRestarts, RedisConnection redis) {
		List<String> warn = new ArrayList<>();
		if (errorRatio != null && errorRatio >= ERROR_RATIO_WARN) {
			warn.add("errorRatio");
		}
		if (p95Seconds != null && p95Seconds >= P95_WARN_SECONDS) {
			warn.add("p95");
		}
		if (podsReady != null && podsMax != null && podsMax > 0 && podsReady >= podsMax) {
			warn.add("pods");
		}
		if (redisMemoryRatio != null && redisMemoryRatio >= REDIS_MEMORY_WARN) {
			warn.add("redisMemory");
		}
		if (redisRestarts != null && redisRestarts > 0) {
			warn.add("redisRestarts");
		}
		if (redis == RedisConnection.CHECKING || redis == RedisConnection.DOWN) {
			warn.add("redisConnection");
		}
		return warn;
	}

	// ---------------------------------------------------------------- 클러스터

	/** 애드온 하나(Deployment · StatefulSet). available이 null이면 시리즈 없음(배포 안 됨) */
	record AddonMember(String group, String namespace, String name, Integer available, Integer desired) {

		boolean healthy() {
			return available != null && desired != null && available >= desired;
		}

		/** 핵심 애드온 가용 0 */
		boolean down() {
			return available == null || available == 0;
		}

	}

	/**
	 * 위험: system 노드 Ready < 2, 핵심 애드온 가용 0. 주의: Pending Pod 5분 이상.
	 * 쿼리 오류로 빈 재료(null)가 있으면 보이는 위험이 없을 때 UNKNOWN.
	 * @param available Prometheus를 읽었는지(kube-state-metrics 응답 포함)
	 * @param addons null이면 애드온 쿼리 오류(모름)
	 */
	static Judgment cluster(boolean available, Integer systemReady, int readyNodes, List<AddonMember> addons,
			Integer pendingOver5m) {
		if (!available) {
			return Judgment.NO_DATA;
		}
		if (systemReady != null && systemReady < SYSTEM_NODES_MIN) {
			return new Judgment(MonitoringLevel.DANGER, "system 노드 Ready " + systemReady);
		}
		for (AddonMember a : addons == null ? List.<AddonMember>of() : addons) {
			if (a.down()) {
				return new Judgment(MonitoringLevel.DANGER, a.group() + " 가용 0");
			}
		}
		if (systemReady == null || addons == null || pendingOver5m == null) {
			return Judgment.NO_DATA;
		}
		if (pendingOver5m > 0) {
			return new Judgment(MonitoringLevel.WARN, "Pending Pod " + pendingOver5m + "개 5분 이상");
		}
		return new Judgment(MonitoringLevel.OK, "노드 " + readyNodes + " Ready");
	}

	// ---------------------------------------------------------------- 자동화

	/**
	 * 위험: 최근 24시간 *_FAILED(메일 · 지연 · 시스템 알림 제외), DLQ 메시지 있음, 긴급 단계 지연(호스팅 시작 · 준비 시작 · 입장).
	 * 주의: 주의 단계 지연(종료 시작 · 종료 완료).
	 * @param dlq 큐 이름 → 보이는 메시지 수. null이면 DLQ를 보지 않음(미설정 · AWS 조회 실패)
	 */
	static Judgment automation(List<TrafficCatalog.FailureItem> recentFailures, Map<String, Integer> dlq,
			List<Delay> delays) {
		List<String> danger = new ArrayList<>();
		if (!recentFailures.isEmpty()) {
			TrafficCatalog.FailureItem latest = recentFailures.get(0);
			danger.add("24시간 실패 " + recentFailures.size()
					+ (latest.eventId() == null ? "" : " · " + latest.eventId() + "번 " + stepLabel(latest.step())));
		}
		if (dlq != null) {
			List<String> queues = dlq.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() > 0)
				.map(Map.Entry::getKey).sorted().toList();
			if (!queues.isEmpty()) {
				danger.add("DLQ " + String.join(", ", queues));
			}
		}
		List<Delay> urgent = delays.stream().filter(d -> d.step().urgent()).toList();
		if (!urgent.isEmpty()) {
			danger.add(summarize(urgent));
		}
		if (!danger.isEmpty()) {
			return new Judgment(MonitoringLevel.DANGER, String.join(" · ", danger));
		}
		if (!delays.isEmpty()) {
			return new Judgment(MonitoringLevel.WARN, summarize(delays));
		}
		return new Judgment(MonitoringLevel.OK, "최근 24시간 실패 0");
	}

	/** "7번 준비 시작 12분 지연 외 1" */
	private static String summarize(List<Delay> delays) {
		return delays.get(0).reason() + (delays.size() > 1 ? " 외 " + (delays.size() - 1) : "");
	}

	/** 사유 문구용 짧은 단계 이름 */
	static String stepLabel(FailureStep step) {
		return switch (step) {
			case DEPLOY -> "등록";
			case HOSTING_START -> "호스팅 시작";
			case PREPARE -> "준비";
			case END -> "종료";
			case PROVISIONING -> "환경 준비";
			case MAIL -> "메일";
			case DELAY -> "지연";
			case SYSTEM -> "시스템";
			case OTHER -> "알림";
		};
	}

	/**
	 * 단계 지연 판정 재료. registry 값 + 테넌트 스키마 hosting_end_at(못 읽었으면 null — 종료 2종을 보지 않는다).
	 * approvedAt(processed_at)은 WF2-2 · WF4 실행을 찾는 시작 기준(EventExecutionService 자동화 실행 상세와 같은 범위).
	 */
	record DelayTarget(Long eventId, String tenantId, LocalDateTime approvedAt, LocalDateTime hostingStartAt,
			LocalDateTime startAt, LocalDateTime hostingEndAt, EventStatus eventStatus, HostingStatus hostingStatus) {

		/** 준비(WF2-2) 스케줄 */
		LocalDateTime prepareAt() {
			return startAt == null ? null : EventPhase.prepareAt(startAt);
		}

		LocalDateTime entryAt() {
			return startAt == null ? null : EntryOpen.at(startAt);
		}

	}

	/** WF2-2 · WF4 실행이 시작됐는지. 있으면 true, 없으면 false, 모르면(조회 실패 · 시간 상한) null — 그 단계는 이번에 판정하지 않는다 */
	@FunctionalInterface
	interface ExecutionCheck {

		Boolean started(DelayStep step, DelayTarget target);

	}

	/** 판정한 지연. minutes = 예정 시각부터 판정 시각까지(분) */
	record Delay(DelayTarget target, DelayStep step, LocalDateTime planned, long minutes) {

		/** 자동화 칸 사유: "7번 준비 시작 12분 지연" */
		String reason() {
			return target.eventId() + "번 " + step.label() + " " + minutes + "분 지연";
		}

	}

	/** WF4(종료)가 도는 호스팅 상태 — WF1-2가 끝까지 성공해 event-end 스케줄을 만든 뒤, 아직 ENDED · END_FAILED가 아님 */
	static boolean endStepsApply(HostingStatus hosting) {
		return hosting == HostingStatus.DEPLOYED || hosting == HostingStatus.HOSTING
				|| hosting == HostingStatus.HOSTING_FAILED;
	}

	/**
	 * 예정 단계 지연 5종(1분 주기 확인 알림 · OPS-05 자동화 칸 공통). 예정 + 단계별 기준({@link DelayStep#after})을 넘었고
	 * 예정 + window(기본 24시간) 안일 때만 본다 — 오래 멈춘 이벤트가 끝없이 지연으로 남거나 첫 배포 때 한꺼번에 알림이 몰리지 않게.
	 * - 호스팅 시작: hosting_start_at, 호스팅 PENDING · DEPLOYED 그대로
	 * - 준비 시작: EventPhase.prepareAt(시작 − 준비 리드), 이벤트 SCHEDULED이고 WF2-2 실행이 없음
	 * - 입장: EntryOpen.at(시작 − 입장 리드), 이벤트 SCHEDULED 그대로(WF2-2는 입장 직후 OPEN으로 바꾼다)
	 * - 종료 시작 · 종료 완료: hosting_end_at, WF4가 도는 호스팅 상태({@link #endStepsApply})에서 WF4 실행이 없음 / ENDED가 아님
	 * 제외: 배포 · 호스팅 시작 실패(DEPLOY_FAILED · HOSTING_FAILED)는 앞 3종만 뺀다 — 실패 알림 · 실패 목록이 따로 보여 준다.
	 * DEPLOY_FAILED는 WF1-2가 event-end 스케줄을 만들기 전에 실패해 WF4가 돌지 않으므로 종료 2종에도 들지 않는다.
	 * 같은 단계가 이미 실패 상태(OPEN_FAILED · END_FAILED)면 지연이 아니다(실패 알림이 있다). 한 이벤트에 여러 단계가 같이 늦을 수 있다.
	 * 실행 확인(executions)은 그 단계의 상태 · 시각 조건이 맞을 때만 부른다.
	 */
	static List<Delay> delays(List<DelayTarget> targets, LocalDateTime now, Duration window, ExecutionCheck executions) {
		List<Delay> delays = new ArrayList<>();
		for (DelayTarget t : targets) {
			HostingStatus h = t.hostingStatus();
			boolean hostingFailed = h == HostingStatus.DEPLOY_FAILED || h == HostingStatus.HOSTING_FAILED;
			boolean scheduled = t.eventStatus() == EventStatus.SCHEDULED;
			if (!hostingFailed && (h == HostingStatus.PENDING || h == HostingStatus.DEPLOYED)) {
				add(delays, t, DelayStep.HOSTING_START, t.hostingStartAt(), now, window, null);
			}
			if (!hostingFailed && scheduled) {
				add(delays, t, DelayStep.PREPARE_START, t.prepareAt(), now, window, executions);
				add(delays, t, DelayStep.ENTRY_OPEN, t.entryAt(), now, window, null);
			}
			if (endStepsApply(h)) {
				add(delays, t, DelayStep.END_START, t.hostingEndAt(), now, window, executions);
				add(delays, t, DelayStep.END_DONE, t.hostingEndAt(), now, window, null);
			}
		}
		return delays;
	}

	/** 예정 + 기준을 넘었고(경계 미포함) 예정 + window 안(경계 포함)이면 지연. executions가 있으면 실행이 없다고 확인될 때만 */
	private static void add(List<Delay> delays, DelayTarget t, DelayStep step, LocalDateTime planned, LocalDateTime now,
			Duration window, ExecutionCheck executions) {
		if (planned == null || !now.isAfter(planned.plus(step.after())) || now.isAfter(planned.plus(window))) {
			return;
		}
		if (executions != null && !Boolean.FALSE.equals(executions.started(step, t))) {
			return;
		}
		delays.add(new Delay(t, step, planned, Duration.between(planned, now).toMinutes()));
	}

	// ---------------------------------------------------------------- 플랫폼

	/**
	 * 위험: RDS가 available 아님, Portal 정상 대상 0. 주의: RDS CPU 80% 이상.
	 * @param available AWS를 읽었는지
	 */
	static Judgment platform(boolean available, String rdsStatus, Double rdsCpuPercent, Integer portalHealthy,
			int healthyTargets, int totalTargets) {
		if (!available) {
			return Judgment.NO_DATA;
		}
		if (rdsStatus != null && !"available".equals(rdsStatus)) {
			return new Judgment(MonitoringLevel.DANGER, "RDS " + rdsStatus);
		}
		if (portalHealthy != null && portalHealthy == 0) {
			return new Judgment(MonitoringLevel.DANGER, "Portal 정상 대상 0");
		}
		if (rdsCpuPercent != null && rdsCpuPercent >= RDS_CPU_WARN_PERCENT) {
			return new Judgment(MonitoringLevel.WARN, "RDS CPU " + Math.round(rdsCpuPercent) + "%");
		}
		String rds = rdsStatus == null ? "" : "RDS 가동 · ";
		return new Judgment(MonitoringLevel.OK, rds + "Portal·Console " + healthyTargets + "/" + totalTargets);
	}

	// ---------------------------------------------------------------- 표기

	/** 0.0083 → "0.8%" */
	static String percent(double ratio) {
		return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
	}

	/** 0.62 → "620ms", 2.3 → "2.3초" */
	static String latency(double seconds) {
		return seconds < 1 ? Math.round(seconds * 1000) + "ms" : String.format(Locale.ROOT, "%.1f초", seconds);
	}

}

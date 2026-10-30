package com.tetra.ops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ApplicationStatus;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.domain.ProvisioningStatus;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository.CenterRow;
import com.tetra.ops.monitoring.DelayStep;
import com.tetra.ops.notification.NotificationRules.Chip;
import com.tetra.ops.notification.NotificationRules.State;
import com.tetra.ops.notification.NotificationRules.StateKind;

/** OPS-08 알림센터 칩 · 이동 경로 · 현재 상태 규칙 */
class NotificationRulesTest {

	private static final String TENANT = "a1b2c3d4e5f6";

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 30, 15, 0);

	/** 승인된 이벤트(배포 실패 · 오픈 예정) · 준비 완료 활성 테넌트(비밀번호 미설정)의 행 */
	private static CenterRow row(String category, Long eventId, String dedupeKey) {
		return row(category, eventId, dedupeKey, ApplicationStatus.APPROVED, HostingStatus.DEPLOY_FAILED,
				EventStatus.SCHEDULED);
	}

	private static CenterRow row(String category, Long eventId, String dedupeKey, ApplicationStatus app,
			HostingStatus hosting, EventStatus event) {
		var n = OperatorNotification.unread(TENANT, eventId, category, "URGENT", AT, "내용", dedupeKey);
		return new CenterRow(n, "A사", ProvisioningStatus.READY, AccountStatus.ACTIVE, false, app, hosting, event);
	}

	private static State state(CenterRow row) {
		return NotificationRules.state(row, Map.of());
	}

	private static String link(String category, Long eventId, String tenantId, ApplicationStatus app,
			Set<Long> trafficEventIds) {
		return NotificationRules.link(NotificationRules.chip(category, eventId), eventId, tenantId, app, trafficEventIds);
	}

	@Test
	void chipByCategory() {
		assertThat(NotificationRules.chip("APPLICATION", 1L)).isEqualTo(Chip.APPLICATION);
		assertThat(NotificationRules.chip("HOSTING", 1L)).isEqualTo(Chip.EVENT);
		assertThat(NotificationRules.chip("EVENT", 1L)).isEqualTo(Chip.EVENT);
		assertThat(NotificationRules.chip("DELAY", 1L)).isEqualTo(Chip.EVENT);
		assertThat(NotificationRules.chip("ACCOUNT", null)).isEqualTo(Chip.TENANT);
		assertThat(NotificationRules.chip("PROVISIONING", null)).isEqualTo(Chip.TENANT);
		// 개인키 메일(이벤트 있음) · 비밀번호 설정 메일(이벤트 없음)
		assertThat(NotificationRules.chip("MAIL", 1L)).isEqualTo(Chip.EVENT);
		assertThat(NotificationRules.chip("MAIL", null)).isEqualTo(Chip.TENANT);
		assertThat(NotificationRules.chip("REPORT", null)).isEqualTo(Chip.REPORT);
		assertThat(NotificationRules.chip("SYSTEM", null)).isEqualTo(Chip.SYSTEM);
		// 모르는 값 → 칩 없음(화면 "전체"에서만)
		assertThat(NotificationRules.chip("SLACK", 1L)).isNull();
		assertThat(NotificationRules.chip("hosting", 1L)).isNull();
	}

	@Test
	void linkByChip() {
		Set<Long> none = Set.of();
		assertThat(link("APPLICATION", 1043L, TENANT, ApplicationStatus.APPLIED, none)).isEqualTo("/events/applications/1043");
		assertThat(link("ACCOUNT", null, TENANT, null, none)).isEqualTo("/tenants/" + TENANT);
		assertThat(link("PROVISIONING", null, TENANT, null, none)).isEqualTo("/tenants/" + TENANT);
		assertThat(link("MAIL", null, TENANT, null, none)).isEqualTo("/tenants/" + TENANT);
		assertThat(link("REPORT", null, null, null, none)).isEqualTo("/logs/reports");
		assertThat(link("SYSTEM", null, null, null, none)).isEqualTo("/monitoring/traffic");
	}

	@Test
	void eventLinkGoesToTrafficOnlyInsideDropdownRange() {
		Set<Long> traffic = Set.of(1043L);
		for (String category : new String[] { "HOSTING", "EVENT", "DELAY", "MAIL" }) {
			assertThat(link(category, 1043L, TENANT, ApplicationStatus.APPROVED, traffic))
				.as(category).isEqualTo("/monitoring/traffic?eventId=1043");
			assertThat(link(category, 1044L, TENANT, ApplicationStatus.APPROVED, traffic))
				.as(category).isEqualTo("/events/progress/1044");
		}
	}

	@Test
	void eventLinkForNotApprovedGoesToApplicationDetail() {
		// 진행 현황 상세는 APPROVED만 열린다. 드롭다운 범위에 있어도(있을 수 없지만) 승인 전이면 신청 현황 상세
		for (ApplicationStatus status : new ApplicationStatus[] { ApplicationStatus.APPLIED, ApplicationStatus.REJECTED,
				ApplicationStatus.APPLYING, ApplicationStatus.APPLY_FAILED }) {
			assertThat(link("HOSTING", 1043L, TENANT, status, Set.of(1043L))).as(status.name())
				.isEqualTo("/events/applications/1043");
		}
		// registry 행이 없음(상태 모름)
		assertThat(link("EVENT", 1043L, TENANT, null, Set.of())).isEqualTo("/events/applications/1043");
	}

	@Test
	void linkIsNullWithoutNeededId() {
		Set<Long> traffic = Set.of(1043L);
		assertThat(link("APPLICATION", null, TENANT, null, traffic)).isNull();
		assertThat(link("HOSTING", null, TENANT, null, traffic)).isNull();
		assertThat(link("DELAY", null, TENANT, null, traffic)).isNull();
		assertThat(link("ACCOUNT", null, null, null, traffic)).isNull();
		assertThat(link("PROVISIONING", null, null, null, traffic)).isNull();
		assertThat(link("MAIL", null, null, null, traffic)).isNull();
		assertThat(link("UNKNOWN", 1043L, TENANT, ApplicationStatus.APPROVED, traffic)).isNull();
	}

	@Test
	void stateIsCurrentValueOfRelatedColumn() {
		assertThat(state(row("APPLICATION", 1L, null)))
			.isEqualTo(new State(StateKind.APPLICATION_STATUS, "APPROVED", false));
		assertThat(state(row("HOSTING", 1L, null))).isEqualTo(new State(StateKind.HOSTING_STATUS, "DEPLOY_FAILED", true));
		assertThat(state(row("EVENT", 1L, null))).isEqualTo(new State(StateKind.EVENT_STATUS, "SCHEDULED", false));
		assertThat(state(row("PROVISIONING", null, null)))
			.isEqualTo(new State(StateKind.PROVISIONING_STATUS, "READY", false));
		assertThat(state(row("ACCOUNT", null, null))).isEqualTo(new State(StateKind.ACCOUNT_STATUS, "ACTIVE", false));
		assertThat(state(row("REPORT", null, null))).isNull();
		assertThat(state(row("SLACK", 1L, null))).isNull();
	}

	@Test
	void stateIsNullWhenColumnHasNoValue() {
		// 승인 전 이벤트는 호스팅 · 이벤트 상태가 없다
		CenterRow applied = row("HOSTING", 1L, null, ApplicationStatus.APPLIED, null, null);
		assertThat(state(applied)).isNull();
		// 이벤트 · 테넌트가 없는 알림
		var n = OperatorNotification.unread(null, null, "APPLICATION", "INFO", AT, "내용", null);
		assertThat(state(new CenterRow(n, null, null, null, false, null, null, null))).isNull();
		var account = OperatorNotification.unread(null, null, "ACCOUNT", "INFO", AT, "내용", null);
		assertThat(state(new CenterRow(account, null, null, null, false, null, null, null))).isNull();
	}

	@Test
	void delayStateFollowsStepColumn() {
		CenterRow base = row("DELAY", 1043L, null, ApplicationStatus.APPROVED, HostingStatus.HOSTING, EventStatus.OPEN_FAILED);
		for (DelayStep step : DelayStep.values()) {
			CenterRow delay = new CenterRow(OperatorNotification.unread(TENANT, 1043L, "DELAY", "URGENT", AT, "내용",
					NotificationService.delayKey(1043L, step)), "A사", ProvisioningStatus.READY, AccountStatus.ACTIVE,
					false, base.applicationStatus(), base.hostingStatus(), base.eventStatus());
			State expected = switch (step) {
				case HOSTING_START, END_START, END_DONE -> new State(StateKind.HOSTING_STATUS, "HOSTING", false);
				case PREPARE_START, ENTRY_OPEN -> new State(StateKind.EVENT_STATUS, "OPEN_FAILED", true);
			};
			assertThat(state(delay)).as(step.name()).isEqualTo(expected);
		}
		// 키가 없거나 모르는 단계
		assertThat(state(row("DELAY", 1043L, null))).isNull();
		assertThat(state(row("DELAY", 1043L, "DELAY:1043:UNKNOWN_STEP"))).isNull();
	}

	@Test
	void mailStateDependsOnEvent() {
		// 비밀번호 설정 메일(테넌트) → 설정 여부
		var passwordMail = OperatorNotification.unread(TENANT, null, "MAIL", "WARNING", AT, "내용", null);
		assertThat(state(new CenterRow(passwordMail, "A사", ProvisioningStatus.READY, AccountStatus.ACTIVE, false, null,
				null, null))).isEqualTo(new State(StateKind.PASSWORD_SETUP, "NOT_SET", false));
		assertThat(state(new CenterRow(passwordMail, "A사", ProvisioningStatus.READY, AccountStatus.ACTIVE, true, null,
				null, null))).isEqualTo(new State(StateKind.PASSWORD_SETUP, "SET", false));
		// 테넌트 행이 없으면 모름
		assertThat(state(new CenterRow(passwordMail, null, null, null, false, null, null, null))).isNull();
		// 개인키 메일(이벤트) → 없음
		assertThat(state(row("MAIL", 1043L, null))).isNull();
	}

	@Test
	void failedValuesAreAlerts() {
		assertThat(state(row("HOSTING", 1L, null, ApplicationStatus.APPROVED, HostingStatus.END_FAILED, null)).alert())
			.isTrue();
		assertThat(state(row("HOSTING", 1L, null, ApplicationStatus.APPROVED, HostingStatus.HOSTING_FAILED, null)).alert())
			.isTrue();
		assertThat(state(row("APPLICATION", 1L, null, ApplicationStatus.APPLY_FAILED, null, null)).alert()).isTrue();
		// 반려 · 종료는 실패가 아니다
		assertThat(state(row("APPLICATION", 1L, null, ApplicationStatus.REJECTED, null, null)).alert()).isFalse();
		assertThat(state(row("HOSTING", 1L, null, ApplicationStatus.APPROVED, HostingStatus.ENDED, null)).alert()).isFalse();
		// 환경 준비 실패는 값이 FAILED
		var provisioning = OperatorNotification.unread(TENANT, null, "PROVISIONING", "URGENT", AT, "내용", null);
		assertThat(NotificationRules.state(new CenterRow(provisioning, "A사", ProvisioningStatus.FAILED,
				AccountStatus.INACTIVE, false, null, null, null), Map.of()))
			.isEqualTo(new State(StateKind.PROVISIONING_STATUS, "FAILED", true));
	}

	@Test
	void systemStateIsCurrentDlqDepth() {
		String queue = "tetra-dev-approve-result-dlq";
		CenterRow system = new CenterRow(OperatorNotification.unread(null, null, "SYSTEM", "URGENT", AT, "내용",
				NotificationService.dlqKey(queue, AT.toLocalDate())), null, null, null, false, null, null, null);
		assertThat(NotificationRules.dlqQueue(system)).contains(queue);
		assertThat(NotificationRules.state(system, Map.of(queue, 3)))
			.isEqualTo(new State(StateKind.DLQ_MESSAGES, "3", true));
		assertThat(NotificationRules.state(system, Map.of(queue, 0)))
			.isEqualTo(new State(StateKind.DLQ_MESSAGES, "0", false));
		// 못 읽음
		assertThat(NotificationRules.state(system, Map.of())).isNull();
		// SYSTEM이 아니면 큐를 묻지 않는다
		assertThat(NotificationRules.dlqQueue(row("HOSTING", 1L, NotificationService.dlqKey(queue, AT.toLocalDate()))))
			.isEmpty();
	}

	@Test
	void dedupeKeysParseBack() {
		assertThat(NotificationService.dlqQueueOf("DLQ:tetra-dev-scheduler-dlq:2026-10-30")).contains("tetra-dev-scheduler-dlq");
		assertThat(NotificationService.dlqQueueOf("DLQ::2026-10-30")).isEmpty();
		assertThat(NotificationService.dlqQueueOf("DELAY:1:HOSTING_START")).isEmpty();
		assertThat(NotificationService.dlqQueueOf(null)).isEmpty();
		for (DelayStep step : DelayStep.values()) {
			assertThat(NotificationService.delayStepOf(NotificationService.delayKey(7L, step))).contains(step);
		}
		assertThat(NotificationService.delayStepOf("DLQ:q:2026-10-30")).isEmpty();
		assertThat(NotificationService.delayStepOf(null)).isEmpty();
	}

}

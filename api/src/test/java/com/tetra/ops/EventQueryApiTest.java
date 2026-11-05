package com.tetra.ops;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.event.EntryOpen;
import com.tetra.ops.external.DownloadUrlSigner.SignedUrl;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;
import com.tetra.ops.tenancy.TenantRouter;

/** OPS-02·03·03-1·04·04-1·04-3 조회 (§7.2, 7.5~7.8). */
class EventQueryApiTest extends IntegrationTest {

	/** 실제 라우팅 그대로, OPS-04 목록이 테넌트 스키마에 들어간 횟수만 센다 */
	@MockitoSpyBean
	TenantRouter router;

	@Test
	void detailBeforeApprovalHasNoHostingStatusOrResources() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(6001, A).name("추석맞이 쿠폰").applied().insert();
		data.statusLog(A, 6001, "APPLICATION", null, "APPLYING", TestData.BASE, null);
		data.statusLog(A, 6001, "APPLICATION", "APPLYING", "APPLIED", TestData.BASE.plusMinutes(1), null);

		mvc.perform(get("/api/ops/events/{id}", 6001).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.basic.eventId").value(6001))
			.andExpect(jsonPath("$.basic.name").value("추석맞이 쿠폰"))
			.andExpect(jsonPath("$.basic.tenantName").value("A사"))
			.andExpect(jsonPath("$.basic.eventType").value("COUPON"))
			.andExpect(jsonPath("$.basic.stockCount").value(10000))
			.andExpect(jsonPath("$.basic.appliedAt").value("2026-10-01T10:00:00+09:00"))
			.andExpect(jsonPath("$.status.applicationStatus").value("APPLIED"))
			.andExpect(jsonPath("$.status.hostingStatus").doesNotExist())
			.andExpect(jsonPath("$.status.resultAvailable").value(false))
			.andExpect(jsonPath("$.resources.templateId").value(1))
			.andExpect(jsonPath("$.resources.domain").doesNotExist())
			.andExpect(jsonPath("$.resources.publicKey").doesNotExist())
			.andExpect(jsonPath("$.statusLogs.length()").value(2))
			.andExpect(jsonPath("$.statusLogs[0].statusType").value("APPLICATION"))
			.andExpect(jsonPath("$.statusLogs[0].newStatus").value("APPLYING"))
			.andExpect(jsonPath("$.statusLogs[0].actor.type").value("TENANT"))
			.andExpect(jsonPath("$.statusLogs[0].actor.name").value("A사"))
			.andExpect(jsonPath("$.statusLogs[1].prevStatus").value("APPLYING"))
			.andExpect(jsonPath("$.statusLogs[1].actor.type").value("SYSTEM"))
			.andExpect(jsonPath("$.statusLogs[1].actor.name").doesNotExist())
			.andExpect(jsonPath("$.applicationLogs").doesNotExist());
	}

	/** 상태 처리 기록: 신청 · 호스팅 · 이벤트 로그 전체, changed_at 오름차순(같은 시각은 기록 순서), 원인 · 처리자 */
	@Test
	void detailHasAllStatusLogsInOrderWithDetailAndActor() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(6004, A).approved("DEPLOYED", "SCHEDULED", "hist0004").insert();
		var t = TestData.BASE;
		// 기록 순서와 시각 순서를 일부러 섞는다(가장 늦은 것을 먼저 넣음)
		data.statusLog(A, 6004, "HOSTING", "DEPLOY_FAILED", "DEPLOYED", t.plusHours(3), null);
		data.statusLog(A, 6004, "APPLICATION", null, "APPLYING", t, null);
		data.statusLog(A, 6004, "APPLICATION", "APPLYING", "APPLIED", t.plusMinutes(1), null);
		// 승인 한 번에 같은 시각으로 3줄(신청 승인은 운영자, 호스팅 · 이벤트 시작은 자동)
		data.statusLog(A, 6004, "APPLICATION", "APPLIED", "APPROVED", t.plusHours(1), null, TestData.OPERATOR_ID);
		data.statusLog(A, 6004, "HOSTING", null, "PENDING", t.plusHours(1), null);
		data.statusLog(A, 6004, "EVENT", null, "SCHEDULED", t.plusHours(1), null);
		data.statusLog(A, 6004, "HOSTING", "PENDING", "DEPLOY_FAILED", t.plusHours(2),
				"TIMED_OUT: EventAppSyncTimedOut / ArgoCD 동기화 대기 시간 초과");

		mvc.perform(get("/api/ops/events/{id}", 6004).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.statusLogs.length()").value(7))
			.andExpect(jsonPath("$.statusLogs[*].statusType").value(org.hamcrest.Matchers.contains("APPLICATION",
					"APPLICATION", "APPLICATION", "HOSTING", "EVENT", "HOSTING", "HOSTING")))
			.andExpect(jsonPath("$.statusLogs[*].newStatus").value(org.hamcrest.Matchers.contains("APPLYING", "APPLIED",
					"APPROVED", "PENDING", "SCHEDULED", "DEPLOY_FAILED", "DEPLOYED")))
			.andExpect(jsonPath("$.statusLogs[*].actor.type").value(org.hamcrest.Matchers.contains("TENANT", "SYSTEM",
					"OPERATOR", "SYSTEM", "SYSTEM", "SYSTEM", "SYSTEM")))
			.andExpect(jsonPath("$.statusLogs[2].actor.name").value("홍길동"))
			.andExpect(jsonPath("$.statusLogs[3].prevStatus").doesNotExist())
			.andExpect(jsonPath("$.statusLogs[5].prevStatus").value("PENDING"))
			.andExpect(jsonPath("$.statusLogs[5].detail").value("TIMED_OUT: EventAppSyncTimedOut / ArgoCD 동기화 대기 시간 초과"))
			.andExpect(jsonPath("$.statusLogs[5].changedAt").value("2026-10-01T12:00:00+09:00"))
			.andExpect(jsonPath("$.statusLogs[6].detail").doesNotExist());
	}

	@Test
	void detailAfterApprovalHasDomainAndLatestActiveKey() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(6002, A).approved("DEPLOYED", "SCHEDULED", "k7f2q9xa").insert();
		data.activeKey(A, 6002, 1, "PEM-1");
		data.activeKey(A, 6002, 2, "PEM-2");
		data.statusLog(A, 6002, "HOSTING", null, "PENDING", TestData.BASE, null);

		mvc.perform(get("/api/ops/events/{id}", 6002).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status.hostingStatus").value("DEPLOYED"))
			.andExpect(jsonPath("$.resources.domain").value("k7f2q9xa." + A))
			// 이벤트 주소는 플랫폼 도메인(application.yml 기본 tetraops.cloud) 아래 — 테넌트 회사 도메인(a사.example.com)이 아님
			.andExpect(jsonPath("$.resources.fullDomain").value("k7f2q9xa." + A + ".tetraops.cloud"))
			.andExpect(jsonPath("$.resources.publicKey").value("PEM-2"))
			// 호스팅 로그도 상태 처리 기록에 나온다(승인 때 자동으로 쓰는 시작 로그)
			.andExpect(jsonPath("$.statusLogs.length()").value(1))
			.andExpect(jsonPath("$.statusLogs[0].statusType").value("HOSTING"))
			.andExpect(jsonPath("$.statusLogs[0].actor.type").value("SYSTEM"));
	}

	@Test
	void expiredEventHidesDomainAndKey() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(6003, A).approved("ENDED", "EXPIRED", "k7f2q9xa").insert();
		data.activeKey(A, 6003, 1, "PEM-1");

		mvc.perform(get("/api/ops/events/{id}", 6003).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resources.domain").doesNotExist())
			.andExpect(jsonPath("$.resources.publicKey").doesNotExist());
	}

	@Test
	void applicationListOrderAndCounts() throws Exception {
		LocalDateTime t = TestData.BASE;
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(7001, A).status("APPROVED").approved("PENDING", "SCHEDULED", "aaaa0001").appliedAt(t).insert();
		data.event(7002, A).status("REJECTED").appliedAt(t.plusHours(3)).processedAt(t.plusHours(4)).insert();
		data.event(7003, A).status("APPLY_FAILED").appliedAt(t.plusHours(1)).insert();
		data.event(7004, A).status("APPLYING").appliedAt(t.plusHours(5)).insert();
		data.event(7005, A).applied().appliedAt(t.plusHours(6)).insert();
		data.event(7006, A).applied().appliedAt(t.plusHours(2)).insert();

		mvc.perform(get("/api/ops/applications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.APPLIED").value(2))
			.andExpect(jsonPath("$.counts.APPLYING").value(1))
			.andExpect(jsonPath("$.counts.APPLY_FAILED").value(1))
			.andExpect(jsonPath("$.counts.APPROVED").value(1))
			.andExpect(jsonPath("$.counts.REJECTED").value(1))
			// APPLIED(오름차순) → APPLYING → 나머지(applied_at 내림차순)
			.andExpect(jsonPath("$.items[*].eventId").value(org.hamcrest.Matchers.contains(7006, 7005, 7004, 7002, 7003, 7001)))
			.andExpect(jsonPath("$.items[3].processedAt").value("2026-10-01T14:00:00+09:00"))
			.andExpect(jsonPath("$.items[0].tenantName").value("A사"));
	}

	@Test
	void applicationCountsAreZeroFilled() throws Exception {
		mvc.perform(get("/api/ops/applications").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.APPLIED").value(0))
			.andExpect(jsonPath("$.counts.REJECTED").value(0))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void liveEventsAreApprovedOnlyByStartDescending() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(8001, A).approved("PENDING", "SCHEDULED", "aaaa0001").startAt(TestData.FUTURE).insert();
		data.event(8002, A).approved("HOSTING", "OPEN", "aaaa0002").startAt(TestData.FUTURE.plusDays(1)).insert();
		data.event(8003, A).approved("ENDED", "EXPIRED", "aaaa0003").startAt(TestData.FUTURE.minusDays(1)).insert();
		data.event(8004, A).approved("DEPLOY_FAILED", "SCHEDULED", "aaaa0004").startAt(TestData.FUTURE).insert();
		data.event(8005, A).applied().insert();

		mvc.perform(get("/api/ops/live-events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.PENDING").value(1))
			.andExpect(jsonPath("$.counts.HOSTING").value(1))
			.andExpect(jsonPath("$.counts.ENDED").value(1))
			.andExpect(jsonPath("$.counts.DEPLOY_FAILED").doesNotExist())
			.andExpect(jsonPath("$.items.length()").value(4))
			.andExpect(jsonPath("$.items[0].eventId").value(8002))
			.andExpect(jsonPath("$.items[3].eventId").value(8003))
			.andExpect(jsonPath("$.items[0].trafficPlanCode").value("M"))
			// 타임라인용 시각 3개(TestData 기본: 이벤트 종료 = 시작 + 3시간, 호스팅 종료 = 시작 + 5시간)
			.andExpect(jsonPath("$.items[0].entryAt").value(kst(TestData.FUTURE.plusDays(1).minus(EntryOpen.ENTRY_OPEN_LEAD))))
			.andExpect(jsonPath("$.items[0].endAt").value(kst(TestData.FUTURE.plusDays(1).plusHours(3))))
			.andExpect(jsonPath("$.items[0].hostingEndAt").value(kst(TestData.FUTURE.plusDays(1).plusHours(5))))
			.andExpect(jsonPath("$.items[3].entryAt").value(kst(TestData.FUTURE.minusDays(1).minus(EntryOpen.ENTRY_OPEN_LEAD))))
			.andExpect(jsonPath("$.items[3].endAt").value(kst(TestData.FUTURE.minusDays(1).plusHours(3))))
			.andExpect(jsonPath("$.items[3].hostingEndAt").value(kst(TestData.FUTURE.minusDays(1).plusHours(5))));
	}

	/** endAt · hostingEndAt은 테넌트 스키마 event 값 그대로(+09:00), entryAt = startAt − 입장 리드(EntryOpen) */
	@Test
	void liveEventTimesComeFromTenantSchemaAndEntryLead() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		LocalDateTime start = TestData.FUTURE;
		data.event(8101, A).approved("DEPLOYED", "SCHEDULED", "aaaa0101").startAt(start).insert();
		// TestData 기본(시작 + 3시간 · + 5시간)과 다른 값으로 바꿔, 계산이 아니라 테넌트 스키마에서 읽는지 본다
		LocalDateTime endAt = start.plusMinutes(95).plusSeconds(30);
		LocalDateTime hostingEndAt = start.plusMinutes(250);
		setEndTimes(A, 8101, endAt, hostingEndAt);

		mvc.perform(get("/api/ops/live-events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].startAt").value(kst(start)))
			.andExpect(jsonPath("$.items[0].entryAt").value(kst(start.minus(EntryOpen.ENTRY_OPEN_LEAD))))
			.andExpect(jsonPath("$.items[0].endAt").value(kst(endAt)))
			.andExpect(jsonPath("$.items[0].hostingEndAt").value(kst(hostingEndAt)));
	}

	/** 테넌트 2개의 이벤트가 저마다 자기 스키마 값으로 채워지고, 테넌트 스키마에는 테넌트마다 한 번만 들어간다 */
	@Test
	void liveEventsReadEachTenantSchemaOnce() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		LocalDateTime start = TestData.FUTURE;
		data.event(8201, A).approved("HOSTING", "OPEN", "aaaa0201").startAt(start.plusDays(2)).insert();
		data.event(8202, B).approved("PENDING", "SCHEDULED", "bbbb0202").startAt(start.plusDays(1)).insert();
		data.event(8203, A).approved("DEPLOYED", "SCHEDULED", "aaaa0203").startAt(start).insert();
		setEndTimes(A, 8201, start.plusDays(2).plusMinutes(70), start.plusDays(2).plusMinutes(130));
		setEndTimes(B, 8202, start.plusDays(1).plusMinutes(80), start.plusDays(1).plusMinutes(140));
		setEndTimes(A, 8203, start.plusMinutes(90), start.plusMinutes(150));

		mvc.perform(get("/api/ops/live-events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].eventId").value(org.hamcrest.Matchers.contains(8201, 8202, 8203)))
			.andExpect(jsonPath("$.items[0].tenantName").value("A사"))
			.andExpect(jsonPath("$.items[0].endAt").value(kst(start.plusDays(2).plusMinutes(70))))
			.andExpect(jsonPath("$.items[0].hostingEndAt").value(kst(start.plusDays(2).plusMinutes(130))))
			.andExpect(jsonPath("$.items[1].tenantName").value("B사"))
			.andExpect(jsonPath("$.items[1].endAt").value(kst(start.plusDays(1).plusMinutes(80))))
			.andExpect(jsonPath("$.items[1].hostingEndAt").value(kst(start.plusDays(1).plusMinutes(140))))
			.andExpect(jsonPath("$.items[2].endAt").value(kst(start.plusMinutes(90))))
			.andExpect(jsonPath("$.items[2].hostingEndAt").value(kst(start.plusMinutes(150))));
		// 이벤트 3개 · 테넌트 2개 → 테넌트 스키마 조회 2번
		verify(router, times(2)).inTenant(any(Tenant.class), ArgumentMatchers.<Supplier<Object>>any());
	}

	/** READY가 아닌 테넌트의 이벤트 · 테넌트 스키마에 행이 없는 이벤트는 endAt · hostingEndAt null, 목록에서는 빠지지 않는다 */
	@Test
	void liveEventsWithoutTenantRowKeepNullEndTimes() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "PROVISIONING");
		LocalDateTime start = TestData.FUTURE;
		data.event(8301, A).approved("HOSTING", "OPEN", "aaaa0301").startAt(start.plusDays(2)).insert();
		// t_B에 행은 있지만 READY가 아니라 들어가지 않는다
		data.event(8302, B).approved("PENDING", "SCHEDULED", "bbbb0302").startAt(start.plusDays(1)).insert();
		data.event(8303, A).approved("PENDING", "SCHEDULED", "aaaa0303").startAt(start).registryOnly().insert();

		mvc.perform(get("/api/ops/live-events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.PENDING").value(2))
			.andExpect(jsonPath("$.items[*].eventId").value(org.hamcrest.Matchers.contains(8301, 8302, 8303)))
			.andExpect(jsonPath("$.items[0].endAt").value(kst(start.plusDays(2).plusHours(3))))
			.andExpect(jsonPath("$.items[0].hostingEndAt").value(kst(start.plusDays(2).plusHours(5))))
			.andExpect(jsonPath("$.items[1].endAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[1].hostingEndAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[1].entryAt").value(kst(start.plusDays(1).minus(EntryOpen.ENTRY_OPEN_LEAD))))
			.andExpect(jsonPath("$.items[2].endAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[2].hostingEndAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[2].entryAt").value(kst(start.minus(EntryOpen.ENTRY_OPEN_LEAD))));
		// READY인 A만
		verify(router, times(1)).inTenant(any(Tenant.class), ArgumentMatchers.<Supplier<Object>>any());
	}

	/** 한 테넌트 스키마 읽기가 실패해도 200 — 그 테넌트 이벤트만 endAt · hostingEndAt null, 다른 테넌트 · 목록 · 건수는 그대로 */
	@Test
	void liveEventsKeepListWhenOneTenantSchemaReadFails() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.tenant(B, "B사", "ACTIVE", "READY");
		LocalDateTime start = TestData.FUTURE;
		data.event(8401, A).approved("HOSTING", "OPEN", "aaaa0401").startAt(start.plusDays(2)).insert();
		data.event(8402, B).approved("PENDING", "SCHEDULED", "bbbb0402").startAt(start.plusDays(1)).insert();
		data.event(8403, B).approved("PENDING", "SCHEDULED", "bbbb0403").startAt(start).insert();
		// B 스키마 읽기만 실패시킨다(A는 실제 라우팅 그대로)
		doThrow(new DataAccessResourceFailureException("t_B 읽기 실패")).when(router)
			.inTenant(ArgumentMatchers.<Tenant>argThat(t -> t != null && B.equals(t.getTenantId())),
					ArgumentMatchers.<Supplier<Object>>any());

		mvc.perform(get("/api/ops/live-events").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.PENDING").value(2))
			.andExpect(jsonPath("$.counts.HOSTING").value(1))
			.andExpect(jsonPath("$.items[*].eventId").value(org.hamcrest.Matchers.contains(8401, 8402, 8403)))
			.andExpect(jsonPath("$.items[0].endAt").value(kst(start.plusDays(2).plusHours(3))))
			.andExpect(jsonPath("$.items[0].hostingEndAt").value(kst(start.plusDays(2).plusHours(5))))
			.andExpect(jsonPath("$.items[1].tenantName").value("B사"))
			.andExpect(jsonPath("$.items[1].endAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[1].hostingEndAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[1].entryAt").value(kst(start.plusDays(1).minus(EntryOpen.ENTRY_OPEN_LEAD))))
			.andExpect(jsonPath("$.items[2].endAt").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.items[2].hostingEndAt").value(org.hamcrest.Matchers.nullValue()));
		verify(router, times(2)).inTenant(any(Tenant.class), ArgumentMatchers.<Supplier<Object>>any());
	}

	private void setEndTimes(String tenantId, long eventId, LocalDateTime endAt, LocalDateTime hostingEndAt) {
		data.jdbc().update("UPDATE t_%s.event SET end_at = ?, hosting_end_at = ? WHERE event_id = ?".formatted(tenantId),
				endAt, hostingEndAt, eventId);
	}

	/** 응답 일시 형식: KST 벽시계(초 포함) + "+09:00" */
	private static String kst(LocalDateTime value) {
		return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "+09:00";
	}

	// ------------------------------------------------------------ OPS-04-3

	@Test
	void resultInProgress() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9001, A).approved("HOSTING", "OPEN", "aaaa0001").insert();

		mvc.perform(get("/api/ops/events/{id}/result", 9001).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.endAt").exists())
			.andExpect(jsonPath("$.aggregatedAt").doesNotExist())
			.andExpect(jsonPath("$.result").doesNotExist());
		mvc.perform(get("/api/ops/events/{id}/result/history-csv", 9001).cookie(operatorCookie()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESULT_NOT_READY"));
	}

	@Test
	void resultAggregatingWhenExpiredWithoutHistory() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9002, A).approved("ENDED", "EXPIRED", "aaaa0002").insert();
		insertSummary(9002, false);

		mvc.perform(get("/api/ops/events/{id}/result", 9002).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("AGGREGATING"))
			.andExpect(jsonPath("$.hostingStatus").value("ENDED"))
			.andExpect(jsonPath("$.eventStatus").value("EXPIRED"));
	}

	@Test
	void resultEndFailedWinsAndShowsLatestCause() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9003, A).approved("END_FAILED", "EXPIRED", "aaaa0003").insert();
		insertSummary(9003, true);
		data.statusLog(A, 9003, "HOSTING", "HOSTING", "END_FAILED", TestData.BASE, "첫 실패");
		data.statusLog(A, 9003, "HOSTING", "END_FAILED", "END_FAILED", TestData.BASE.plusHours(1), "집계 Job 실패");

		mvc.perform(get("/api/ops/events/{id}/result", 9003).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("END_FAILED"))
			.andExpect(jsonPath("$.failure.cause").value("집계 Job 실패"))
			.andExpect(jsonPath("$.failure.occurredAt").value("2026-10-01T11:00:00+09:00"));
		mvc.perform(get("/api/ops/events/{id}/result/history-csv", 9003).cookie(operatorCookie()))
			.andExpect(status().isConflict());
	}

	@Test
	void resultReadyWithMetricsCompositionAndPerMinute() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9004, A).approved("ENDED", "EXPIRED", "aaaa0004").insert();
		insertSummary(9004, true);
		LocalDateTime m = LocalDateTime.of(2026, 11, 1, 10, 0, 5);
		issuance(9004, "u1", m, "SUCCESS");
		issuance(9004, "u2", m.plusSeconds(30), "SUCCESS");
		issuance(9004, "u3", m.plusMinutes(1), "FAILED_SOLDOUT");
		issuance(9004, "U1", m.plusMinutes(1), "SUCCESS");

		mvc.perform(get("/api/ops/events/{id}/result", 9004).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("READY"))
			.andExpect(jsonPath("$.aggregatedAt").value("2026-11-02T00:12:00+09:00"))
			.andExpect(jsonPath("$.result.metrics.successCount").value(3))
			// 총 재고는 summary.total_stock(9000)이 아니라 event.stock_count(10000) (화면설계서 OPS-04-3)
			.andExpect(jsonPath("$.result.metrics.totalStock").value(10000))
			.andExpect(jsonPath("$.result.metrics.selloutRate").value(0.0003))
			.andExpect(jsonPath("$.result.metrics.soldoutCount").value(1))
			.andExpect(jsonPath("$.result.metrics.peakConcurrent").value(8920))
			.andExpect(jsonPath("$.result.metrics.maxCapacity").value(30000))
			.andExpect(jsonPath("$.result.metrics.capacityUsageRate").value(0.2973))
			// 이탈 = 세션 20 − 발급 3 − 품절 1 (번호표 10이 아니라 세션 기준)
			.andExpect(jsonPath("$.result.composition.abandonedCount").value(16))
			.andExpect(jsonPath("$.result.perMinute.length()").value(2))
			.andExpect(jsonPath("$.result.perMinute[0].minute").value("2026-11-01T10:00:00+09:00"))
			.andExpect(jsonPath("$.result.perMinute[0].successCount").value(2))
			.andExpect(jsonPath("$.result.perMinute[1].successCount").value(1))
			.andExpect(jsonPath("$.result.perMinute[1].soldoutCount").value(1));

		when(urlSigner.presignGet(any(), any(), any(), any()))
			.thenReturn(new SignedUrl("https://signed.example/csv", Instant.parse("2026-11-02T00:00:00Z")));
		mvc.perform(get("/api/ops/events/{id}/result/history-csv", 9004).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://signed.example/csv"))
			.andExpect(jsonPath("$.expiresAt").value("2026-11-02T09:00:00+09:00"));
		verify(urlSigner).presignGet(eq("tetra-audit-logs-test-" + A), eq("issuance-history/9004.csv"), eq(Duration.ofMinutes(5)),
				eq("issuance-history-9004.csv"));
	}

	@Test
	void resultDoubleChecksIssuanceRows() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9005, A).approved("ENDED", "EXPIRED", "aaaa0005").insert();
		insertSummary(9005, true);
		data.jdbc().update("""
				INSERT INTO t_%s.issuance_history (event_id, tenant_id, user_id, ticket_number, session_issued_at,
				    queue_entered_at, issued_at, result) VALUES (9005, ?, 'x', 1, NOW(), NOW(), NOW(), 'SUCCESS')"""
			.formatted(A), B);

		mvc.perform(get("/api/ops/events/{id}/result", 9005).cookie(operatorCookie()))
			.andExpect(status().isInternalServerError());
	}

	@Test
	void detailResultAvailableFollowsResultState() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9301, A).approved("HOSTING", "OPEN", "rav00001").insert();          // IN_PROGRESS
		data.event(9302, A).approved("DEPLOY_FAILED", "SCHEDULED", "rav00002").insert(); // IN_PROGRESS
		data.event(9303, A).approved("ENDED", "EXPIRED", "rav00003").insert();          // AGGREGATING
		data.event(9304, A).approved("END_FAILED", "EXPIRED", "rav00004").insert();     // END_FAILED
		data.event(9305, A).approved("ENDED", "EXPIRED", "rav00005").insert();          // READY
		insertSummary(9305, true);

		long[][] expected = { { 9301, 0 }, { 9302, 0 }, { 9303, 1 }, { 9304, 1 }, { 9305, 1 } };
		for (long[] e : expected) {
			mvc.perform(get("/api/ops/events/{id}", e[0]).cookie(operatorCookie()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status.resultAvailable").value(e[1] == 1));
		}
	}

	// ------------------------------------------------------------ OPS-02

	@Test
	void dashboardCountsAndRecentNotifications() throws Exception {
		LocalDateTime now = LocalDateTime.now();
		data.tenant(A, "A사", "a@a.com", "ACTIVE", "READY", now.withDayOfMonth(1).minusDays(1));
		data.tenant(B, "B사", "b@b.com", "INACTIVE", "READY", now);
		data.event(9101, A).approved("HOSTING", "OPEN", "aaaa0101").insert();
		data.event(9102, A).approved("PENDING", "SCHEDULED", "aaaa0102").insert();
		for (int i = 1; i <= 6; i++) {
			data.jdbc().update("""
					INSERT INTO tetra_platform.operator_notification (tenant_id, event_id, category, priority, occurred_at, content)
					VALUES (?, NULL, 'SYSTEM', 'INFO', ?, ?)""", A, TestData.BASE.plusMinutes(i), "알림 " + i);
		}

		mvc.perform(get("/api/ops/dashboard").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tenants.active").value(1))
			.andExpect(jsonPath("$.tenants.total").value(2))
			.andExpect(jsonPath("$.tenants.newThisMonth").value(1))
			.andExpect(jsonPath("$.openEvents.count").value(1))
			.andExpect(jsonPath("$.openEvents.asOf").value(org.hamcrest.Matchers.endsWith("+09:00")))
			.andExpect(jsonPath("$.recentNotifications.length()").value(5))
			.andExpect(jsonPath("$.recentNotifications[0].content").value("알림 6"));
	}

	// ------------------------------------------------------------ 예전 껍데기(501) — 지금은 모두 구현

	@Test
	void formerStubsNoLongerReturn501() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9201, A).applied().insert();
		// OPS-03-2 · 04-2 신청 내용, OPS-08 알림센터, OPS-09 리포트
		for (String path : new String[] { "/api/ops/events/9201/application-summary", "/api/ops/notifications",
				"/api/ops/reports" }) {
			mvc.perform(get(path).cookie(operatorCookie())).andExpect(status().isOk());
		}
	}

	/**
	 * 이탈은 세션 기준: 페이지에 들어왔다가 결과 없이 나간 사람(번호표를 받기 전에 나간 사람 포함).
	 * 세션 11 · 번호표 9 · 발급 5 · 품절 4 → 번호표 기준이면 0이지만 세션 기준 2. 결과보다 세션이 적으면 0.
	 */
	@Test
	void abandonedCountIsSessionsWithoutResult() throws Exception {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(9006, A).approved("ENDED", "EXPIRED", "aaaa0006").insert();
		insertSummary(9006, true);
		data.jdbc().update("UPDATE t_%s.event_result_summary SET session_count = 11, ticket_count = 9, peak_concurrent = 11,"
				.formatted(A) + " success_count = 5, soldout_count = 4 WHERE event_id = 9006");
		mvc.perform(get("/api/ops/events/{id}/result", 9006).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result.composition.successCount").value(5))
			.andExpect(jsonPath("$.result.composition.soldoutCount").value(4))
			.andExpect(jsonPath("$.result.composition.abandonedCount").value(2));

		// 집계 Job을 건너뛰어 세션이 0인데 결과가 있으면 음수 대신 0
		data.jdbc().update("UPDATE t_%s.event_result_summary SET session_count = 0, ticket_count = 0 WHERE event_id = 9006"
			.formatted(A));
		mvc.perform(get("/api/ops/events/{id}/result", 9006).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.result.composition.abandonedCount").value(0));
	}

	private void insertSummary(long eventId, boolean withHistory) {
		data.jdbc().update("""
				INSERT INTO t_%s.event_result_summary (event_id, tenant_id, session_count, ticket_count, peak_concurrent,
				    peak_at, metrics_finalized_at, total_stock, success_count, soldout_count, sellout_rate,
				    history_file_path, history_generated_at)
				VALUES (?, ?, 20, 10, 8920, '2026-11-01 10:00:30', '2026-11-01 23:00:00', ?, ?, ?, ?, ?, ?)"""
			.formatted(A), eventId, A, withHistory ? 9000 : null, withHistory ? 3 : null, withHistory ? 1 : null,
				withHistory ? 0.0003 : null, withHistory ? "issuance-history/" + eventId + ".csv" : null,
				withHistory ? "2026-11-02 00:12:00" : null);
	}

	private void issuance(long eventId, String userId, LocalDateTime queueEnteredAt, String result) {
		data.jdbc().update("""
				INSERT INTO t_%s.issuance_history (event_id, tenant_id, user_id, ticket_number, session_issued_at,
				    queue_entered_at, issued_at, result) VALUES (?, ?, ?, 1, ?, ?, ?, ?)""".formatted(A), eventId, A,
				userId, queueEnteredAt, queueEnteredAt, queueEnteredAt.plusSeconds(1), result);
	}

}

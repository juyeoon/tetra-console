package com.tetra.ops.report.event;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.report.ReportCategory;
import com.tetra.ops.report.ReportKeys;
import com.tetra.ops.report.ReportStore;
import com.tetra.ops.report.ReportStore.TenantReport;
import com.tetra.ops.tenancy.TenantRouter;

/**
 * 이벤트 결과 리포트 한 건 만들기(11-02 · 11-03 결정): 테넌트 스키마에서 값 읽기({@link EventResultReportReader}) → zip
 * ({@link EventResultReportFiles}) → {@link ReportStore#saveTenantReport}(S3 먼저 → 같은 키의 메타가 없을 때만 메타 + REPORT 알림).
 * 키 reports/{yyyy}/{mm}/event-{event_id}.zip의 yyyy/mm은 이벤트 오픈(start_at, KST)의 달, 기간은 start_at ~ end_at.
 * 잠금 없이 부르면 같은 리포트를 동시에 넣을 수 있다 — 같은 이름의 잠금({@link EventResultReportJob#LOCK_NAME}) 안에서만 부른다
 * ({@link EventResultReportJob} · 수동 생성 {@link EventResultReportGenerator}). 트랜잭션 밖에서 부른다.
 * 실패하면 예외를 그대로 던진다(다시 만들면 같은 키에 덮어쓰고 메타 · 알림은 한 번).
 */
@Service
class EventResultReportService {

	enum Outcome {

		/** 메타 · 알림을 새로 남김 */
		CREATED,
		/** 같은 리포트가 이미 있었다 — 파일만 덮어씀 */
		OVERWRITTEN,
		/** 결과 탭이 READY가 아님(발급 이력 저장 전 · 종료 실패) — 만들지 않음 */
		NOT_READY

	}

	/** 만든 결과와 리포트 키(테넌트 로그 버킷 안, NOT_READY면 null) */
	record Result(Outcome outcome, String key) {

		static final Result NOT_READY = new Result(Outcome.NOT_READY, null);

	}

	/** tenant_report_meta.name. 100자를 넘으면 ReportStore가 자른다 */
	static final String NAME_PREFIX = "이벤트 결과 — ";

	private final TenantRouter router;

	private final EventResultReportReader reader;

	private final EventResultReportFiles files;

	private final ReportStore store;

	private final Clock clock;

	EventResultReportService(TenantRouter router, EventResultReportReader reader, EventResultReportFiles files,
			ReportStore store, Clock clock) {
		this.router = router;
		this.reader = reader;
		this.files = files;
		this.store = store;
		this.clock = clock;
	}

	/** reports/{yyyy}/{mm}/event-{event_id}.zip — yyyy/mm = 이벤트 오픈(start_at, KST 벽시계)의 달 */
	static String key(long eventId, LocalDateTime startAt) {
		return ReportKeys.eventResult(YearMonth.from(startAt), eventId);
	}

	Result create(Tenant tenant, long eventId) {
		Optional<EventResultReportData> read = router.inTenant(tenant, () -> reader.read(eventId, tenant.getName()));
		if (read.isEmpty()) {
			return Result.NOT_READY;
		}
		EventResultReportData data = read.get();
		byte[] zip = files.zip(data, Kst.now(clock));
		String key = key(eventId, data.startAt());
		boolean created = store.saveTenantReport(tenant, new TenantReport(ReportCategory.EVENT_RESULT, eventId,
				NAME_PREFIX + data.eventName(), data.startAt(), data.endAt(), key, zip));
		return new Result(created ? Outcome.CREATED : Outcome.OVERWRITTEN, key);
	}

}

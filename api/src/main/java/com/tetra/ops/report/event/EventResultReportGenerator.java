package com.tetra.ops.report.event;

import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.report.event.EventResultReportService.Outcome;
import com.tetra.ops.report.event.EventResultReportService.Result;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;

/**
 * 이벤트 결과 리포트 수동 생성(운영자 POST /api/ops/reports/generate — 시험 · 재생성용). 이벤트 하나를 바로 만든다.
 * <ul>
 * <li>작업({@link EventResultReportJob})과 같은 잠금 이름(tetra:report-event-result)을 기다리지 않고 잡는다 — 못 잡으면 409
 * REPORT_BUSY(리스너 시도 · 10분 보충 · 다른 수동 요청이 만드는 중)</li>
 * <li>보충 기간(최근 N일) 조건은 보지 않는다. 결과 탭이 READY가 아니면(발급 이력 저장 전 · 종료 실패) 409 RESULT_NOT_READY,
 * 없는 이벤트 404 EVENT_NOT_FOUND, READY가 아닌 테넌트 409 TENANT_NOT_READY</li>
 * <li>저장은 멱등(같은 키 덮어쓰기, 메타 · REPORT 알림은 처음 한 번) — 여러 번 불러도 테넌트 알림은 한 번</li>
 * </ul>
 * 작업 스위치(tetra.report.event-result.enabled)와 상관없이 있다. 트랜잭션 밖에서 부른다.
 */
@Component
public class EventResultReportGenerator {

	private final DbLock lock;

	private final TenantRouter router;

	private final EventResultReportService reports;

	@Autowired
	EventResultReportGenerator(DataSource dataSource, TenantRouter router, EventResultReportService reports) {
		this(new DbLock(dataSource, EventResultReportJob.LOCK_NAME), router, reports);
	}

	/** 테스트용: 잠금을 직접 준다 */
	EventResultReportGenerator(DbLock lock, TenantRouter router, EventResultReportService reports) {
		this.lock = lock;
		this.router = router;
		this.reports = reports;
	}

	/**
	 * @param tenantId 이벤트의 테넌트
	 * @param filePath 테넌트 로그 버킷 안의 키(reports/{yyyy}/{mm}/event-{event_id}.zip, 버킷 이름 없음)
	 * @param created 메타 · 알림을 이번에 새로 남겼으면 true, 이미 있어 파일만 덮어썼으면 false
	 */
	public record Generated(String tenantId, String filePath, boolean created) {
	}

	public Generated generate(long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		AtomicReference<Result> result = new AtomicReference<>();
		if (!lock.runIfAcquired(() -> result.set(reports.create(routed.tenant(), routed.eventId())))) {
			throw new ApiException(ErrorCode.REPORT_BUSY);
		}
		Result done = result.get();
		if (done.outcome() == Outcome.NOT_READY) {
			throw new ApiException(ErrorCode.RESULT_NOT_READY);
		}
		return new Generated(routed.tenantId(), done.key(), done.outcome() == Outcome.CREATED);
	}

}

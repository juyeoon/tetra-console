package com.tetra.ops.event;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.external.DownloadUrlSigner;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenancy.TenantRouter.RoutedEvent;
import com.tetra.ops.tenant.TenantBuckets;

/** 이벤트 상세·진행 결과·CSV. eventId → registry → tenant_id → 테넌트 스키마. */
@Service
public class EventQueryService {

	private final TenantRouter router;

	private final EventReader reader;

	private final DownloadUrlSigner urlSigner;

	private final TenantBuckets buckets;

	private final TetraProperties properties;

	public EventQueryService(TenantRouter router, EventReader reader, DownloadUrlSigner urlSigner,
			TenantBuckets buckets, TetraProperties properties) {
		this.router = router;
		this.reader = reader;
		this.urlSigner = urlSigner;
		this.buckets = buckets;
		this.properties = properties;
	}

	public record CsvDownloadResponse(String url, OffsetDateTime expiresAt) {
	}

	public EventDetailResponse detail(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		return router.inTenant(routed, () -> reader.detail(eventId, routed.tenant()));
	}

	/** OPS-03-2 · 04-2 신청 내용. 상태를 바꾸지 않고 S3를 부르지 않는다. */
	public ApplicationSummaryResponse applicationSummary(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		return router.inTenant(routed, () -> reader.applicationSummary(eventId));
	}

	public EventResultResponse result(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		return router.inTenant(routed, () -> reader.result(eventId));
	}

	/**
	 * history_file_path의 presigned GET URL을 매번 새로 만든다. READY가 아니면 409.
	 * history_file_path는 테넌트 로그 버킷(tetra-audit-logs-{env}-{tenant_id}) 안의 키만 담는다(3-12, issuance-history/{event_id}.csv).
	 */
	public CsvDownloadResponse historyCsv(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		String key = router.inTenant(routed, () -> reader.historyFilePathIfReady(eventId));
		if (key == null || key.isBlank()) {
			throw new ApiException(ErrorCode.RESULT_NOT_READY);
		}
		if (key.startsWith("s3://") || key.startsWith("/")) {
			throw new IllegalStateException("history_file_path 형식 오류(키만 저장): eventId=" + eventId);
		}
		var signed = urlSigner.presignGet(buckets.auditLog(routed.tenantId()), key, properties.csv().presignTtl(),
				"issuance-history-" + eventId + ".csv");
		return new CsvDownloadResponse(signed.url(), signed.expiresAt().atZone(Kst.ZONE).toOffsetDateTime());
	}

}

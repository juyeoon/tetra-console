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

	public EventResultResponse result(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		return router.inTenant(routed, () -> reader.result(eventId));
	}

	/** history_file_path(테넌트 로그 버킷)의 presigned GET URL을 매번 새로 만든다. READY가 아니면 409. */
	public CsvDownloadResponse historyCsv(Long eventId) {
		RoutedEvent routed = router.findEvent(eventId);
		String path = router.inTenant(routed, () -> reader.historyFilePathIfReady(eventId));
		if (path == null || path.isBlank()) {
			throw new ApiException(ErrorCode.RESULT_NOT_READY);
		}
		S3Location location = S3Location.parse(path, buckets.auditLog(routed.tenantId()));
		var signed = urlSigner.presignGet(location.bucket(), location.key(), properties.csv().presignTtl(),
				"issuance-history-" + eventId + ".csv");
		return new CsvDownloadResponse(signed.url(), signed.expiresAt().atZone(Kst.ZONE).toOffsetDateTime());
	}

	/**
	 * TODO(미정): history_file_path 형식. 발급 이력 저장 API 담당과 맞춤.
	 * 임시로 "s3://bucket/key"면 그대로, 아니면 테넌트 로그 버킷 안의 key로 본다.
	 */
	record S3Location(String bucket, String key) {

		static S3Location parse(String path, String defaultBucket) {
			if (path.startsWith("s3://")) {
				String rest = path.substring("s3://".length());
				int slash = rest.indexOf('/');
				if (slash <= 0 || slash == rest.length() - 1) {
					throw new IllegalStateException("history_file_path 형식 오류");
				}
				return new S3Location(rest.substring(0, slash), rest.substring(slash + 1));
			}
			return new S3Location(defaultBucket, path.startsWith("/") ? path.substring(1) : path);
		}

	}

}

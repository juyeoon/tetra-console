package com.tetra.ops.report;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.external.ObjectStorage;
import com.tetra.ops.tenancy.TenantRouter;
import com.tetra.ops.tenant.TenantBuckets;

/**
 * 리포트 저장(11-02 결정, 스키마 변경 없이 멱등). 부르는 쪽이 데이터로 파일(테넌트 zip · 운영자 CSV)을 메모리에 만든 뒤:
 * <ol>
 * <li>결정적 키({@link ReportKeys})로 S3에 먼저 Put — 같은 키면 덮어쓴다(다시 만들어도 파일 하나)</li>
 * <li>한 트랜잭션: 같은 키(file_path)의 메타 행이 있는지 보고, 없을 때만 메타 INSERT + REPORT 알림 INSERT({@link ReportMetaWriter})</li>
 * </ol>
 * S3가 먼저라 메타가 있으면 파일은 있다. 실패하면 예외를 그대로 던진다 — 다시 돌리면 같은 키에 덮어쓰고 메타 · 알림은 한 번이다.
 * 확인 뒤 넣기라 같은 리포트를 동시에 저장하면 둘 다 넣을 수 있다: 리포트 작업은 이름별 DbLock 안에서 한 스레드로 부른다
 * (운영자 알림은 dedupe_key가 2중 안전장치). 테넌트 스키마로 들어가야 해서 트랜잭션 밖에서 부른다.
 * 보관 2년: S3 객체는 버킷 수명 주기(reports/ 731일), 메타 행은 {@link ReportMetaCleanupJob}.
 */
@Service
public class ReportStore {

	private static final Logger log = LoggerFactory.getLogger(ReportStore.class);

	static final String ZIP = "application/zip";

	static final String CSV = "text/csv; charset=utf-8";

	/** 키는 이 접두어 아래만 — 버킷 수명 주기(731일)가 이 접두어에 걸려 있다 */
	static final String KEY_PREFIX = "reports/";

	/** tenant_report_meta · operator_report_meta.name VARCHAR(100) */
	static final int NAME_MAX = 100;

	/**
	 * 테넌트 리포트(이벤트 결과 · 월간). 테넌트 로그 버킷에 zip. eventId = 이벤트 결과면 그 이벤트(테넌트 알림에 붙음), 월간이면 null.
	 * name은 100자를 넘으면 자른다.
	 */
	public record TenantReport(ReportCategory category, Long eventId, String name, LocalDateTime periodStart,
			LocalDateTime periodEnd, String key, byte[] zip) {

		public TenantReport {
			require(category != null && category.tenant(), "테넌트 리포트 분류가 아닙니다: " + category);
			require(category != ReportCategory.EVENT_RESULT || eventId != null, "이벤트 결과 리포트에 eventId가 없습니다");
			require(category != ReportCategory.MONTHLY || eventId == null, "월간 리포트에는 eventId를 넣지 않습니다");
			validate(name, periodStart, periodEnd, key, zip);
		}

	}

	/** 운영자 리포트(운영자 감사). 운영자 리포트 버킷에 CSV. name은 100자를 넘으면 자른다 */
	public record OperatorReport(ReportCategory category, String name, LocalDateTime periodStart,
			LocalDateTime periodEnd, String key, byte[] csv) {

		public OperatorReport {
			require(category != null && !category.tenant(), "운영자 리포트 분류가 아닙니다: " + category);
			validate(name, periodStart, periodEnd, key, csv);
		}

	}

	private final ObjectStorage storage;

	private final TenantBuckets buckets;

	private final TenantRouter router;

	private final ReportMetaWriter writer;

	private final String operatorBucket;

	private final Clock clock;

	public ReportStore(ObjectStorage storage, TenantBuckets buckets, TenantRouter router, ReportMetaWriter writer,
			TetraProperties properties, Clock clock) {
		this.storage = storage;
		this.buckets = buckets;
		this.router = router;
		this.writer = writer;
		this.operatorBucket = properties.report().operatorBucket();
		this.clock = clock;
	}

	/**
	 * 테넌트 로그 버킷(tetra-audit-logs-{env}-{tenant_id})에 Put → 그 테넌트 스키마에서 tenant_report_meta + tenant_notification.
	 * READY가 아닌 테넌트는 S3에 올리기 전에 거절한다(스키마로 라우팅할 수 없음).
	 * @return 메타 · 알림을 새로 남겼으면 true, 같은 리포트가 이미 있었으면 false(파일만 덮어씀)
	 */
	public boolean saveTenantReport(Tenant tenant, TenantReport report) {
		if (!tenant.isReady()) {
			throw new IllegalStateException("READY가 아닌 테넌트에는 리포트를 저장하지 않습니다: tenantId=" + tenant.getTenantId());
		}
		storage.put(buckets.auditLog(tenant.getTenantId()), report.key(), report.zip(), ZIP);
		boolean created = router.inTenant(tenant, () -> writer.tenantMeta(report, fitName(report.name()), Kst.now(clock)));
		log.info("리포트 저장: tenantId={}, category={}, key={}, bytes={}, created={}", tenant.getTenantId(),
				report.category(), report.key(), report.zip().length, created);
		return created;
	}

	/**
	 * 운영자 리포트 버킷(tetra.report.operator-bucket)에 Put → tetra_platform.operator_report_meta + operator_notification.
	 * @return 메타 · 알림을 새로 남겼으면 true, 같은 리포트가 이미 있었으면 false(파일만 덮어씀)
	 */
	public boolean saveOperatorReport(OperatorReport report) {
		storage.put(operatorBucket, report.key(), report.csv(), CSV);
		boolean created = writer.operatorMeta(report, fitName(report.name()), Kst.now(clock));
		log.info("리포트 저장: category={}, key={}, bytes={}, created={}", report.category(), report.key(),
				report.csv().length, created);
		return created;
	}

	/** VARCHAR(100)은 글자(코드 포인트) 수 — 100자를 넘으면 서로게이트 쌍을 가르지 않고 자른다 */
	static String fitName(String name) {
		String stripped = name.strip();
		if (stripped.codePointCount(0, stripped.length()) <= NAME_MAX) {
			return stripped;
		}
		return stripped.substring(0, stripped.offsetByCodePoints(0, NAME_MAX));
	}

	private static void validate(String name, LocalDateTime periodStart, LocalDateTime periodEnd, String key,
			byte[] content) {
		require(name != null && !name.isBlank(), "리포트 이름이 없습니다");
		require(periodStart != null && periodEnd != null && !periodEnd.isBefore(periodStart),
				"리포트 기간이 잘못되었습니다(끝 ≥ 시작): " + periodStart + " ~ " + periodEnd);
		require(key != null && key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length() && key.length() <= 1024,
				"리포트 키는 " + KEY_PREFIX + " 아래(1024자 이내)여야 합니다: " + key);
		require(content != null && content.length > 0, "리포트 파일이 비어 있습니다: " + key);
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}

}

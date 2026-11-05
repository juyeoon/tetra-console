package com.tetra.ops.report.audit;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.DbLock;
import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.Operator;
import com.tetra.ops.domain.platform.OperatorReportMetaRepository;
import com.tetra.ops.domain.platform.OperatorRepository;
import com.tetra.ops.report.ReportCategory;
import com.tetra.ops.report.ReportKeys;
import com.tetra.ops.report.ReportStore;
import com.tetra.ops.report.ReportStore.OperatorReport;

/**
 * 운영자 접속기록 리포트(기간 지정 CSV) 수동 생성(POST /api/ops/reports/generate, category OPERATOR_AUDIT). 월간 자동 예약은 없다.
 * <ol>
 * <li>감사 버킷 이름({@link OperatorAuditBuckets})이 없으면 409 AUDIT_BUCKET_NOT_CONFIGURED(기동 때 WARN 한 줄)</li>
 * <li>잠금 tetra:report-operator-audit을 기다리지 않고 잡는다 — 못 잡으면 409 REPORT_BUSY</li>
 * <li>감사 줄 읽기({@link OperatorAuditReader}) → CSV({@link OperatorAuditCsv}, 운영자 이름은 운영자 표) →
 * {@link ReportStore#saveOperatorReport}(S3 먼저, 같은 file_path가 없을 때만 메타 + REPORT 알림 — 같은 기간이면 파일만 덮어씀)</li>
 * </ol>
 * 키 reports/{yyyy}/{mm}/operator-audit-{from}-{to}.csv, 이름 '운영자 접속기록 {from} ~ {to}', 메타 기간 from 00:00 ~ to 23:59:59 KST.
 * 기간 검사(필수 · 순서 · 오늘까지 · 31일)는 부르는 쪽(ReportGenerateService). 트랜잭션 밖에서 부른다.
 */
@Component
public class OperatorAuditReportGenerator {

	public static final String LOCK_NAME = "tetra:report-operator-audit";

	private static final Logger log = LoggerFactory.getLogger(OperatorAuditReportGenerator.class);

	private static final LocalTime DAY_END = LocalTime.of(23, 59, 59);

	private final DbLock lock;

	private final OperatorAuditReader reader;

	private final ReportStore store;

	private final OperatorReportMetaRepository metas;

	private final OperatorRepository operators;

	private final Optional<String> auditBucket;

	@Autowired
	OperatorAuditReportGenerator(DataSource dataSource, OperatorAuditReader reader, ReportStore store,
			OperatorReportMetaRepository metas, OperatorRepository operators, TetraProperties properties) {
		this(new DbLock(dataSource, LOCK_NAME), reader, store, metas, operators,
				OperatorAuditBuckets.resolve(properties.report().auditBucket(), properties.report().operatorBucket()));
		if (auditBucket.isEmpty()) {
			log.warn("운영자 접속기록 리포트: 감사 버킷 이름을 정할 수 없습니다(REPORT_AUDIT_BUCKET이 비어 있고 운영자 리포트 버킷 이름에 {}가 없음) "
					+ "— 생성 요청은 409 {}", OperatorAuditBuckets.OPERATOR_PART, ErrorCode.AUDIT_BUCKET_NOT_CONFIGURED);
		}
	}

	/** 테스트용: 잠금 · 감사 버킷 이름을 직접 준다 */
	public OperatorAuditReportGenerator(DbLock lock, OperatorAuditReader reader, ReportStore store,
			OperatorReportMetaRepository metas, OperatorRepository operators, Optional<String> auditBucket) {
		this.lock = lock;
		this.reader = reader;
		this.store = store;
		this.metas = metas;
		this.operators = operators;
		this.auditBucket = auditBucket;
	}

	/**
	 * @param reportId operator_report_meta.report_id(새로 넣었거나 이미 있던 행)
	 * @param filePath 운영자 리포트 버킷 안의 키(버킷 이름 없음)
	 * @param created 메타 · REPORT 알림을 이번에 새로 남겼으면 true, 같은 기간이 이미 있어 파일만 덮어썼으면 false
	 * @param rows CSV 데이터 줄 수(머리글 제외)
	 */
	public record Generated(long reportId, String filePath, boolean created, int rows) {
	}

	/** @param from 시작일(KST, 포함) @param to 종료일(KST, 포함) */
	public Generated generate(LocalDate from, LocalDate to) {
		String bucket = auditBucket.orElseThrow(() -> new ApiException(ErrorCode.AUDIT_BUCKET_NOT_CONFIGURED));
		AtomicReference<Generated> result = new AtomicReference<>();
		if (!lock.runIfAcquired(() -> result.set(create(bucket, from, to)))) {
			throw new ApiException(ErrorCode.REPORT_BUSY);
		}
		return result.get();
	}

	/** '운영자 접속기록 2026-11-01 ~ 2026-11-05' */
	static String name(LocalDate from, LocalDate to) {
		return "운영자 접속기록 " + from + " ~ " + to;
	}

	private Generated create(String bucket, LocalDate from, LocalDate to) {
		OperatorAuditReader.Result read = reader.read(bucket, from, to);
		List<OperatorAuditRecord> records = read.records();
		log.info("운영자 접속기록 읽음: 기간 {} ~ {}, 파일 {}개, 줄 {}개", from, to, read.files(), records.size());
		if (read.skipped() > 0) {
			log.warn("운영자 접속기록: 읽지 못한 감사 줄 {}개를 뺐습니다(JSON · time 형식 오류, 기간 {} ~ {})", read.skipped(), from, to);
		}
		byte[] csv = OperatorAuditCsv.write(records, operatorNames(records));
		String key = ReportKeys.operatorAudit(from, to);
		boolean created = store.saveOperatorReport(new OperatorReport(ReportCategory.OPERATOR_AUDIT, name(from, to),
				from.atStartOfDay(), to.atTime(DAY_END), key, csv));
		long reportId = metas.findFirstByFilePathOrderByReportIdAsc(key)
			.orElseThrow(() -> new IllegalStateException("저장한 운영자 리포트 메타가 없습니다: " + key))
			.getReportId();
		return new Generated(reportId, key, created, records.size());
	}

	/** 줄에 나온 운영자 id → 운영자 표의 이름(contact_name). 이메일은 읽지 않는다. 숫자가 아닌 id · 없는 운영자는 빠진다 */
	private Map<String, String> operatorNames(List<OperatorAuditRecord> records) {
		List<Long> ids = records.stream()
			.map(OperatorAuditRecord::operatorId)
			.filter(Objects::nonNull)
			.distinct()
			.filter(id -> id.matches("\\d{1,10}"))
			.map(Long::valueOf)
			.toList();
		Map<String, String> names = new HashMap<>();
		if (!ids.isEmpty()) {
			for (Operator operator : operators.findAllById(ids)) {
				names.put(operator.getOperatorId().toString(), operator.getContactName());
			}
		}
		return names;
	}

}

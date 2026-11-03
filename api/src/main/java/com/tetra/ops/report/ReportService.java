package com.tetra.ops.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.Operator;
import com.tetra.ops.domain.platform.OperatorReportMeta;
import com.tetra.ops.domain.platform.OperatorReportMetaRepository;
import com.tetra.ops.domain.platform.OperatorRepository;
import com.tetra.ops.external.DownloadUrlSigner;
import com.tetra.ops.external.MailGateway;

/**
 * OPS-09 리포트: 목록 · 다운로드 링크 · 이메일 전송. 행이 없으면 빈 목록. 리포트를 만들어 넣는 쪽은 리포트 작업(ReportStore, 운영자 감사
 * 리포트 = CSV, file_path는 키만 — 예 reports/2026/10/operator-audit-202610.csv).
 * 파일은 운영자 리포트 버킷(tetra.report.operator-bucket, 기본 이름 규칙 tetra-operator-logs-{env})에 있다.
 * file_path는 키만 또는 s3://버킷/키 둘 다 받는다(예전 형식 reports/{report_id}.pdf 포함).
 */
@Service
public class ReportService {

	private static final Logger log = LoggerFactory.getLogger(ReportService.class);

	private final OperatorReportMetaRepository reports;

	private final OperatorRepository operators;

	private final DownloadUrlSigner urlSigner;

	private final MailGateway mailGateway;

	private final ReportMailComposer mailComposer;

	private final TetraProperties properties;

	public ReportService(OperatorReportMetaRepository reports, OperatorRepository operators,
			DownloadUrlSigner urlSigner, MailGateway mailGateway, ReportMailComposer mailComposer,
			TetraProperties properties) {
		this.reports = reports;
		this.operators = operators;
		this.urlSigner = urlSigner;
		this.mailGateway = mailGateway;
		this.mailComposer = mailComposer;
		this.properties = properties;
	}

	public record ReportListResponse(List<ReportItem> items) {
	}

	/** file_path는 내보내지 않는다. 기간 · 생성일은 KST(+09:00). */
	public record ReportItem(Long reportId, String name, String category, OffsetDateTime periodStart,
			OffsetDateTime periodEnd, OffsetDateTime createdAt) {
	}

	public record ReportDownloadResponse(String url, OffsetDateTime expiresAt) {
	}

	@Transactional(readOnly = true)
	public ReportListResponse list() {
		return new ReportListResponse(reports.findAllByOrderByCreatedAtDescReportIdDesc()
			.stream()
			.map(r -> new ReportItem(r.getReportId(), r.getName(), r.getCategory(), Kst.toOffset(r.getPeriodStart()),
					Kst.toOffset(r.getPeriodEnd()), Kst.toOffset(r.getCreatedAt())))
			.toList());
	}

	/** 요청할 때마다 presigned GET URL을 새로 만든다(만료 tetra.csv.presign-ttl, 기본 5분). 파일 이름 report-{id}.{확장자} */
	public ReportDownloadResponse download(Long reportId) {
		OperatorReportMeta report = find(reportId);
		String key = keyOf(report.getFilePath(), bucket(), reportId);
		var signed = urlSigner.presignGet(bucket(), key, properties.csv().presignTtl(),
				"report-" + reportId + extension(key));
		return new ReportDownloadResponse(signed.url(), signed.expiresAt().atZone(Kst.ZONE).toOffsetDateTime());
	}

	/** 로그인한 운영자 이메일로 리포트 안내 메일(링크만). 트랜잭션 밖에서 보낸다. 실패하면 503 MAIL_SEND_FAILED */
	public void email(Long reportId, Long operatorId) {
		OperatorReportMeta report = find(reportId);
		Operator operator = operators.findById(operatorId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		boolean sent;
		try {
			sent = mailGateway.send(mailComposer.compose(operator.getEmail(), operator.getContactName(), report));
		}
		catch (RuntimeException ex) {
			log.error("리포트 메일 발송 중 오류: reportId={}, error={}", reportId, ex.getClass().getSimpleName());
			sent = false;
		}
		if (!sent) {
			log.warn("리포트 메일 발송 실패: reportId={}, operatorId={}", reportId, operatorId);
			throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
		}
		log.info("리포트 메일 발송: reportId={}, operatorId={}", reportId, operatorId);
	}

	private OperatorReportMeta find(Long reportId) {
		return reports.findById(reportId).orElseThrow(() -> new ApiException(ErrorCode.REPORT_NOT_FOUND));
	}

	String bucket() {
		return properties.report().operatorBucket();
	}

	/** 키만(reports/1.pdf) 또는 s3://{운영자 로그 버킷}/{키}. 다른 버킷 · 빈 키는 데이터 오류(500). */
	static String keyOf(String filePath, String bucket, Long reportId) {
		String path = filePath == null ? "" : filePath.strip();
		if (path.startsWith("s3://")) {
			String rest = path.substring("s3://".length());
			int slash = rest.indexOf('/');
			if (slash < 0 || !rest.substring(0, slash).equals(bucket)) {
				throw new IllegalStateException("file_path가 운영자 로그 버킷이 아닙니다: reportId=" + reportId);
			}
			path = rest.substring(slash + 1);
		}
		if (path.isEmpty() || path.startsWith("/")) {
			throw new IllegalStateException("file_path 형식 오류: reportId=" + reportId);
		}
		return path;
	}

	/** 키의 확장자(영문 · 숫자 1~10자, 소문자로). 없거나 형식이 다르면 빈 문자열 — 다운로드 파일 이름은 ASCII만 */
	static String extension(String key) {
		String fileName = key.substring(key.lastIndexOf('/') + 1);
		int dot = fileName.lastIndexOf('.');
		if (dot < 0) {
			return "";
		}
		String ext = fileName.substring(dot + 1);
		return ext.matches("[A-Za-z0-9]{1,10}") ? "." + ext.toLowerCase(Locale.ROOT) : "";
	}

}

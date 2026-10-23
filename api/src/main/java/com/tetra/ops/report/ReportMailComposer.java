package com.tetra.ops.report;

import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.platform.OperatorReportMeta;
import com.tetra.ops.external.MailGateway.Mail;

/**
 * 리포트 이메일 전송(OPS-09). 받는 사람은 로그인한 운영자. 파일을 첨부하지 않고 presigned URL도 넣지 않는다(5분이면 만료) —
 * 콘솔 리포트 화면 링크만 둔다. 콘솔 주소(CONSOLE_BASE_URL)가 없으면 링크 대신 메뉴 경로.
 * TODO(미정): 메일 문구 확정(화면설계서에 없음).
 */
@Component
public class ReportMailComposer {

	static final String SUBJECT_PREFIX = "[Tetra] 운영 콘솔 리포트 — ";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

	private final String consoleBaseUrl;

	public ReportMailComposer(TetraProperties properties) {
		String base = properties.console().baseUrl();
		this.consoleBaseUrl = base == null ? "" : base.strip().replaceAll("/+$", "");
	}

	public Mail compose(String to, String operatorName, OperatorReportMeta report) {
		String name = singleLine(report.getName());
		String where = consoleBaseUrl.isEmpty() ? "운영 콘솔 > 로그 > 리포트 화면에서 내려받을 수 있습니다."
				: "운영 콘솔 리포트 화면에서 내려받을 수 있습니다.\n" + consoleBaseUrl + "/logs/reports";
		String text = """
				%s님, 안녕하세요.

				요청하신 운영 콘솔 리포트를 안내해 드립니다.

				- 리포트명: %s
				- 기간: %s ~ %s
				- 생성일: %s

				%s

				본 메일은 발신 전용입니다.
				""".formatted(singleLine(operatorName), name, DATE.format(report.getPeriodStart()),
				DATE.format(report.getPeriodEnd()), DATE.format(report.getCreatedAt()), where);
		return new Mail(to, SUBJECT_PREFIX + name, text, null);
	}

	/** 제어 문자를 공백으로 바꾸고 연속 공백을 하나로 — 제목 헤더가 깨지지 않게 */
	static String singleLine(String value) {
		return value == null ? "" : value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").strip();
	}

}

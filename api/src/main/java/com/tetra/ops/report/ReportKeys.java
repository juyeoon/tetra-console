package com.tetra.ops.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 리포트 파일의 S3 키(결정적 — 같은 리포트는 늘 같은 키, 다시 만들면 덮어쓴다). yyyy/mm은 대상 기간(이벤트 리포트는 부르는 쪽이 정한 달).
 * 테넌트 리포트는 테넌트 로그 버킷, 운영자 리포트는 운영자 리포트 버킷 안의 키다. 메타 file_path에도 이 키만 넣는다.
 * 보관은 버킷 수명 주기(reports/ 731일)가 지운다.
 */
public final class ReportKeys {

	private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

	private ReportKeys() {
	}

	/** reports/{yyyy}/{mm}/event-{event_id}.zip */
	public static String eventResult(YearMonth period, long eventId) {
		return prefix(period) + "event-" + eventId + ".zip";
	}

	/** reports/{yyyy}/{mm}/monthly-{yyyy}{mm}.zip */
	public static String monthly(YearMonth period) {
		return prefix(period) + "monthly-" + compact(period) + ".zip";
	}

	/**
	 * reports/{yyyy}/{mm}/operator-audit-{yyyyMMdd}-{yyyyMMdd}.csv — 운영자 접속기록(기간 지정, 양끝 포함 KST 날짜). yyyy/mm = from의 달.
	 * 같은 기간이면 같은 키(다시 만들면 덮어씀)
	 */
	public static String operatorAudit(LocalDate from, LocalDate to) {
		return prefix(YearMonth.from(from)) + "operator-audit-" + DAY.format(from) + "-" + DAY.format(to) + ".csv";
	}

	private static String prefix(YearMonth period) {
		return "reports/%04d/%02d/".formatted(period.getYear(), period.getMonthValue());
	}

	private static String compact(YearMonth period) {
		return "%04d%02d".formatted(period.getYear(), period.getMonthValue());
	}

}

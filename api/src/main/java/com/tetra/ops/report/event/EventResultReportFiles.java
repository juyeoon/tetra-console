package com.tetra.ops.report.event;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tetra.ops.common.CsvWriter;
import com.tetra.ops.domain.StatusType;
import com.tetra.ops.report.ReportZip;
import com.tetra.ops.report.event.EventResultReportData.Flow;
import com.tetra.ops.report.event.EventResultReportData.Metrics;
import com.tetra.ops.report.event.EventResultReportData.Minute;
import com.tetra.ops.report.event.EventResultReportData.StatusEntry;
import com.tetra.ops.report.event.EventResultReportData.Timing;
import com.tetra.ops.report.pdf.ReportPdf;
import com.tetra.ops.report.pdf.ReportPdf.Bar;
import com.tetra.ops.report.pdf.ReportPdf.Column;
import com.tetra.ops.report.pdf.ReportPdf.KeyValue;
import com.tetra.ops.report.pdf.ReportPdfRenderer;

/**
 * 이벤트 결과 리포트 zip(11-02 · 11-03 결정): summary.pdf(A4, KST, 한국어) + event.csv · minutes.csv · history.csv(UTF-8 BOM · CRLF ·
 * 수식 가림). 값은 {@link EventResultReportData} 그대로 — 여기서는 표기만 정한다(천 단위 쉼표, 비율 % 소수 1자리, 시각 KST).
 * 상태 이름은 Console 화면(web utils/status.ts · pages/events/eventView.ts STATUS_LOG_TEXT)과 같은 한글.
 */
@Component
class EventResultReportFiles {

	static final String PDF = "summary.pdf";

	static final String EVENT_CSV = "event.csv";

	static final String MINUTES_CSV = "minutes.csv";

	static final String HISTORY_CSV = "history.csv";

	static final String[] EVENT_HEADER = { "event_id", "event_name", "tenant_name", "traffic_plan", "max_capacity",
			"start_at", "end_at", "aggregated_at", "success_count", "total_stock", "sellout_rate", "soldout_count",
			"peak_concurrent", "capacity_usage_rate", "peak_at", "session_count", "ticket_count", "abandoned_count",
			"session_ratio", "ticket_ratio", "success_ratio", "soldout_ratio", "abandoned_ratio",
			"sellout_duration_seconds", "wait_avg_seconds", "wait_max_seconds" };

	static final String[] MINUTES_HEADER = { "minute", "success", "soldout" };

	static final String[] HISTORY_HEADER = { "changed_at", "status_type", "prev_status", "new_status", "reject_reason" };

	static final String HISTORY_CSV_NOTE = "건별 자료는 발급 이력 CSV(이벤트 결과 화면에서 내려받기)를 이용해 주세요.";

	private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

	private static final Map<StatusType, String> TYPE_LABEL = Map.of(StatusType.APPLICATION, "신청", StatusType.HOSTING,
			"호스팅", StatusType.EVENT, "이벤트");

	/** 상태 배지 이름(web utils/status.ts) */
	private static final Map<StatusType, Map<String, String>> STATUS_LABEL = Map.of(
			StatusType.APPLICATION,
			Map.of("APPLYING", "신청됨(처리 중)", "APPLIED", "승인 대기 중", "APPLY_FAILED", "신청 실패", "APPROVED", "승인됨",
					"REJECTED", "반려됨"),
			StatusType.HOSTING,
			Map.of("PENDING", "배포 대기", "DEPLOYED", "배포 완료", "DEPLOY_FAILED", "배포 실패", "HOSTING", "호스팅 중",
					"HOSTING_FAILED", "호스팅 준비 실패", "ENDED", "호스팅 종료", "END_FAILED", "종료 실패"),
			StatusType.EVENT,
			Map.of("SCHEDULED", "오픈 예정", "OPEN", "진행 중", "OPEN_FAILED", "이벤트 준비 실패", "EXPIRED", "만료"));

	/** 단계 이름(web pages/events/eventView.ts STATUS_LOG_TEXT의 title) — 변경된 상태로 고른다 */
	private static final Map<StatusType, Map<String, String>> STEP_LABEL = Map.of(
			StatusType.APPLICATION,
			Map.of("APPLYING", "신청 접수", "APPLIED", "신청 저장 완료", "APPLY_FAILED", "신청 실패", "APPROVED", "운영자 승인",
					"REJECTED", "운영자 반려"),
			StatusType.HOSTING,
			Map.of("PENDING", "배포 시작", "DEPLOYED", "배포 완료", "DEPLOY_FAILED", "배포 실패", "HOSTING", "호스팅 시작",
					"HOSTING_FAILED", "호스팅 준비 실패", "ENDED", "호스팅 종료", "END_FAILED", "종료 실패"),
			StatusType.EVENT,
			Map.of("SCHEDULED", "오픈 예약", "OPEN", "입장 시작", "OPEN_FAILED", "이벤트 준비 실패", "EXPIRED", "이벤트 만료"));

	private final ReportPdfRenderer renderer;

	EventResultReportFiles(ReportPdfRenderer renderer) {
		this.renderer = renderer;
	}

	/** zip. generatedAt = PDF 바닥의 생성 시각(KST). CSV는 같은 값이면 같은 바이트, PDF는 문서 ID가 매번 달라 바이트가 다르다 */
	byte[] zip(EventResultReportData data, LocalDateTime generatedAt) {
		return ReportZip.zip(List.of(new ReportZip.Entry(PDF, renderer.render(pdf(data), generatedAt)),
				new ReportZip.Entry(EVENT_CSV, eventCsv(data)), new ReportZip.Entry(MINUTES_CSV, minutesCsv(data)),
				new ReportZip.Entry(HISTORY_CSV, historyCsv(data))));
	}

	// ---------------------------------------------------------------- PDF

	static ReportPdf pdf(EventResultReportData d) {
		Metrics m = d.metrics();
		Flow f = d.flow();
		Timing t = d.timing();
		String event = "[" + d.eventId() + "] " + d.eventName();
		ReportPdf.Builder pdf = ReportPdf.builder("이벤트 결과 리포트 " + event)
			.title("이벤트 결과 리포트")
			.keyValues(List.of(new KeyValue("테넌트", d.tenantName()), new KeyValue("이벤트", event),
					new KeyValue("플랜", d.maxCapacity() == null ? d.planCode()
							: d.planCode() + " (수용 인원 " + number(d.maxCapacity()) + "명)"),
					new KeyValue("이벤트 오픈", time(d.startAt())), new KeyValue("이벤트 종료", time(d.endAt())),
					new KeyValue("집계 기준", time(d.aggregatedAt()))))
			.heading("핵심 지표")
			.keyValues(List.of(
					new KeyValue("발급 성공 / 총 재고",
							number(m.successCount()) + " / " + number(m.totalStock()) + " (소진율 " + percent(m.selloutRate())
									+ ")"),
					new KeyValue("품절로 못 받은 수", number(m.soldoutCount()) + "명"),
					new KeyValue("최대 동시 접속 / 플랜 수용",
							number(m.peakConcurrent()) + " / " + (d.maxCapacity() == null ? "-" : number(d.maxCapacity()))
									+ " (사용률 " + percent(m.capacityUsageRate()) + ")"),
					new KeyValue("최대 동시 접속 시각", time(m.peakAt()))))
			.heading("참여 흐름")
			.bars(List.of(bar("입장(세션)", f.sessionCount(), f), bar("번호표", f.ticketCount(), f),
					bar("발급 성공", f.successCount(), f), bar("품절", f.soldoutCount(), f),
					bar("이탈", f.abandonedCount(), f)))
			.paragraph("비율은 발급 성공 + 품절 + 이탈 대비입니다(이벤트 결과 화면의 발급 결과 구성과 같음). 이탈은 이벤트 페이지에 들어왔다가 "
					+ "발급 · 품절 결과 없이 나간 사람으로, 번호표를 받기 전에 나간 사람도 포함합니다.")
			.heading("시간")
			.keyValues(List.of(
					new KeyValue("소진까지 걸린 시간",
							t.selloutSeconds() == null ? "- (재고를 다 쓰지 않음)" : duration(t.selloutSeconds())),
					new KeyValue("대기 시간 평균", duration(t.waitAverageSeconds())),
					new KeyValue("대기 시간 최대", duration(t.waitMaxSeconds()))))
			.paragraph("소진까지 걸린 시간은 이벤트 오픈(시작 시각)부터 마지막 발급 성공까지입니다(재고를 다 쓴 경우만). 대기 시간은 번호표를 받아 "
					+ "대기열에 들어간 시각부터 발급 결과(성공 · 품절)가 정해진 시각까지이며, 결과가 있는 " + number(t.decidedCount())
					+ "명 기준입니다. 이벤트 시작 전에 번호표를 받은 사람은 시작까지 기다린 시간도 포함됩니다.")
			.heading("분당 발급 결과");
		if (d.minutes().isEmpty()) {
			pdf.paragraph("발급 기록이 없습니다.");
		}
		else {
			pdf.paragraph("대기열 진입 시각(분) 기준입니다. 이탈한 사람은 포함하지 않습니다.")
				.table(List.of(Column.left("대기열 진입(KST)", 2.2f), Column.right("발급 성공", 1), Column.right("품절", 1)),
						d.minutes()
							.stream()
							.map(x -> List.of(MINUTE.format(x.minute()), number(x.successCount()), number(x.soldoutCount())))
							.toList());
		}
		pdf.heading("운영 이력");
		if (d.history().isEmpty()) {
			pdf.paragraph("운영 이력이 없습니다.");
		}
		else {
			// 반려 사유 열은 반려 행이 있을 때만(결과가 있는 이벤트는 보통 반려가 없다). CSV는 늘 둔다
			boolean rejected = d.history().stream().anyMatch(h -> h.rejectReason() != null);
			List<Column> columns = new ArrayList<>(List.of(Column.left("일시(KST)", 2.3f), Column.left("구분", 0.9f),
					Column.left("단계", 1.9f), Column.left("상태", 3)));
			if (rejected) {
				columns.add(Column.left("반려 사유", 2.5f));
			}
			pdf.table(columns, d.history().stream().map(h -> historyRow(h, rejected)).toList());
		}
		return pdf.heading("참고").paragraph(HISTORY_CSV_NOTE).build();
	}

	/** 일시 · 구분 · 단계 · 상태(이전 → 변경) [· 반려 사유(반려 행만, 그 밖은 빈 칸)] */
	private static List<String> historyRow(StatusEntry h, boolean withRejectReason) {
		String next = statusLabel(h.statusType(), h.newStatus());
		String status = h.prevStatus() == null ? next : statusLabel(h.statusType(), h.prevStatus()) + " → " + next;
		List<String> row = new ArrayList<>(Arrays.asList(SECOND.format(h.changedAt()),
				TYPE_LABEL.getOrDefault(h.statusType(), h.statusType().name()),
				STEP_LABEL.getOrDefault(h.statusType(), Map.of()).getOrDefault(h.newStatus(), next), status));
		if (withRejectReason) {
			row.add(h.rejectReason());
		}
		return row;
	}

	private static Bar bar(String label, long count, Flow flow) {
		BigDecimal ratio = flow.ratio(count);
		return new Bar(label, number(count) + "명 · " + percent(ratio), ratio.doubleValue());
	}

	static String statusLabel(StatusType type, String status) {
		return STATUS_LABEL.getOrDefault(type, Map.of()).getOrDefault(status, status);
	}

	// ---------------------------------------------------------------- CSV

	static byte[] eventCsv(EventResultReportData d) {
		Metrics m = d.metrics();
		Flow f = d.flow();
		Timing t = d.timing();
		// 값이 없는 칸(null)은 빈 칸: 플랜 수용 · 사용률 · 최대 시각 · 시간
		return CsvWriter.withFormulaGuard(EVENT_HEADER)
			.row(d.eventId(), d.eventName(), d.tenantName(), d.planCode(), d.maxCapacity(), d.startAt(), d.endAt(),
					d.aggregatedAt(), m.successCount(), m.totalStock(), m.selloutRate(), m.soldoutCount(),
					m.peakConcurrent(), m.capacityUsageRate(), m.peakAt(), f.sessionCount(), f.ticketCount(),
					f.abandonedCount(), f.ratio(f.sessionCount()), f.ratio(f.ticketCount()), f.ratio(f.successCount()),
					f.ratio(f.soldoutCount()), f.ratio(f.abandonedCount()), t.selloutSeconds(), t.waitAverageSeconds(),
					t.waitMaxSeconds())
			.toBytes();
	}

	static byte[] minutesCsv(EventResultReportData d) {
		CsvWriter csv = CsvWriter.withFormulaGuard(MINUTES_HEADER);
		for (Minute x : d.minutes()) {
			csv.row(x.minute(), x.successCount(), x.soldoutCount());
		}
		return csv.toBytes();
	}

	/** status_type · 상태는 DB 값(영문) 그대로, 반려 사유는 반려 행만 */
	static byte[] historyCsv(EventResultReportData d) {
		CsvWriter csv = CsvWriter.withFormulaGuard(HISTORY_HEADER);
		for (StatusEntry h : d.history()) {
			csv.row(h.changedAt(), h.statusType().name(), h.prevStatus(), h.newStatus(), h.rejectReason());
		}
		return csv.toBytes();
	}

	// ---------------------------------------------------------------- 표기

	static String number(long value) {
		return NumberFormat.getIntegerInstance(Locale.KOREA).format(value);
	}

	/** 0~1 비율 → 37.5%(소수 1자리 반올림). null이면 - */
	static String percent(BigDecimal rate) {
		return rate == null ? "-" : rate.movePointRight(2).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
	}

	static String time(LocalDateTime value) {
		return value == null ? "-" : MINUTE.format(value) + " (KST)";
	}

	/** 초 → 12.3초 · 4분 5초 · 1시간 2분 3초. null이면 - */
	static String duration(BigDecimal seconds) {
		if (seconds == null) {
			return "-";
		}
		BigDecimal tenths = seconds.setScale(1, RoundingMode.HALF_UP);
		if (tenths.compareTo(SIXTY) < 0) {
			return tenths.toPlainString() + "초";
		}
		long total = seconds.setScale(0, RoundingMode.HALF_UP).longValue();
		long hours = total / 3600;
		long minutes = total % 3600 / 60;
		long secs = total % 60;
		return hours > 0 ? hours + "시간 " + minutes + "분 " + secs + "초" : minutes + "분 " + secs + "초";
	}

}

package com.tetra.ops.report.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tetra.ops.domain.StatusType;
import com.tetra.ops.report.event.EventResultReportData.Flow;
import com.tetra.ops.report.event.EventResultReportData.Metrics;
import com.tetra.ops.report.event.EventResultReportData.Minute;
import com.tetra.ops.report.event.EventResultReportData.StatusEntry;
import com.tetra.ops.report.event.EventResultReportData.Timing;
import com.tetra.ops.report.pdf.ReportPdfRenderer;

/**
 * 이벤트 결과 리포트 파일(값 → summary.pdf · event.csv · minutes.csv · history.csv · zip). DB 없이 고정 값으로 표기 · 수식 가림 ·
 * PDF 글자를 확인한다. 재고 0(DB CHECK stock_count > 0이라 통합 테스트로는 못 만듦)도 여기서. 견본 zip을 build/report-samples/에 남긴다.
 */
class EventResultReportFilesTest {

	private static final Logger log = LoggerFactory.getLogger(EventResultReportFilesTest.class);

	static final LocalDateTime START = LocalDateTime.of(2026, 10, 31, 23, 30);

	static final LocalDateTime GENERATED = LocalDateTime.of(2026, 11, 1, 4, 35, 10);

	static final EventResultReportFiles files = new EventResultReportFiles(new ReportPdfRenderer());

	/** 9,000 / 10,000 판매(재고 남음), 입장 10,500 · 번호표 9,800, 분당 n개, 운영 이력 정상 흐름 */
	static EventResultReportData sample(String tenantName, String eventName, int minuteCount) {
		List<Minute> minutes = new ArrayList<>();
		for (int i = 0; i < minuteCount; i++) {
			minutes.add(new Minute(START.minusMinutes(5).plusMinutes(i), Math.max(0, 300 - i * 3L), i * 2L));
		}
		return new EventResultReportData(tenantName, 1043, eventName, "M", 30000L, START, START.plusHours(3),
				LocalDateTime.of(2026, 11, 1, 4, 31, 5),
				new Metrics(9000, 10000, new BigDecimal("0.9000"), 120, 12600, new BigDecimal("0.4200"),
						START.plusMinutes(1)),
				new Flow(10500, 9800, 9000, 120, 1380),
				new Timing(null, 9120, new BigDecimal("75.250"), new BigDecimal("3723.400")), minutes, history(null));
	}

	static List<StatusEntry> history(String rejectReason) {
		LocalDateTime t = LocalDateTime.of(2026, 10, 20, 9, 0);
		List<StatusEntry> rows = new ArrayList<>(List.of(
				new StatusEntry(t, StatusType.APPLICATION, null, "APPLYING", null),
				new StatusEntry(t.plusSeconds(3), StatusType.APPLICATION, "APPLYING", "APPLIED", null)));
		if (rejectReason != null) {
			rows.add(new StatusEntry(t.plusDays(1), StatusType.APPLICATION, "APPLIED", "REJECTED", rejectReason));
			return rows;
		}
		rows.addAll(List.of(new StatusEntry(t.plusDays(1), StatusType.APPLICATION, "APPLIED", "APPROVED", null),
				new StatusEntry(t.plusDays(1), StatusType.HOSTING, null, "PENDING", null),
				new StatusEntry(t.plusDays(1), StatusType.EVENT, null, "SCHEDULED", null),
				new StatusEntry(t.plusDays(1).plusMinutes(4), StatusType.HOSTING, "PENDING", "DEPLOYED", null),
				new StatusEntry(START.minusHours(2), StatusType.HOSTING, "DEPLOYED", "HOSTING", null),
				new StatusEntry(START.minusHours(1), StatusType.EVENT, "SCHEDULED", "OPEN", null),
				new StatusEntry(START.plusHours(5), StatusType.HOSTING, "HOSTING", "ENDED", null),
				new StatusEntry(START.plusHours(5), StatusType.EVENT, "OPEN", "EXPIRED", null)));
		return rows;
	}

	static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		return entries;
	}

	static String csv(byte[] bytes) {
		assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
	}

	/** 추출 글자(공백 · 줄바꿈 하나로) */
	static String pdfText(byte[] pdf) throws IOException {
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
		}
	}

	static int pages(byte[] pdf) throws IOException {
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			return doc.getNumberOfPages();
		}
	}

	static byte[] pdf(EventResultReportData data) {
		return new ReportPdfRenderer().render(EventResultReportFiles.pdf(data), GENERATED);
	}

	@Test
	void zipHasPdfAndThreeCsvsInOrder() throws IOException {
		byte[] zip = files.zip(sample("가을상회", "2026 가을 한정 쿠폰", 3), GENERATED);

		assertThat(new ArrayList<>(unzip(zip).keySet())).containsExactly("summary.pdf", "event.csv", "minutes.csv",
				"history.csv");
		assertThat(new String(unzip(zip).get("summary.pdf"), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
	}

	@Test
	void eventCsvIsOneRowOfNumbersAndKstTimes() {
		String csv = csv(EventResultReportFiles.eventCsv(sample("가을상회", "2026 가을 한정 쿠폰", 3)));

		assertThat(csv).isEqualTo(String.join(",", EventResultReportFiles.EVENT_HEADER) + "\r\n"
				+ "1043,2026 가을 한정 쿠폰,가을상회,M,30000,2026-10-31T23:30:00.000+09:00,2026-11-01T02:30:00.000+09:00,"
				+ "2026-11-01T04:31:05.000+09:00,9000,10000,0.9000,120,12600,0.4200,2026-10-31T23:31:00.000+09:00,"
				+ "10500,9800,1380,1.0000,0.9333,0.8571,0.0114,0.1314,,75.250,3723.400\r\n");
	}

	@Test
	void minutesAndHistoryCsv() {
		EventResultReportData data = sample("가을상회", "가을", 2);

		assertThat(csv(EventResultReportFiles.minutesCsv(data))).isEqualTo("minute,success,soldout\r\n"
				+ "2026-10-31T23:25:00.000+09:00,300,0\r\n" + "2026-10-31T23:26:00.000+09:00,297,2\r\n");
		assertThat(csv(EventResultReportFiles.historyCsv(data)))
			.startsWith("changed_at,status_type,prev_status,new_status,reject_reason\r\n"
					+ "2026-10-20T09:00:00.000+09:00,APPLICATION,,APPLYING,\r\n"
					+ "2026-10-20T09:00:03.000+09:00,APPLICATION,APPLYING,APPLIED,\r\n")
			.endsWith("2026-11-01T04:30:00.000+09:00,EVENT,OPEN,EXPIRED,\r\n");
	}

	/** 11-03: 이벤트명 · 테넌트명 · 반려 사유가 = + - @ 탭 CR로 시작하면 ' — 숫자 · 시각 칸은 그대로 */
	@Test
	void csvCellsAreGuardedAgainstFormulas() {
		EventResultReportData data = sample("@SUM(A1) 상회", "=1+1", 1);
		EventResultReportData rejected = new EventResultReportData(data.tenantName(), data.eventId(), "-50% 할인",
				data.planCode(), data.maxCapacity(), data.startAt(), data.endAt(), data.aggregatedAt(), data.metrics(),
				data.flow(), data.timing(), List.of(), history("+cmd|' /C calc'!A0"));

		assertThat(csv(EventResultReportFiles.eventCsv(data)).split("\r\n")[1]).startsWith("1043,'=1+1,'@SUM(A1) 상회,M,30000,");
		assertThat(csv(EventResultReportFiles.eventCsv(rejected)).split("\r\n")[1]).startsWith("1043,'-50% 할인,");
		assertThat(csv(EventResultReportFiles.historyCsv(rejected)))
			.endsWith("2026-10-21T09:00:00.000+09:00,APPLICATION,APPLIED,REJECTED,'+cmd|' /C calc'!A0\r\n");
	}

	@Test
	void pdfShowsHeaderKeyMetricsFlowTimeAndHistory() throws IOException {
		String text = pdfText(pdf(sample("가을상회", "2026 가을 한정 쿠폰", 3)));

		assertThat(text).contains("이벤트 결과 리포트", "가을상회", "[1043] 2026 가을 한정 쿠폰", "M (수용 인원 30,000명)",
				"2026-10-31 23:30 (KST)", "2026-11-01 02:30 (KST)", "2026-11-01 04:31 (KST)");
		assertThat(text).contains("9,000 / 10,000 (소진율 90.0%)", "120명", "12,600 / 30,000 (사용률 42.0%)",
				"2026-10-31 23:31 (KST)");
		assertThat(text).contains("입장(세션)", "10,500명 · 100.0%", "번호표", "9,800명 · 93.3%", "9,000명 · 85.7%",
				"120명 · 1.1%", "이탈", "1,380명 · 13.1%");
		assertThat(text).contains("- (재고를 다 쓰지 않음)", "1분 15초", "1시간 2분 3초", "9,120명 기준");
		assertThat(text).contains("2026-10-31 23:25", "2026-10-31 23:27", "운영자 승인", "승인 대기 중 → 승인됨", "배포 대기 → 배포 완료",
				"호스팅 중 → 호스팅 종료", "진행 중 → 만료", EventResultReportFiles.HISTORY_CSV_NOTE);
		// 운영 이력 detail 원문 · 내부 지표는 없다. 반려 행이 없으면 반려 사유 열도 없다
		assertThat(text).doesNotContain("오류율", "응답 시간", "Pod", "반려 사유");
	}

	@Test
	void rejectedRowShowsReasonOnly() throws IOException {
		EventResultReportData data = sample("가을상회", "가을", 1);
		EventResultReportData rejected = new EventResultReportData(data.tenantName(), data.eventId(), data.eventName(),
				data.planCode(), data.maxCapacity(), data.startAt(), data.endAt(), data.aggregatedAt(), data.metrics(),
				data.flow(), data.timing(), data.minutes(), history("배너 이미지 규격이 맞지 않습니다"));

		String text = pdfText(pdf(rejected));

		assertThat(text).contains("반려 사유", "운영자 반려", "승인 대기 중 → 반려됨", "배너 이미지 규격이 맞지 않습니다");
	}

	/** 재고를 다 쓴 경우만 소진까지 걸린 시간. 재고 0이면 다 쓴 것으로 보지 않는다(소진율 0, 비율 분모 0이면 0%) */
	@Test
	void selloutOnlyWhenStockIsUsedUpAndZeroStockIsNotSoldOut() throws IOException {
		assertThat(new Metrics(10, 10, BigDecimal.ONE, 0, 0, null, null).soldOut()).isTrue();
		assertThat(new Metrics(11, 10, BigDecimal.ONE, 0, 0, null, null).soldOut()).isTrue();
		assertThat(new Metrics(9999, 10000, new BigDecimal("0.9999"), 0, 0, null, null).soldOut()).isFalse();
		assertThat(new Metrics(0, 0, new BigDecimal("0.0000"), 0, 0, null, null).soldOut()).isFalse();
		assertThat(new Flow(0, 0, 0, 0, 0).ratio(0)).isEqualByComparingTo("0");

		EventResultReportData zero = new EventResultReportData("가을상회", 1043, "재고 0", "S", 10000L, START,
				START.plusHours(1), START.plusHours(3), new Metrics(0, 0, new BigDecimal("0.0000"), 0, 0,
						new BigDecimal("0.0000"), null),
				new Flow(0, 0, 0, 0, 0), new Timing(null, 0, null, null), List.of(), List.of());
		String text = pdfText(pdf(zero));
		assertThat(text).contains("0 / 0 (소진율 0.0%)", "0명 · 0.0%", "- (재고를 다 쓰지 않음)", "발급 기록이 없습니다.",
				"운영 이력이 없습니다.");
		assertThat(csv(EventResultReportFiles.eventCsv(zero)).split("\r\n")[1])
			.endsWith(",0,0,0,0.0000,0.0000,0.0000,0.0000,0.0000,,,");

		EventResultReportData data = sample("가을상회", "가을", 1);
		EventResultReportData soldOut = new EventResultReportData(data.tenantName(), data.eventId(), data.eventName(),
				data.planCode(), data.maxCapacity(), data.startAt(), data.endAt(), data.aggregatedAt(),
				new Metrics(10000, 10000, BigDecimal.ONE.setScale(4), 120, 12600, new BigDecimal("0.4200"), null),
				data.flow(), new Timing(new BigDecimal("723.500"), 10120, new BigDecimal("12.340"), new BigDecimal("59.960")),
				data.minutes(), data.history());
		String soldOutText = pdfText(pdf(soldOut));
		assertThat(soldOutText).contains("12분 4초", "12.3초", "1분 0초", "10,000 / 10,000 (소진율 100.0%)");
	}

	@Test
	void formats() {
		assertThat(EventResultReportFiles.duration(null)).isEqualTo("-");
		assertThat(EventResultReportFiles.duration(new BigDecimal("0.000"))).isEqualTo("0.0초");
		assertThat(EventResultReportFiles.duration(new BigDecimal("59.940"))).isEqualTo("59.9초");
		assertThat(EventResultReportFiles.duration(new BigDecimal("59.950"))).isEqualTo("1분 0초");
		assertThat(EventResultReportFiles.duration(new BigDecimal("3600.000"))).isEqualTo("1시간 0분 0초");
		assertThat(EventResultReportFiles.percent(null)).isEqualTo("-");
		assertThat(EventResultReportFiles.percent(new BigDecimal("0.3750"))).isEqualTo("37.5%");
		assertThat(EventResultReportFiles.percent(new BigDecimal("0.99995"))).isEqualTo("100.0%");
		assertThat(EventResultReportFiles.number(1234567)).isEqualTo("1,234,567");
		assertThat(EventResultReportFiles.statusLabel(StatusType.HOSTING, "UNKNOWN")).isEqualTo("UNKNOWN");
	}

	/** 분당 표가 길면 다음 쪽으로 넘어간다. 견본 zip(build/report-samples)을 남기고 쪽 수 · 크기를 로그로 */
	@Test
	void longMinuteTableSpillsOverPagesAndWritesSample() throws IOException {
		byte[] zip = files.zip(sample("가을상회", "2026 가을 한정 슈퍼 세일 선착순 쿠폰 이벤트", 120), GENERATED);
		Map<String, byte[]> entries = unzip(zip);
		byte[] pdf = entries.get("summary.pdf");

		assertThat(pages(pdf)).isGreaterThanOrEqualTo(3);
		assertThat(pdfText(pdf)).contains("2026-10-31 23:25", "2026-11-01 01:24");
		Path dir = Path.of("build", "report-samples");
		Files.createDirectories(dir);
		Files.write(dir.resolve("event-result-sample.zip"), zip);
		log.info("이벤트 결과 리포트 견본: zip {}바이트, summary.pdf {}쪽 · {}바이트, event.csv {} · minutes.csv {} · history.csv {}바이트",
				zip.length, pages(pdf), pdf.length, entries.get("event.csv").length, entries.get("minutes.csv").length,
				entries.get("history.csv").length);
	}

}

package com.tetra.ops.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.NoOpLog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tetra.ops.report.pdf.ReportPdf.Bar;
import com.tetra.ops.report.pdf.ReportPdf.Column;
import com.tetra.ops.report.pdf.ReportPdf.KeyValue;

/**
 * 한글 PDF(spike 결과 그대로 — PDFBox 직접 그리기 + 앱 리소스 Noto Sans KR, GSUB 끔). 고정 데이터(긴 한글 문단 · 40자 넘는 이벤트명 표 ·
 * 천 단위 숫자 · KST 날짜 · Bold 제목 · 막대)로 만들어 ① 글자 추출 = 원문(공백 차이만 정규화) ② 글꼴 임베딩 · 서브셋 접두사
 * ③ 줄 끝이 어절 경계 ④ 글꼴에 없는 글자 → '?' ⑤ 같은 글꼴 객체로 두 번째 문서를 확인한다. 첫 생성 · 웜 생성 시간을 로그로 남긴다.
 */
class ReportPdfRendererTest {

	private static final Logger log = LoggerFactory.getLogger(ReportPdfRendererTest.class);

	static final LocalDateTime GENERATED = LocalDateTime.of(2026, 11, 3, 4, 30, 15);

	static final String TITLE = "2026년 10월 이벤트 결과 리포트";

	static final String LONG_PARAGRAPH = "이 리포트는 2026년 10월 한 달 동안 진행된 선착순 쿠폰 이벤트의 결과를 요약한 것입니다. "
			+ "발급 성공과 품절, 이탈 건수는 한국 표준시(KST) 기준으로 집계했으며 이벤트 페이지에 들어왔다가 번호표를 받기 전에 나간 사람도 "
			+ "이탈에 포함됩니다. 트래픽이 몰리는 시작 직후 1분 동안의 처리량이 전체 결과를 크게 좌우하므로 다음 이벤트에서는 재고와 구매 플랜을 "
			+ "함께 검토해 주세요. 표의 시각은 모두 2026-10-31 23:59 (KST)처럼 한국 표준시로 적었습니다.";

	static final List<Column> COLUMNS = List.of(Column.left("이벤트명", 4), Column.right("발급 성공", 1.3f),
			Column.right("재고", 1.3f), Column.right("소진율", 1), Column.left("종료 시각(KST)", 2));

	static final String HEADER_LINE = "이벤트명 발급 성공 재고 소진율 종료 시각(KST)";

	static final int ROWS = 45;

	static final ReportPdfRenderer renderer = new ReportPdfRenderer();

	static byte[] first;

	@BeforeAll
	static void firstRender() {
		long started = System.nanoTime();
		first = renderer.render(sample(), GENERATED);
		log.info("리포트 PDF 첫 생성(클래스 로딩 · 글꼴 파싱 포함): {}ms, {}바이트", (System.nanoTime() - started) / 1_000_000,
				first.length);
	}

	static String eventName(int i) {
		return "2026 가을 한정 슈퍼 세일 선착순 쿠폰 이벤트 (서울 · 부산 · 대구 · 광주 지점 동시 진행) " + i + "회차";
	}

	static String number(long value) {
		return NumberFormat.getIntegerInstance(Locale.KOREA).format(value);
	}

	static List<List<String>> rows() {
		List<List<String>> rows = new ArrayList<>();
		for (int i = 1; i <= ROWS; i++) {
			long success = 12_000L + i * 7;
			rows.add(List.of(eventName(i), number(success), number(20_000), "61." + (i % 10) + "%",
					"2026-10-" + String.format("%02d", 1 + i % 28) + " 23:59 (KST)"));
		}
		return rows;
	}

	static List<Bar> bars() {
		return List.of(new Bar("쿠폰 A 10% 할인", number(8_230) + " (82.3%)", 0.823),
				new Bar("쿠폰 B 무료 배송 (회원 전용, 1인 1매 한정으로 이름이 길어 두 줄이 되는 쿠폰)", number(1_204_500) + " (100%)", 1.4),
				new Bar("쿠폰 C 첫 구매", "0 (0%)", Double.NaN));
	}

	static ReportPdf sample() {
		return ReportPdf.builder("이벤트 결과 리포트")
			.title(TITLE)
			.keyValues(List.of(new KeyValue("대상 기간", "2026-10-01 00:00 ~ 2026-10-31 23:59 (KST)"),
					new KeyValue("이벤트 수", "3건"), new KeyValue("발급 성공 합계", number(37_035) + "건")))
			.heading("요약")
			.paragraph(LONG_PARAGRAPH)
			.heading("이벤트별 결과")
			.table(COLUMNS, rows())
			.heading("쿠폰별 소진율")
			.bars(bars())
			.paragraph("끝.")
			.build();
	}

	/** 원문 순서대로 나와야 하는 글자들 */
	static List<String> expectedInOrder() {
		List<String> expected = new ArrayList<>(List.of(TITLE, "대상 기간 2026-10-01 00:00 ~ 2026-10-31 23:59 (KST)",
				"이벤트 수 3건", "발급 성공 합계 37,035건", "요약", LONG_PARAGRAPH, "이벤트별 결과", HEADER_LINE));
		for (List<String> row : rows()) {
			expected.add(String.join(" ", row));
		}
		expected.add("쿠폰별 소진율");
		for (Bar bar : bars()) {
			expected.add(bar.label() + " " + bar.value());
		}
		expected.add("끝.");
		return expected;
	}

	// ---------------------------------------------------------------- 검사 도구

	static String extract(byte[] pdf) throws IOException {
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			return new PDFTextStripper().getText(doc);
		}
	}

	static String norm(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	/** 각 원문이 공백 정규화한 추출 글자에 이 순서대로(이어서) 나오는지 */
	static void assertInOrder(String extracted, List<String> expected) {
		String text = norm(extracted);
		int from = 0;
		for (String e : expected) {
			int at = text.indexOf(norm(e), from);
			assertThat(at).as("원문이 순서대로 추출되지 않음: [%s]", e).isGreaterThanOrEqualTo(0);
			from = at + norm(e).length();
		}
	}

	static int count(String text, String part) {
		int count = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
			count++;
		}
		return count;
	}

	/** 쪽마다 글꼴 이름 → 임베딩 여부 */
	static Map<String, Boolean> fonts(byte[] pdf) throws IOException {
		Map<String, Boolean> fonts = new LinkedHashMap<>();
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			for (PDPage page : doc.getPages()) {
				PDResources resources = page.getResources();
				for (COSName name : resources.getFontNames()) {
					PDFont font = resources.getFont(name);
					fonts.merge(font.getName(), font.isEmbedded(), Boolean::logicalAnd);
				}
			}
		}
		return fonts;
	}

	/**
	 * 원문이 추출 글자의 이어진 줄들로 나뉘어 나오고, 줄이 끝나는 곳이 모두 원문의 공백(어절 경계)인지. 줄 수를 돌려준다.
	 * (어절 중간에서 끊겼다면 줄 끝 다음 원문 글자가 공백이 아니다). 첫 줄이 같은 행이 여럿일 수 있어 시작 후보를 모두 본다.
	 */
	static int assertBreaksAtSpaces(String extracted, String original) {
		List<String> lines = extracted.lines().map(String::strip).toList();
		String problem = "첫 줄을 찾지 못함";
		for (int start = 0; start < lines.size(); start++) {
			if (lines.get(start).length() < 5 || !original.startsWith(lines.get(start))) {
				continue;
			}
			int pos = 0;
			int count = 0;
			String found = null;
			for (int i = start; pos < original.length() && found == null; i++) {
				String line = i < lines.size() ? lines.get(i) : "";
				if (line.isEmpty() || !original.startsWith(line, pos)) {
					found = "줄 " + i + " [" + line + "]이 원문 " + pos + "번째 글자부터와 다름";
					continue;
				}
				pos += line.length();
				count++;
				if (pos < original.length()) {
					if (original.charAt(pos) != ' ') {
						found = "줄 [" + line + "] 끝이 어절 중간";
					}
					pos++;
				}
			}
			if (found == null) {
				return count;
			}
			problem = found;
		}
		throw new AssertionError(problem + ": " + original);
	}

	static void assertValid(byte[] pdf) throws IOException {
		String text = extract(pdf);
		assertInOrder(text, expectedInOrder());
		assertThat(fonts(pdf)).hasSize(2).allSatisfy((name, embedded) -> {
			assertThat(name).matches(Pattern.compile("[A-Z]{6}\\+NotoSansKR-(Regular|Bold)"));
			assertThat(embedded).as(name).isTrue();
		});
	}

	// ---------------------------------------------------------------- 시험

	/** ① 추출 = 원문(순서 그대로, 공백만 정규화). 표는 쪽을 넘기면 머리글을 다시 그리고, 쪽마다 바닥에 생성 시각 · 쪽 번호 */
	@Test
	void extractedTextMatchesSourceAcrossPages() throws IOException {
		String text = extract(first);
		assertInOrder(text, expectedInOrder());

		int pages;
		try (PDDocument doc = Loader.loadPDF(first)) {
			pages = doc.getNumberOfPages();
			for (PDPage page : doc.getPages()) {
				// A4 세로
				assertThat(page.getMediaBox().getWidth()).isCloseTo(595.28f, Offset.offset(0.1f));
				assertThat(page.getMediaBox().getHeight()).isCloseTo(841.89f, Offset.offset(0.1f));
			}
			assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("이벤트 결과 리포트");
		}
		assertThat(pages).isGreaterThanOrEqualTo(3);
		String normalized = norm(text);
		// 표가 걸친 쪽마다 머리글(첫 쪽 + 넘어간 쪽)
		assertThat(count(normalized, HEADER_LINE)).isGreaterThanOrEqualTo(2);
		for (int page = 1; page <= pages; page++) {
			assertThat(normalized).contains("생성 시각 2026-11-03 04:30 (KST) " + page + " / " + pages);
		}
	}

	/** ② 모든 글꼴이 임베딩되고 서브셋 접두사(XXXXXX+)가 붙는다 — Regular · Bold 두 벌만 */
	@Test
	void fontsAreEmbeddedSubsets() throws IOException {
		Map<String, Boolean> fonts = fonts(first);

		assertThat(fonts).hasSize(2);
		assertThat(fonts.keySet()).anyMatch(name -> name.endsWith("+NotoSansKR-Regular"))
			.anyMatch(name -> name.endsWith("+NotoSansKR-Bold"));
		fonts.forEach((name, embedded) -> {
			assertThat(name).matches("[A-Z]{6}\\+NotoSansKR-(Regular|Bold)");
			assertThat(embedded).as(name).isTrue();
		});
	}

	/** ③ 줄 끝은 어절 경계 — 긴 문단과 표의 긴 이벤트명(칸 안 줄바꿈) */
	@Test
	void linesBreakOnlyAtWordBoundaries() throws IOException {
		String text = extract(first);

		assertThat(assertBreaksAtSpaces(text, LONG_PARAGRAPH)).isGreaterThanOrEqualTo(3);
		assertThat(eventName(1).length()).isGreaterThan(40);
		assertThat(assertBreaksAtSpaces(text, eventName(1))).isGreaterThanOrEqualTo(2);
		assertThat(assertBreaksAtSpaces(text, eventName(ROWS))).isGreaterThanOrEqualTo(2);
	}

	/** ④ 글꼴에 없는 글자(이모지 · 확장 한자)와 제어 문자가 있어도 만들어지고 '?' · 공백으로 나온다 — 제목(Bold) · 문단 · 표 · 막대 */
	@Test
	void missingGlyphsBecomeQuestionMarks() throws IOException {
		PdfFonts fonts = PdfFonts.shared();
		for (int cp : new int[] { 0x1F600, 0x20000 }) {
			assertThat(fonts.regular().has(cp)).as("U+%X가 글꼴에 없어야 시험이 된다", cp).isFalse();
			assertThat(fonts.bold().has(cp)).as("U+%X", cp).isFalse();
		}

		byte[] pdf = renderer.render(ReportPdf.builder("이모지 😀 문서")
			.title("이모지 제목 😀")
			.paragraph("이모지 😀 확장 한자 𠀀 탭\t제어\u0007끝")
			.table(List.of(Column.left("이름 👍", 2), Column.right("값", 1)), List.of(List.of("쿠폰 🎁 A", "1,000")))
			.bars(List.of(new Bar("막대 ✨😀", "😀 10%", 0.1)))
			.build(), GENERATED);

		String text = norm(extract(pdf));
		assertThat(text).contains("이모지 제목 ?")
			.contains("이모지 ? 확장 한자 ? 탭 제어 끝")
			.contains("이름 ? 값")
			.contains("쿠폰 ? A 1,000")
			.contains("? 10%");
		assertThat(fonts(pdf)).allSatisfy((name, embedded) -> assertThat(embedded).isTrue());
	}

	/** ⑤ 같은 글꼴 객체(한 번 파싱한 TrueTypeFont)로 두 번째 · 세 번째 문서도 같은 결과, 여러 스레드가 동시에 불러도(잠금) 같다 */
	@Test
	void reusesParsedFontsForLaterDocuments() throws Exception {
		PdfFonts fonts = PdfFonts.shared();
		Object regular = fonts.regular().ttf();

		byte[] second = renderer.render(sample(), GENERATED);
		byte[] third = new ReportPdfRenderer().render(sample(), GENERATED);

		assertThat(PdfFonts.shared()).isSameAs(fonts);
		assertThat(PdfFonts.shared().regular().ttf()).isSameAs(regular);
		assertValid(second);
		assertValid(third);
		// 첫 문서와 내용이 같다(문서 ID만 다름)
		assertThat(norm(extract(second))).isEqualTo(norm(extract(first)));

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<byte[]>> futures = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				futures.add(pool.submit(() -> renderer.render(sample(), GENERATED)));
			}
			for (Future<byte[]> future : futures) {
				assertValid(future.get());
			}
		}
		finally {
			pool.shutdown();
		}
	}

	/** 한 쪽보다 높은 행은 들어가는 줄까지 그리고 …를 붙인다(무한히 쪽을 넘기지 않음). 빈 표 · 빈 문서도 만들어진다 */
	@Test
	void oversizedRowIsCutAndEmptyContentStillRenders() throws IOException {
		String tall = String.join("\n", Collections.nCopies(200, "줄"));
		byte[] pdf = renderer.render(ReportPdf.builder("긴 행")
			.table(List.of(Column.left("내용", 1)), List.of(List.of(tall), List.of("다음 행")))
			.table(List.of(Column.left("빈 표", 1)), List.of())
			.build(), GENERATED);

		String text = norm(extract(pdf));
		assertThat(text).contains("줄 줄…").contains("다음 행").contains("빈 표");
		try (PDDocument doc = Loader.loadPDF(pdf)) {
			assertThat(doc.getNumberOfPages()).isBetween(2, 3);
		}

		byte[] empty = renderer.render(ReportPdf.builder("빈 문서").build(), GENERATED);
		assertThat(norm(extract(empty))).isEqualTo("생성 시각 2026-11-03 04:30 (KST) 1 / 1");
	}

	/** 웜 생성 시간(글꼴 파싱 뒤). 시간은 로그로만 남기고 판정하지 않는다(CI 기계마다 다름) */
	@Test
	void warmRenderTime() {
		renderer.render(sample(), GENERATED);
		long[] millis = new long[20];
		for (int i = 0; i < millis.length; i++) {
			long started = System.nanoTime();
			renderer.render(sample(), GENERATED);
			millis[i] = (System.nanoTime() - started) / 1_000_000;
		}
		Arrays.sort(millis);
		log.info("리포트 PDF 웜 생성 {}회({}쪽 표 {}행): 중앙값 {}ms, 최소 {}ms, 최대 {}ms", millis.length, "3+", ROWS,
				millis[millis.length / 2], millis[0], millis[millis.length - 1]);
		assertThat(millis[0]).isGreaterThanOrEqualTo(0);
	}

	/** PDFBox 로그(commons-logging, Boot BOM이 1.3.6으로 맞춤)가 앱 로깅으로 간다 */
	@Test
	void pdfBoxLoggingIsWired() {
		var pdfBoxLog = LogFactory.getLog(PDDocument.class);

		log.info("PDFBox 로그 구현: {}", pdfBoxLog.getClass().getName());
		assertThat(pdfBoxLog).isNotInstanceOf(NoOpLog.class);
		assertThat(pdfBoxLog.isWarnEnabled()).isTrue();
	}

}

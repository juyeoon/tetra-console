package com.tetra.ops.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 내려받는 CSV 공통 형식: BOM · CRLF · RFC 4180 따옴표 · KST +09:00. 발급 이력 바이트 동일은 IssuanceHistoryCsvBytesTest. */
class CsvWriterTest {

	private static String body(byte[] csv) {
		assertThat(Arrays.copyOf(csv, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		return new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
	}

	@Test
	void bomHeaderAndCrlfRows() {
		byte[] csv = new CsvWriter("event_id", "name").row(1043, "가을 한정").row(List.of(7, "b")).toBytes();

		assertThat(body(csv)).isEqualTo("event_id,name\r\n1043,가을 한정\r\n7,b\r\n");
	}

	@Test
	void headerOnly() {
		assertThat(body(new CsvWriter("a", "b").toBytes())).isEqualTo("a,b\r\n");
	}

	@Test
	void quotesCommaQuoteAndLineBreakCells() {
		String csv = body(new CsvWriter("v").row("a,b").row("a\"b").row("줄\n바꿈").row("cr\rx").row("crlf\r\ny")
			.row("\"\"").row(" 공백 ").row("plain").toBytes());

		assertThat(csv).isEqualTo("v\r\n" + "\"a,b\"\r\n" + "\"a\"\"b\"\r\n" + "\"줄\n바꿈\"\r\n" + "\"cr\rx\"\r\n"
				+ "\"crlf\r\ny\"\r\n" + "\"\"\"\"\"\"\r\n" + " 공백 \r\n" + "plain\r\n");
	}

	@Test
	void fieldQuotesOnlyWhenNeeded() {
		assertThat(CsvWriter.field("user-1")).isEqualTo("user-1");
		assertThat(CsvWriter.field("a,b")).isEqualTo("\"a,b\"");
		assertThat(CsvWriter.field("a\"b")).isEqualTo("\"a\"\"b\"");
		assertThat(CsvWriter.field("a\nb")).isEqualTo("\"a\nb\"");
		assertThat(CsvWriter.field("")).isEmpty();
	}

	/** 기본은 수식 가림 끔 — 발급 이력 CSV는 바이트 그대로(IssuanceHistoryCsvBytesTest) */
	@Test
	void formulaGuardIsOffByDefault() {
		assertThat(body(new CsvWriter("v").row("=1+1").row("@SUM(A1)").row("-5").toBytes()))
			.isEqualTo("v\r\n=1+1\r\n@SUM(A1)\r\n-5\r\n");
	}

	/** 리포트 CSV(11-03): = + - @ 탭 CR로 시작하는 글자 칸 앞에 '. CR은 그 뒤 따옴표 규칙으로 감싼다 */
	@Test
	void formulaGuardPrefixesQuoteOnTextCells() {
		String csv = body(CsvWriter.withFormulaGuard("v")
			.row("=1+1")
			.row("+82-10")
			.row("-50% 할인")
			.row("@SUM(A1)")
			.row("\tx")
			.row("\rx")
			.row("=a,b")
			.row("a=b")
			.row("'이미")
			.row("")
			.row((Object) null)
			.toBytes());

		assertThat(csv).isEqualTo("v\r\n" + "'=1+1\r\n" + "'+82-10\r\n" + "'-50% 할인\r\n" + "'@SUM(A1)\r\n" + "'\tx\r\n"
				+ "\"'\rx\"\r\n" + "\"'=a,b\"\r\n" + "a=b\r\n" + "'이미\r\n" + "\r\n" + "\r\n");
	}

	/** 숫자 · 시각 칸은 가리지 않는다(음수가 없고 시각은 숫자로 시작) */
	@Test
	void formulaGuardLeavesNumbersAndTimes() {
		String csv = body(CsvWriter.withFormulaGuard("a", "b", "c", "d")
			.row(-1L, new BigDecimal("-0.5"), LocalDateTime.of(2026, 11, 1, 10, 0), 1043)
			.toBytes());

		assertThat(csv).isEqualTo("a,b,c,d\r\n-1,-0.5,2026-11-01T10:00:00.000+09:00,1043\r\n");
	}

	@Test
	void nullIsEmptyTimeIsKstMillisAndDecimalIsPlain() {
		String csv = body(new CsvWriter("a", "b", "c", "d")
			.row(null, LocalDateTime.of(2026, 11, 1, 10, 0, 0, 123_999_999), new BigDecimal("1E+3"), "x")
			.row("", LocalDateTime.of(2026, 1, 1, 0, 0), new BigDecimal("0.3750"), null)
			.toBytes());

		// 밀리초 3자리(남는 자리는 버림), 오프셋은 늘 +09:00
		assertThat(csv).isEqualTo("a,b,c,d\r\n" + ",2026-11-01T10:00:00.123+09:00,1000,x\r\n"
				+ ",2026-01-01T00:00:00.000+09:00,0.3750,\r\n");
	}

}

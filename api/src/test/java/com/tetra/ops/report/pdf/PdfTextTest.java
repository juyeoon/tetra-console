package com.tetra.ops.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

/** 글자 정리 · 어절 줄바꿈(PDFBox 없이, 글자 하나 = 폭 1). */
class PdfTextTest {

	/** 글자(코드 포인트) 하나 = 폭 1, 공백도 1 */
	private static final ToDoubleFunction<String> ONE = s -> s.codePointCount(0, s.length());

	@Test
	void wrapsAtSpacesOnly() {
		assertThat(PdfText.wrap("가나 다라마 바", ONE, 5)).containsExactly("가나", "다라마 바");
		assertThat(PdfText.wrap("가나 다라", ONE, 5)).containsExactly("가나 다라");
		// 이어진 공백은 하나로, 앞뒤 공백은 뺀다
		assertThat(PdfText.wrap("  가나   다라  ", ONE, 10)).containsExactly("가나 다라");
	}

	@Test
	void splitsOnlyWordsLongerThanALine() {
		assertThat(PdfText.wrap("ab abcdefghij c", ONE, 4)).containsExactly("ab", "abcd", "efgh", "ij c");
		// 서로게이트 쌍(이모지 대신 '?'가 오지만 혹시 남아 있어도)은 가르지 않는다
		assertThat(PdfText.wrap("😀😀😀", ONE, 2)).containsExactly("😀😀", "😀");
	}

	@Test
	void emptyOrBlankIsOneEmptyLine() {
		assertThat(PdfText.wrap("", ONE, 5)).containsExactly("");
		assertThat(PdfText.wrap("   ", ONE, 5)).containsExactly("");
	}

	@Test
	void hardLinesSplitCrLfCrAndLf() {
		assertThat(PdfText.hardLines("a\r\nb\rc\nd")).containsExactly("a", "b", "c", "d");
		assertThat(PdfText.hardLines("a\n\nb")).containsExactly("a", "", "b");
		assertThat(PdfText.hardLines("")).containsExactly("");
	}

	@Test
	void printableReplacesControlWithSpaceAndMissingGlyphWithQuestionMark() {
		// 한글 · 영문 · 숫자만 있는 글꼴이라고 치면
		String printable = PdfText.printable("가\tA😀1\u0007𠀀", cp -> cp == '가' || cp == 'A' || cp == '1' || cp == ' ');

		assertThat(printable).isEqualTo("가 A?1 ?");
	}

}

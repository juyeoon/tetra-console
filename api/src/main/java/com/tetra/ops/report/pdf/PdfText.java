package com.tetra.ops.report.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;

/** 글자 정리(그릴 수 없는 글자 대체)와 어절 단위 줄바꿈. PDFBox 없이 폭 함수만 받아 따로 시험할 수 있다. */
final class PdfText {

	/** 글꼴에 없는 글자 대신 그리는 글자 */
	static final char MISSING = '?';

	private PdfText() {
	}

	/** \r\n · \r · \n으로 나눈 줄들(강제 줄바꿈). 빈 글자면 빈 줄 하나 */
	static List<String> hardLines(String text) {
		return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
	}

	/**
	 * 한 줄 글자를 그릴 수 있게 바꾼다: 제어 문자(탭 등) → 공백, 글꼴에 없는 글자(이모지 · 확장 한자 등) → '?'.
	 * PDFBox는 글꼴에 없는 글자를 만나면 예외를 던지므로 그리기 · 폭 재기 전에 꼭 거친다. 줄바꿈은 먼저 {@link #hardLines}로 나눈다.
	 */
	static String printable(String line, IntPredicate hasGlyph) {
		StringBuilder out = new StringBuilder(line.length());
		line.codePoints().forEach(cp -> {
			if (Character.isISOControl(cp)) {
				out.append(' ');
			}
			else if (hasGlyph.test(cp)) {
				out.appendCodePoint(cp);
			}
			else {
				out.append(MISSING);
			}
		});
		return out.toString();
	}

	/**
	 * 공백 단위(어절)로 maxWidth 안에 채운다. 이어진 공백은 하나로, 줄 앞뒤 공백은 뺀다. 한 어절이 한 줄보다 길 때만 글자 단위로 자른다
	 * (URL 같은 값). 빈 줄은 빈 문자열 한 줄. width는 글자의 폭(pt) — 글자 폭은 더하기로 맞으므로(PDFBox showText는 커닝 없음)
	 * 어절 폭을 한 번씩만 잰다.
	 */
	static List<String> wrap(String line, ToDoubleFunction<String> width, double maxWidth) {
		List<String> lines = new ArrayList<>();
		double space = width.applyAsDouble(" ");
		StringBuilder current = new StringBuilder();
		double currentWidth = 0;
		for (String word : line.split(" ")) {
			if (word.isEmpty()) {
				continue;
			}
			double wordWidth = width.applyAsDouble(word);
			if (!current.isEmpty() && currentWidth + space + wordWidth <= maxWidth) {
				current.append(' ').append(word);
				currentWidth += space + wordWidth;
				continue;
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
				current.setLength(0);
			}
			String rest = word;
			while (wordWidth > maxWidth && rest.codePointCount(0, rest.length()) > 1) {
				int cut = fit(rest, width, maxWidth);
				lines.add(rest.substring(0, cut));
				rest = rest.substring(cut);
				wordWidth = width.applyAsDouble(rest);
			}
			current.append(rest);
			currentWidth = wordWidth;
		}
		if (!current.isEmpty() || lines.isEmpty()) {
			lines.add(current.toString());
		}
		return lines;
	}

	/** maxWidth 안에 들어가는 가장 긴 앞부분의 끝 위치(글자 단위, 최소 한 글자) */
	private static int fit(String word, ToDoubleFunction<String> width, double maxWidth) {
		int end = word.offsetByCodePoints(0, 1);
		while (end < word.length()) {
			int next = word.offsetByCodePoints(end, 1);
			if (width.applyAsDouble(word.substring(0, next)) > maxWidth) {
				break;
			}
			end = next;
		}
		return end;
	}

}

package com.tetra.ops.report.pdf;

import java.util.ArrayList;
import java.util.List;

/**
 * 리포트 PDF 한 부의 내용(위에서 아래로 그릴 블록 목록). 그리기는 {@link ReportPdfRenderer}.
 * 글자는 그대로 넘긴다 — 줄바꿈(\n)은 강제 줄바꿈, 나머지 제어 문자는 공백, 글꼴에 없는 글자(이모지 등)는 '?'로 그린다.
 * 숫자 · 날짜 표기(천 단위 쉼표, KST)는 부르는 쪽이 정해서 문자열로 넘긴다.
 * <pre>
 * ReportPdf pdf = ReportPdf.builder("이벤트 결과 리포트")
 *     .title("가을 한정 쿠폰 이벤트 결과")
 *     .keyValues(List.of(new KeyValue("이벤트 기간", "2026-10-01 10:00 ~ 12:00 (KST)")))
 *     .heading("쿠폰별 발급")
 *     .table(List.of(Column.left("쿠폰", 3), Column.right("발급", 1)), List.of(List.of("10% 할인", "12,345")))
 *     .bars(List.of(new Bar("10% 할인", "82.3%", 0.823)))
 *     .paragraph("…")
 *     .build();
 * </pre>
 */
public final class ReportPdf {

	public enum Align {

		LEFT, RIGHT

	}

	/** 표 열. weight = 너비 비율(본문 너비를 나눠 가진다), 숫자 열은 RIGHT */
	public record Column(String header, float weight, Align align) {

		public Column {
			requireText(header, "열 이름");
			if (!(weight > 0)) {
				throw new IllegalArgumentException("열 너비 비율은 0보다 커야 합니다: " + header);
			}
			if (align == null) {
				throw new IllegalArgumentException("열 정렬이 없습니다: " + header);
			}
		}

		public static Column left(String header, float weight) {
			return new Column(header, weight, Align.LEFT);
		}

		public static Column right(String header, float weight) {
			return new Column(header, weight, Align.RIGHT);
		}

	}

	/** 요약 한 줄(라벨 · 값) */
	public record KeyValue(String key, String value) {

		public KeyValue {
			requireText(key, "라벨");
			value = value == null ? "" : value;
		}

	}

	/** 가로 막대 한 줄. value = 오른쪽에 쓸 값 글자, ratio = 막대 길이(0~1, 벗어나면 끝에 맞춤) */
	public record Bar(String label, String value, double ratio) {

		public Bar {
			requireText(label, "막대 이름");
			value = value == null ? "" : value;
		}

	}

	sealed interface Block permits Title, Heading, Paragraph, KeyValues, Table, Bars {

	}

	record Title(String text) implements Block {
	}

	record Heading(String text) implements Block {
	}

	record Paragraph(String text) implements Block {
	}

	record KeyValues(List<KeyValue> items) implements Block {
	}

	record Table(List<Column> columns, List<List<String>> rows) implements Block {
	}

	record Bars(List<Bar> items) implements Block {
	}

	private final String documentTitle;

	private final List<Block> blocks;

	private ReportPdf(String documentTitle, List<Block> blocks) {
		this.documentTitle = documentTitle;
		this.blocks = List.copyOf(blocks);
	}

	/** documentTitle = PDF 문서 정보의 제목(본문에는 그리지 않는다 — 본문 제목은 {@link Builder#title}) */
	public static Builder builder(String documentTitle) {
		return new Builder(documentTitle);
	}

	public String documentTitle() {
		return documentTitle;
	}

	List<Block> blocks() {
		return blocks;
	}

	private static void requireText(String value, String what) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(what + "이(가) 비어 있습니다");
		}
	}

	public static final class Builder {

		private final String documentTitle;

		private final List<Block> blocks = new ArrayList<>();

		private Builder(String documentTitle) {
			requireText(documentTitle, "문서 제목");
			this.documentTitle = documentTitle;
		}

		/** 큰 제목(Bold) */
		public Builder title(String text) {
			requireText(text, "제목");
			blocks.add(new Title(text));
			return this;
		}

		/** 소제목(Bold). 쪽 끝에 홀로 남지 않게 다음 내용과 함께 넘긴다 */
		public Builder heading(String text) {
			requireText(text, "소제목");
			blocks.add(new Heading(text));
			return this;
		}

		/** 문단. 어절(공백) 단위로 줄바꿈한다 */
		public Builder paragraph(String text) {
			blocks.add(new Paragraph(text == null ? "" : text));
			return this;
		}

		/** 요약 줄들(라벨 · 값) */
		public Builder keyValues(List<KeyValue> items) {
			blocks.add(new KeyValues(List.copyOf(items)));
			return this;
		}

		/** 표. 행마다 칸 수가 열 수와 같아야 한다(null 칸은 빈 칸). 쪽이 넘어가면 머리글을 다시 그린다 */
		public Builder table(List<Column> columns, List<? extends List<String>> rows) {
			if (columns.isEmpty()) {
				throw new IllegalArgumentException("표에 열이 없습니다");
			}
			List<List<String>> copied = new ArrayList<>(rows.size());
			for (List<String> row : rows) {
				if (row.size() != columns.size()) {
					throw new IllegalArgumentException("표 행의 칸 수(" + row.size() + ")가 열 수(" + columns.size() + ")와 다릅니다");
				}
				copied.add(row.stream().map(cell -> cell == null ? "" : cell).toList());
			}
			blocks.add(new Table(List.copyOf(columns), List.copyOf(copied)));
			return this;
		}

		/** 가로 막대 목록(이름 · 값 · 비율) */
		public Builder bars(List<Bar> items) {
			blocks.add(new Bars(List.copyOf(items)));
			return this;
		}

		public ReportPdf build() {
			return new ReportPdf(documentTitle, blocks);
		}

	}

}

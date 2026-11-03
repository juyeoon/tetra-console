package com.tetra.ops.report.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.report.pdf.ReportPdf.Align;
import com.tetra.ops.report.pdf.ReportPdf.Bar;
import com.tetra.ops.report.pdf.ReportPdf.Bars;
import com.tetra.ops.report.pdf.ReportPdf.Block;
import com.tetra.ops.report.pdf.ReportPdf.Column;
import com.tetra.ops.report.pdf.ReportPdf.Heading;
import com.tetra.ops.report.pdf.ReportPdf.KeyValue;
import com.tetra.ops.report.pdf.ReportPdf.KeyValues;
import com.tetra.ops.report.pdf.ReportPdf.Paragraph;
import com.tetra.ops.report.pdf.ReportPdf.Table;
import com.tetra.ops.report.pdf.ReportPdf.Title;

/**
 * 리포트 PDF 그리기(Apache PDFBox 3 직접 그리기, 한글 글꼴은 {@link PdfFonts}). A4 세로, 블록을 위에서 아래로 그리고 넘치면 다음 쪽.
 * 쪽마다 바닥에 생성 시각(KST)과 쪽 번호(n / 전체). 글꼴은 문서마다 서브셋으로 임베딩한다(XXXXXX+NotoSansKR-…).
 * <p>
 * PDFBox는 스레드 안전을 보장하지 않고 글꼴(TrueTypeFont)을 모든 문서가 같이 쓰므로, 이 앱 전체에서 한 번에 한 문서만 그린다(정적 잠금).
 * 한 부는 수십 ms(웜)라 리포트 작업(한 스레드)에는 충분하다. 첫 호출은 글꼴 파싱 때문에 1~2초 더 걸린다.
 * 결과는 byte[] — 저장(S3)은 부르는 쪽 몫이다.
 */
@Component
public class ReportPdfRenderer {

	private static final Object LOCK = new Object();

	static final PDRectangle PAGE = PDRectangle.A4;

	static final float MARGIN_X = 50;

	/** 본문이 시작하는 높이와 끝나는 높이(바닥 여백). 쪽 번호는 그 아래 */
	static final float TOP = PAGE.getHeight() - 56;

	static final float BOTTOM = 60;

	static final float FOOTER_BASELINE = 32;

	static final float WIDTH = PAGE.getWidth() - 2 * MARGIN_X;

	private static final DateTimeFormatter FOOTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/** 줄 상자 안에서 한글 글자가 가운데 오도록 기준선을 내리는 비율(글자 크기 대비) */
	private static final float BASELINE_SHIFT = 0.36f;

	private static final String ELLIPSIS = "…";

	private static final float BLACK = 0f;

	private static final float LABEL_GRAY = 0.38f;

	private static final float FOOTER_GRAY = 0.45f;

	private static final float RULE_GRAY = 0.75f;

	private static final float HEADER_FILL = 0.93f;

	private static final float TRACK_FILL = 0.9f;

	private static final float[] BAR_FILL = { 0.15f, 0.39f, 0.92f };

	/**
	 * @param generatedAt 바닥에 쓸 생성 시각(KST 벽시계). 문서 정보의 작성 시각에도 쓴다
	 */
	public byte[] render(ReportPdf report, LocalDateTime generatedAt) {
		synchronized (LOCK) {
			try {
				return draw(report, generatedAt);
			}
			catch (IOException ex) {
				throw new UncheckedIOException("리포트 PDF를 만들지 못했습니다", ex);
			}
		}
	}

	private static byte[] draw(ReportPdf report, LocalDateTime generatedAt) throws IOException {
		PdfFonts fonts = PdfFonts.shared();
		try (PDDocument doc = new PDDocument()) {
			Font regular = new Font(PDType0Font.load(doc, fonts.regular().ttf(), true), fonts.regular());
			Font bold = new Font(PDType0Font.load(doc, fonts.bold().ttf(), true), fonts.bold());
			Canvas canvas = new Canvas(doc, regular, bold);
			for (Block block : report.blocks()) {
				canvas.draw(block);
			}
			canvas.finish(generatedAt);
			PDDocumentInformation info = doc.getDocumentInformation();
			info.setTitle(report.documentTitle());
			info.setCreationDate(GregorianCalendar.from(generatedAt.atZone(Kst.ZONE)));
			doc.getDocumentCatalog().setLanguage("ko-KR");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	/** 문서에 등록한 글꼴 + 없는 글자 판정 */
	private record Font(PDType0Font pdf, PdfFonts.Face face) {

		String printable(String line) {
			return PdfText.printable(line, face::has);
		}

		double width(String printable, float size) {
			try {
				return pdf.getStringWidth(printable) / 1000.0 * size;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		/** 강제 줄바꿈 · 글자 정리 · 어절 줄바꿈을 거친 줄들 */
		List<String> lines(String text, float size, double maxWidth) {
			List<String> lines = new ArrayList<>();
			for (String hard : PdfText.hardLines(text)) {
				lines.addAll(PdfText.wrap(printable(hard), s -> width(s, size), maxWidth));
			}
			return lines;
		}

	}

	private record Style(Font font, float size, float leading, float gray) {
	}

	/** 쪽을 넘기며 위에서 아래로 그린다. y = 다음에 그릴 줄 상자의 위쪽 */
	private static final class Canvas {

		private final PDDocument doc;

		private final Style title;

		private final Style heading;

		private final Style body;

		private final Style label;

		private final Style cell;

		private final Style cellHeader;

		private final Style bar;

		private final Style footer;

		private final List<PDPage> pages = new ArrayList<>();

		private PDPageContentStream cs;

		private float y;

		/** 이 쪽에 아직 아무것도 그리지 않았으면 위 여백(블록 사이 간격)을 넣지 않는다 */
		private boolean atTop;

		/** 이 쪽의 맨 위(표면 다시 그린 머리글 바로 아래). 여기서도 안 들어가는 것은 쪽을 넘겨도 소용없어 넘기지 않는다 */
		private float freshY;

		Canvas(PDDocument doc, Font regular, Font bold) {
			this.doc = doc;
			this.title = new Style(bold, 18, 25, BLACK);
			this.heading = new Style(bold, 12.5f, 18, BLACK);
			this.body = new Style(regular, 10, 15, BLACK);
			this.label = new Style(regular, 10, 15, LABEL_GRAY);
			this.cell = new Style(regular, 9, 12.5f, BLACK);
			this.cellHeader = new Style(bold, 9, 12.5f, BLACK);
			this.bar = new Style(regular, 9.5f, 14, BLACK);
			this.footer = new Style(regular, 8, 10, FOOTER_GRAY);
		}

		void draw(Block block) throws IOException {
			switch (block) {
				case Title t -> lines(title, t.text(), 0, 10);
				case Heading h -> {
					gap(14);
					// 소제목이 쪽 끝에 홀로 남지 않게: 소제목 + 본문 두 줄이 들어갈 자리가 없으면 다음 쪽
					ensure(heading.leading() + 2 * body.leading());
					lines(heading, h.text(), 0, 6);
				}
				case Paragraph p -> lines(body, p.text(), 0, 8);
				case KeyValues kv -> keyValues(kv.items());
				case Table t -> table(t.columns(), t.rows());
				case Bars b -> bars(b.items());
			}
		}

		// ------------------------------------------------------------ 블록

		private void lines(Style style, String text, float before, float after) throws IOException {
			gap(before);
			for (String line : style.font().lines(text, style.size(), WIDTH)) {
				ensure(style.leading());
				text(style, MARGIN_X, baseline(y, style), line);
				advance(style.leading());
			}
			gap(after);
		}

		private void keyValues(List<KeyValue> items) throws IOException {
			if (items.isEmpty()) {
				return;
			}
			float gap = 14;
			float labelWidth = 0;
			for (KeyValue item : items) {
				for (String hard : PdfText.hardLines(item.key())) {
					labelWidth = (float) Math.max(labelWidth, label.font().width(label.font().printable(hard), label.size()));
				}
			}
			labelWidth = Math.min(labelWidth + 1, WIDTH * 0.35f);
			float[] xs = { MARGIN_X, MARGIN_X + labelWidth + gap };
			float[] widths = { labelWidth, WIDTH - labelWidth - gap };
			Align[] aligns = { Align.LEFT, Align.LEFT };
			Style[] styles = { label, body };
			for (KeyValue item : items) {
				List<List<String>> cells = List.of(label.font().lines(item.key(), label.size(), widths[0]),
						body.font().lines(item.value(), body.size(), widths[1]));
				row(cells, xs, widths, aligns, styles, body.leading(), 1.5f, null);
			}
			gap(8);
		}

		private void table(List<Column> columns, List<List<String>> rows) throws IOException {
			gap(4);
			float padX = 5;
			float padY = 4;
			int n = columns.size();
			float total = 0;
			for (Column column : columns) {
				total += column.weight();
			}
			float[] xs = new float[n];
			float[] widths = new float[n];
			float[] inner = new float[n];
			Align[] aligns = new Align[n];
			float x = MARGIN_X;
			for (int i = 0; i < n; i++) {
				widths[i] = WIDTH * columns.get(i).weight() / total;
				xs[i] = x + padX;
				inner[i] = widths[i] - 2 * padX;
				aligns[i] = columns.get(i).align();
				x += widths[i];
			}
			List<List<String>> header = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				header.add(cellHeader.font().lines(columns.get(i).header(), cellHeader.size(), inner[i]));
			}
			Style[] headerStyles = new Style[n];
			Style[] cellStyles = new Style[n];
			Arrays.fill(headerStyles, cellHeader);
			Arrays.fill(cellStyles, cell);
			float headerHeight = height(header, cell.leading(), padY);
			TableHeader repeat = () -> {
				float top = y;
				boolean pageTop = y >= freshY;
				drawRow(header, xs, inner, aligns, headerStyles, cell.leading(), padY, headerHeight, HEADER_FILL);
				rule(top);
				advance(headerHeight);
				rule(y);
				if (pageTop) {
					freshY = y;
				}
			};
			List<List<List<String>>> bodyRows = new ArrayList<>(rows.size());
			for (List<String> row : rows) {
				List<List<String>> cells = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					cells.add(cell.font().lines(row.get(i), cell.size(), inner[i]));
				}
				bodyRows.add(cells);
			}
			// 머리글이 쪽 끝에 홀로 남지 않게 첫 행과 함께
			ensure(headerHeight + (bodyRows.isEmpty() ? 0 : height(bodyRows.get(0), cell.leading(), padY)));
			repeat.draw();
			for (List<List<String>> cells : bodyRows) {
				row(cells, xs, inner, aligns, cellStyles, cell.leading(), padY, repeat);
			}
			gap(10);
		}

		private void bars(List<Bar> items) throws IOException {
			if (items.isEmpty()) {
				return;
			}
			gap(2);
			float labelWidth = WIDTH * 0.34f;
			float valueWidth = 0;
			for (Bar item : items) {
				valueWidth = (float) Math.max(valueWidth, bar.font().width(bar.font().printable(item.value()), bar.size()));
			}
			valueWidth = Math.min(valueWidth, WIDTH * 0.25f);
			float trackX = MARGIN_X + labelWidth + 10;
			float trackWidth = WIDTH - labelWidth - 10 - 10 - valueWidth;
			for (Bar item : items) {
				List<String> labels = bar.font().lines(item.label(), bar.size(), labelWidth);
				float height = labels.size() * bar.leading() + 3;
				ensure(height);
				float first = baseline(y, bar);
				for (int i = 0; i < labels.size(); i++) {
					text(bar, MARGIN_X, baseline(y - i * bar.leading(), bar), labels.get(i));
				}
				double ratio = Double.isNaN(item.ratio()) ? 0 : Math.clamp(item.ratio(), 0, 1);
				cs.setNonStrokingColor(TRACK_FILL);
				cs.addRect(trackX, first - 1, trackWidth, 8);
				cs.fill();
				if (ratio > 0) {
					cs.setNonStrokingColor(BAR_FILL[0], BAR_FILL[1], BAR_FILL[2]);
					cs.addRect(trackX, first - 1, (float) (trackWidth * ratio), 8);
					cs.fill();
				}
				String value = single(bar, item.value(), valueWidth);
				text(bar, MARGIN_X + WIDTH - (float) bar.font().width(value, bar.size()), first, value);
				advance(height);
			}
			gap(8);
		}

		// ------------------------------------------------------------ 행(표 · 요약 공통)

		@FunctionalInterface
		private interface TableHeader {

			void draw() throws IOException;

		}

		/**
		 * 한 행. 남은 자리에 안 들어가면 다음 쪽으로 넘기고(표면 머리글을 다시 그림) 그린다. 한 쪽보다 높은 행은 들어가는 줄까지만 그리고
		 * 마지막 줄 끝에 …(내용이 잘림)를 붙인다.
		 */
		private void row(List<List<String>> cells, float[] xs, float[] widths, Align[] aligns, Style[] styles, float leading,
				float padY, TableHeader header) throws IOException {
			float height = height(cells, leading, padY);
			if (cs == null || (y - height < BOTTOM && y < freshY)) {
				newPage();
				if (header != null) {
					header.draw();
				}
			}
			float room = y - BOTTOM;
			if (height > room) {
				int maxLines = Math.max(1, (int) ((room - 2 * padY) / leading));
				List<List<String>> cut = new ArrayList<>(cells.size());
				for (List<String> lines : cells) {
					if (lines.size() <= maxLines) {
						cut.add(lines);
						continue;
					}
					List<String> kept = new ArrayList<>(lines.subList(0, maxLines));
					kept.set(maxLines - 1, kept.get(maxLines - 1) + ELLIPSIS);
					cut.add(kept);
				}
				cells = cut;
				height = height(cells, leading, padY);
			}
			drawRow(cells, xs, widths, aligns, styles, leading, padY, height, -1);
			advance(height);
			if (header != null) {
				rule(y);
			}
		}

		/** 칸마다 모든 줄을 차례로 그린다(글자 추출이 칸 단위로 이어지게). fill ≥ 0이면 행 바탕을 그 회색으로 칠한다 */
		private void drawRow(List<List<String>> cells, float[] xs, float[] widths, Align[] aligns, Style[] styles,
				float leading, float padY, float height, float fill) throws IOException {
			if (fill >= 0) {
				cs.setNonStrokingColor(fill);
				cs.addRect(MARGIN_X, y - height, WIDTH, height);
				cs.fill();
			}
			for (int c = 0; c < cells.size(); c++) {
				Style style = styles[c];
				List<String> lines = cells.get(c);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					float x = aligns[c] == Align.RIGHT
							? xs[c] + widths[c] - (float) style.font().width(line, style.size()) : xs[c];
					text(style, x, baseline(y - padY - i * leading, leading, style.size()), line);
				}
			}
		}

		private static float height(List<List<String>> cells, float leading, float padY) {
			int lines = 1;
			for (List<String> cell : cells) {
				lines = Math.max(lines, cell.size());
			}
			return lines * leading + 2 * padY;
		}

		// ------------------------------------------------------------ 쪽 · 그리기 도구

		private void newPage() throws IOException {
			if (cs != null) {
				cs.close();
			}
			PDPage page = new PDPage(PAGE);
			doc.addPage(page);
			pages.add(page);
			cs = new PDPageContentStream(doc, page);
			y = TOP;
			freshY = TOP;
			atTop = true;
		}

		/** 높이 h가 남아 있지 않으면 다음 쪽(이미 쪽 맨 위면 그대로 — 넘겨도 들어가지 않는다) */
		private void ensure(float h) throws IOException {
			if (cs == null || (y - h < BOTTOM && y < freshY)) {
				newPage();
			}
		}

		private void gap(float h) {
			if (!atTop) {
				y -= h;
			}
		}

		private void advance(float h) {
			y -= h;
			atTop = false;
		}

		private static float baseline(float top, Style style) {
			return baseline(top, style.leading(), style.size());
		}

		private static float baseline(float top, float leading, float size) {
			return top - leading / 2 - BASELINE_SHIFT * size;
		}

		private void text(Style style, float x, float baseline, String printable) throws IOException {
			if (printable.isEmpty()) {
				return;
			}
			cs.setNonStrokingColor(style.gray());
			cs.beginText();
			cs.setFont(style.font().pdf(), style.size());
			cs.newLineAtOffset(x, baseline);
			cs.showText(printable);
			cs.endText();
		}

		private void rule(float at) throws IOException {
			cs.setStrokingColor(RULE_GRAY);
			cs.setLineWidth(0.5f);
			cs.moveTo(MARGIN_X, at);
			cs.lineTo(MARGIN_X + WIDTH, at);
			cs.stroke();
		}

		/** 한 줄로 쓸 값(막대 값). 넓으면 들어가는 데까지 + … */
		private static String single(Style style, String text, float maxWidth) {
			String line = style.font().printable(String.join(" ", PdfText.hardLines(text)));
			if (style.font().width(line, style.size()) <= maxWidth) {
				return line;
			}
			String cut = PdfText.wrap(line, s -> style.font().width(s, style.size()), maxWidth).get(0);
			return cut + ELLIPSIS;
		}

		/** 쪽이 없으면 빈 쪽 하나를 만들고, 쪽마다 바닥에 생성 시각 · 쪽 번호를 그린다 */
		void finish(LocalDateTime generatedAt) throws IOException {
			if (cs == null) {
				newPage();
			}
			cs.close();
			String left = "생성 시각 " + FOOTER_TIME.format(generatedAt) + " (KST)";
			for (int i = 0; i < pages.size(); i++) {
				try (PDPageContentStream footerStream = new PDPageContentStream(doc, pages.get(i),
						PDPageContentStream.AppendMode.APPEND, true, true)) {
					cs = footerStream;
					String right = (i + 1) + " / " + pages.size();
					text(footer, MARGIN_X, FOOTER_BASELINE, footer.font().printable(left));
					text(footer, MARGIN_X + WIDTH - (float) footer.font().width(right, footer.size()), FOOTER_BASELINE,
							right);
				}
			}
			cs = null;
		}

	}

}

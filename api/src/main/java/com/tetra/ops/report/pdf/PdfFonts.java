package com.tetra.ops.report.pdf;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 리포트 PDF 글꼴: 앱 리소스 Noto Sans KR 정적 TTF(Regular · Bold, resources/fonts/noto-sans-kr, SIL OFL 1.1).
 * 기반 이미지(eclipse-temurin:21-jre)에 한글 글꼴이 없어 OS 글꼴을 찾지 않는다. 처음 쓸 때 한 번 클래스패스에서 읽어(실행 jar 안이라 파일이
 * 아니라 InputStream) 파싱하고 앱이 끝날 때까지 재사용한다 — 문서마다 PDType0Font.load(doc, ttf, true)로 등록하면 문서를 닫아도 TTF는
 * 닫히지 않는다. 두 벌 파싱 뒤 힙 약 40~50MB(spike 실측). 읽기 실패는 다음 호출에서 다시 시도한다.
 * <p>
 * GSUB(글리프 치환)는 끈다: PDFBox가 이 글꼴을 라틴 문자로 판정해 한글 결과는 같고 그리기만 약 3배 느려진다(spike).
 * PDFBox는 스레드 안전을 보장하지 않으므로 이 객체는 {@link ReportPdfRenderer}의 잠금 안에서만 쓴다.
 * OTF(CFF 윤곽)는 임베딩되지 않아 glyf TTF만 쓴다.
 */
final class PdfFonts {

	private static final Logger log = LoggerFactory.getLogger(PdfFonts.class);

	static final String REGULAR = "fonts/noto-sans-kr/NotoSansKR-Regular.ttf";

	static final String BOLD = "fonts/noto-sans-kr/NotoSansKR-Bold.ttf";

	private static PdfFonts shared;

	/** 파싱한 글꼴과 유니코드 → 글리프 표(없는 글자 판정용) */
	record Face(TrueTypeFont ttf, CmapLookup cmap) {

		boolean has(int codePoint) {
			return cmap.getGlyphId(codePoint) != 0;
		}

	}

	private final Face regular;

	private final Face bold;

	private PdfFonts(Face regular, Face bold) {
		this.regular = regular;
		this.bold = bold;
	}

	/** 처음 부를 때 읽고 파싱한다(약 1~2초). 그 뒤로는 같은 객체 */
	static synchronized PdfFonts shared() {
		if (shared == null) {
			long started = System.nanoTime();
			shared = new PdfFonts(load(REGULAR), load(BOLD));
			log.info("리포트 PDF 글꼴 로드: Noto Sans KR Regular · Bold, {}ms", (System.nanoTime() - started) / 1_000_000);
		}
		return shared;
	}

	Face regular() {
		return regular;
	}

	Face bold() {
		return bold;
	}

	private static Face load(String resource) {
		try (InputStream in = PdfFonts.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new FileNotFoundException("classpath:" + resource);
			}
			TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(in.readAllBytes()));
			ttf.setEnableGsub(false);
			return new Face(ttf, ttf.getUnicodeCmapLookup());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("리포트 PDF 글꼴을 읽지 못했습니다: " + resource, ex);
		}
	}

}

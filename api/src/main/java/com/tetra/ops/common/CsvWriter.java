package com.tetra.ops.common;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 내려받는 CSV의 공통 형식(발급 이력 · 리포트): UTF-8 + BOM, 줄 끝 CRLF, RFC 4180 따옴표 규칙, 시각은 KST +09:00.
 * 메모리에 다 모은 뒤 {@link #toBytes()}로 한 번에 꺼낸다(S3에 한 번에 올림). 한 인스턴스는 한 스레드에서만 쓴다.
 * <pre>
 * byte[] csv = new CsvWriter("event_id", "name", "created_at").row(1043, "가을, 한정", createdAt).toBytes();
 * </pre>
 */
public final class CsvWriter {

	/** Excel(한국어 Windows)에서 한글이 깨지지 않도록 UTF-8 BOM을 붙인다 */
	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final String CRLF = "\r\n";

	/** 밀리초 3자리(남는 자리는 버림) + KST 오프셋. DB 값이 이미 KST라 변환하지 않는다 */
	private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'+09:00'");

	private final StringBuilder text = new StringBuilder();

	/** 머리글 한 줄을 쓴다 */
	public CsvWriter(String... header) {
		row((Object[]) header);
	}

	/**
	 * 한 줄. null은 빈 칸, LocalDateTime은 {@link #kst}, BigDecimal은 지수 표기 없이, 나머지는 toString().
	 * 모든 칸에 {@link #field} 규칙을 적용한다.
	 */
	public CsvWriter row(Object... fields) {
		for (int i = 0; i < fields.length; i++) {
			if (i > 0) {
				text.append(',');
			}
			text.append(field(text(fields[i])));
		}
		text.append(CRLF);
		return this;
	}

	public CsvWriter row(List<?> fields) {
		return row(fields.toArray());
	}

	/** BOM + UTF-8 */
	public byte[] toBytes() {
		byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream(BOM.length + body.length);
		out.writeBytes(BOM);
		out.writeBytes(body);
		return out.toByteArray();
	}

	/** RFC 4180: 쉼표 · 큰따옴표 · 줄바꿈이 있으면 큰따옴표로 감싸고 안의 큰따옴표는 두 번 쓴다 */
	public static String field(String value) {
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	/** KST 벽시계 값(DB DATETIME)에 +09:00을 붙인다. 예: 2026-11-01T10:00:00.123+09:00 */
	public static String kst(LocalDateTime value) {
		return KST.format(value);
	}

	private static String text(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof LocalDateTime time) {
			return kst(time);
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.toPlainString();
		}
		return value.toString();
	}

}

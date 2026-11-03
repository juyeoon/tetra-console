package com.tetra.ops.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 테넌트 리포트 zip(report-{id}.zip 안에 summary.pdf + 표마다 CSV). 같은 입력이면 같은 바이트가 나온다 —
 * 항목 시각을 고정하고(setTimeLocal: 시간대 · 확장 시각 필드 없음) 순서는 받은 그대로, 주석 · 폴더 항목은 넣지 않는다.
 * 항목 이름은 영문(ASCII 글자 · 숫자 · . _ -)만 — 압축 프로그램마다 한글 이름 처리가 달라서.
 */
public final class ReportZip {

	/** 항목 시각(로컬 벽시계 그대로 DOS 시각에 들어간다). Gradle 재현 가능 아카이브와 같은 값 */
	static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

	public record Entry(String name, byte[] content) {

		public Entry {
			if (name == null || !NAME.matcher(name).matches()) {
				throw new IllegalArgumentException("zip 항목 이름은 영문 · 숫자 · . _ -(100자 이내)만: " + name);
			}
			if (content == null) {
				throw new IllegalArgumentException("zip 항목 내용이 없습니다: " + name);
			}
		}

	}

	private ReportZip() {
	}

	public static byte[] zip(List<Entry> entries) {
		if (entries.isEmpty()) {
			throw new IllegalArgumentException("zip 항목이 없습니다");
		}
		Set<String> names = new HashSet<>();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Entry entry : entries) {
				if (!names.add(entry.name())) {
					throw new IllegalArgumentException("zip 항목 이름이 겹칩니다: " + entry.name());
				}
				ZipEntry zipEntry = new ZipEntry(entry.name());
				zipEntry.setTimeLocal(ENTRY_TIME);
				zip.putNextEntry(zipEntry);
				zip.write(entry.content());
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

}

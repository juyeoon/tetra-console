package com.tetra.ops.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

/** 항목 이름 · 내용 · 순서, 같은 입력 같은 바이트(시간대가 달라도), 영문 이름만. */
class ReportZipTest {

	private static final byte[] PDF = "%PDF-1.7 요약".getBytes(StandardCharsets.UTF_8);

	private static final byte[] CSV = "﻿a,b\r\n1,가\r\n".getBytes(StandardCharsets.UTF_8);

	private static List<ReportZip.Entry> entries() {
		return List.of(new ReportZip.Entry("summary.pdf", PDF), new ReportZip.Entry("issuance-by-minute.csv", CSV),
				new ReportZip.Entry("coupons.csv", new byte[0]));
	}

	private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				assertThat(entry.getTimeLocal()).isEqualTo(ReportZip.ENTRY_TIME);
				assertThat(entry.getExtra()).isNull();
				out.put(entry.getName(), in.readAllBytes());
			}
		}
		return out;
	}

	@Test
	void entriesInGivenOrderWithSameContent() throws IOException {
		Map<String, byte[]> files = unzip(ReportZip.zip(entries()));

		assertThat(files.keySet()).containsExactly("summary.pdf", "issuance-by-minute.csv", "coupons.csv");
		assertThat(files.get("summary.pdf")).isEqualTo(PDF);
		assertThat(files.get("issuance-by-minute.csv")).isEqualTo(CSV);
		assertThat(files.get("coupons.csv")).isEmpty();
	}

	@Test
	void sameInputSameBytesEvenInAnotherTimeZone() {
		byte[] first = ReportZip.zip(entries());
		TimeZone original = TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			assertThat(ReportZip.zip(entries())).isEqualTo(first);
		}
		finally {
			TimeZone.setDefault(original);
		}
		assertThat(ReportZip.zip(entries())).isEqualTo(first);
	}

	@Test
	void rejectsNonAsciiDuplicateOrEmpty() {
		assertThatThrownBy(() -> new ReportZip.Entry("요약.pdf", PDF)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReportZip.Entry("dir/summary.pdf", PDF)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReportZip.Entry(".hidden", PDF)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReportZip.Entry("a.csv", null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ReportZip.zip(List.of(new ReportZip.Entry("a.csv", CSV), new ReportZip.Entry("a.csv", CSV))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("겹칩니다");
		assertThatThrownBy(() -> ReportZip.zip(List.of())).isInstanceOf(IllegalArgumentException.class);
	}

}

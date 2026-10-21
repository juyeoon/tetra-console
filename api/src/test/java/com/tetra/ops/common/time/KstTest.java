package com.tetra.ops.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** 워크플로우 input 일시 형식 (ops-workflows 계약: KST 벽시계, 오프셋 없음, 초 항상 포함). */
class KstTest {

	static final String CONTRACT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}$";

	@Test
	void wallClockKeepsZeroSecondsAndHasNoOffset() {
		// LocalDateTime.toString()이나 OffsetDateTime.toString()은 초가 0이면 ":00"을 빼므로 그대로 쓰면 안 된다
		LocalDateTime zeroSeconds = LocalDateTime.of(2026, 10, 24, 14, 0, 0);
		assertThat(zeroSeconds.toString()).isEqualTo("2026-10-24T14:00");

		assertThat(Kst.wallClock(zeroSeconds)).isEqualTo("2026-10-24T14:00:00").matches(CONTRACT);
	}

	@Test
	void wallClockDropsFractionAndKeepsSeconds() {
		assertThat(Kst.wallClock(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 600_000_000)))
			.isEqualTo("2026-01-02T03:04:05")
			.matches(CONTRACT);
	}

	@Test
	void wallClockOfNullIsNull() {
		assertThat(Kst.wallClock(null)).isNull();
	}

}

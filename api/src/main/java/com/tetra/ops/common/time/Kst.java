package com.tetra.ops.common.time;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** DB에는 KST LocalDateTime(DATETIME)으로 저장하고, API 응답은 +09:00이 붙은 OffsetDateTime으로 내린다. */
public final class Kst {

	public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	/**
	 * Step Functions 워크플로우 input의 일시 형식. KST 벽시계, 오프셋 없음, 초는 0이어도 항상 포함.
	 * 상태 머신이 ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$ 로 검사한다(어긋나면 InvalidInput).
	 */
	private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private Kst() {
	}

	/** DATETIME 컬럼은 소수 초를 반올림하므로 초 단위로 자른다. */
	public static LocalDateTime now(Clock clock) {
		return LocalDateTime.now(clock.withZone(ZONE)).truncatedTo(ChronoUnit.SECONDS);
	}

	public static OffsetDateTime toOffset(LocalDateTime value) {
		return value == null ? null : value.atZone(ZONE).toOffsetDateTime();
	}

	/** 워크플로우 input용 KST 벽시계 문자열(예: 2026-10-24T14:00:00). DB 값이 이미 KST라 변환 없이 쓴다. */
	public static String wallClock(LocalDateTime value) {
		return value == null ? null : WALL_CLOCK.format(value);
	}

}

package com.tetra.ops.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.event.EntryOpen;

/**
 * 이벤트 그래프 범위(명세 1절 D-2 · 4절).
 * - 입장 직전 · 진행 중: entry(입장 −10분 ~ 지금, 기본) · 15m · 1h
 * - 종료: full(입장 −10분 ~ 종료 +10분 고정, 기본) · start(시작 ±10분). 종료 = hosting_end_at
 * 점이 {@link #MAX_POINTS}개를 넘지 않게 step을 정한다(Prometheus 15초 단위, AWS 60초 단위).
 */
record TrafficWindow(String key, List<String> options, Instant from, Instant to, Duration step) {

	static final List<String> LIVE_OPTIONS = List.of("entry", "15m", "1h");

	static final List<String> ENDED_OPTIONS = List.of("full", "start");

	static final Set<String> KEYS = Set.of("entry", "15m", "1h", "full", "start");

	static final int MAX_POINTS = 300;

	static final Duration PROMETHEUS_MIN_STEP = Duration.ofSeconds(15);

	static final Duration AWS_MIN_STEP = Duration.ofSeconds(60);

	static final Duration MARGIN = Duration.ofMinutes(10);

	static boolean isKnown(String key) {
		return key == null || KEYS.contains(key);
	}

	/** 이 상태에서 고를 수 없는 key면 기본값. 그래프가 없는 상태(예정 · 준비 실패)는 null */
	static TrafficWindow of(EventPhase phase, String requested, LocalDateTime startAt, LocalDateTime hostingEndAt,
			Instant now, Duration minStep) {
		Instant entry = instant(EntryOpen.at(startAt));
		Instant start = instant(startAt);
		if (phase == EventPhase.PRE_ENTRY || phase.entryOpened()) {
			String key = requested != null && LIVE_OPTIONS.contains(requested) ? requested : "entry";
			Instant from = switch (key) {
				case "15m" -> now.minus(Duration.ofMinutes(15));
				case "1h" -> now.minus(Duration.ofHours(1));
				default -> entry.minus(MARGIN);
			};
			return window(key, LIVE_OPTIONS, from, now, minStep);
		}
		if (phase == EventPhase.ENDED) {
			String key = requested != null && ENDED_OPTIONS.contains(requested) ? requested : "full";
			if ("start".equals(key)) {
				return window(key, ENDED_OPTIONS, start.minus(MARGIN), min(start.plus(MARGIN), now), minStep);
			}
			Instant end = hostingEndAt == null ? now : min(instant(hostingEndAt).plus(MARGIN), now);
			return window(key, ENDED_OPTIONS, entry.minus(MARGIN), end, minStep);
		}
		return null;
	}

	private static TrafficWindow window(String key, List<String> options, Instant from, Instant to, Duration minStep) {
		if (from.isAfter(to)) {
			from = to;
		}
		return new TrafficWindow(key, options, from, to, step(Duration.between(from, to), minStep));
	}

	/** 구간 ÷ 300을 minStep 배수로 올림(최소 minStep) */
	static Duration step(Duration span, Duration minStep) {
		long min = minStep.toSeconds();
		long raw = (long) Math.ceil(span.toSeconds() / (double) MAX_POINTS);
		long steps = Math.max(1, (raw + min - 1) / min);
		return Duration.ofSeconds(steps * min);
	}

	private static Instant instant(LocalDateTime kst) {
		return kst.atZone(Kst.ZONE).toInstant();
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}

}

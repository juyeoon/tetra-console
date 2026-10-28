package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.StringUtils;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.monitoring.TrafficEventCollector.PromSeries;
import com.tetra.ops.monitoring.TrafficEventResponse.Summary;

/**
 * 이벤트 결과 가공: p95(발급 · 번호표 중 큰 값), 구역별 격리(한 구역의 예외는 그 구역만 null),
 * 최종값 집계 구간(입장 ~ 이벤트 종료) · 1분 구간 시작 시각 · 최대 0이면 시각 없음 · Redis 지표 없으면 재시작 없음
 */
@ExtendWith(OutputCaptureExtension.class)
class TrafficEventCollectorTest {

	/** 이벤트 12처럼: 입장 09:55, 시작 10:00, 이벤트 종료 10:15(KST) */
	private static final LocalDateTime ENTRY = LocalDateTime.of(2026, 10, 28, 9, 55);

	private static final LocalDateTime EVENT_END = LocalDateTime.of(2026, 10, 28, 10, 15);

	private static final Duration STEP = Duration.ofSeconds(15);

	@Test
	void largerP95() {
		assertThat(TrafficEventCollector.larger(180L, 120L)).isEqualTo(180L);
		assertThat(TrafficEventCollector.larger(120L, 180L)).isEqualTo(180L);
		assertThat(TrafficEventCollector.larger(180L, null)).isEqualTo(180L);
		assertThat(TrafficEventCollector.larger(null, 120L)).isEqualTo(120L);
		// 입장 직전 · 최근 1분 요청 없음 — 2026-10-28 전에는 NPE로 갱신 전체가 실패
		assertThat(TrafficEventCollector.larger(null, null)).isNull();
	}

	@Test
	void sectionFailureBlanksOnlyThatSection(CapturedOutput output) {
		TrafficEventCollector collector = new TrafficEventCollector(null, Clock.systemUTC());
		Supplier<String> broken = () -> {
			throw new NullPointerException("가공 버그");
		};
		assertThat(collector.section("이벤트 11번", "KPI", broken)).isNull();
		assertThat(collector.section("이벤트 11번", "KPI", broken)).isNull();
		// 다른 구역은 그대로
		assertThat(collector.section("이벤트 11번", "그래프", () -> "charts")).isEqualTo("charts");
		assertThat(output.getOut())
			.contains("트래픽 모니터링 · 결과 가공 실패(이벤트 11번 · KPI: NullPointerException) — 그 구역만 비웁니다")
			.contains("트래픽 모니터링 · 결과 가공 실패(이벤트 11번 · KPI: NullPointerException · 연속 2번째) — 그 구역만 비웁니다");
		// 스택 트레이스는 연속 실패의 처음 한 번만
		assertThat(StringUtils.countOccurrencesOf(output.getOut(), "java.lang.NullPointerException: 가공 버그")).isEqualTo(1);

		// 그 구역이 한 번 성공하면 처음부터 다시
		assertThat(collector.section("이벤트 11번", "KPI", () -> "kpi")).isEqualTo("kpi");
		collector.section("이벤트 11번", "KPI", broken);
		assertThat(StringUtils.countOccurrencesOf(output.getOut(), "java.lang.NullPointerException: 가공 버그")).isEqualTo(2);
	}

	@Test
	void minuteTimesAreStartOfOneMinuteBucket() {
		// rate[1m] 값(10:01:00 = 10:00 ~ 10:01의 1분) → 10:00. 15초 격자 10:01:45 = 10:00:45 ~ 10:01:45 → 창 시작이 든 10:00
		assertThat(at(TrafficEventCollector.rateWindowMinute(millis(10, 1, 0)))).isEqualTo(kst(10, 0));
		assertThat(at(TrafficEventCollector.rateWindowMinute(millis(10, 1, 45)))).isEqualTo(kst(10, 0));
		// 게이지(그 시각 상태)는 그 시각이 든 분
		assertThat(at(TrafficEventCollector.minuteOf(millis(10, 1, 45)))).isEqualTo(kst(10, 1));
		// 처음 0으로 보인 점이 정각이면 바뀐 건 그 앞 분(결과 탭 10:05~10:06 = 10:05), 정각이 아니면 그 분
		assertThat(at(TrafficEventCollector.changedMinute(millis(10, 6, 0)))).isEqualTo(kst(10, 5));
		assertThat(at(TrafficEventCollector.changedMinute(millis(10, 5, 45)))).isEqualTo(kst(10, 5));
		assertThat(at(TrafficEventCollector.changedMinute(millis(10, 6, 15)))).isEqualTo(kst(10, 6));
	}

	@Test
	void finalKpiCountsOnlyEntryToEventEnd() {
		// 그래프 범위(입장 −10분 ~ 이벤트 종료 +10분) 격자. 입장 전 웜업 09:48 57.9 RPS · 이벤트 종료 뒤 정리 10:20 80 RPS
		PromSeries s = grid(ENTRY.minusMinutes(10), EVENT_END.plusMinutes(10), Map.of(
				TrafficEventCollector.RPS, t -> at(t, 9, 48, 0) ? 57.9 : at(t, 10, 20, 0) ? 80.0 : at(t, 10, 1, 0) ? 3.2 : 0.5,
				TrafficEventCollector.ERROR_RPS, t -> 0.0,
				TrafficEventCollector.ERROR_RATIO, t -> 0.0,
				TrafficEventCollector.WAITING, t -> at(t, 9, 50, 0) ? 300.0 : 0.0,
				// 10:05:50쯤 소진 → 15초 격자에서 처음 0으로 보인 점 10:06:00
				TrafficEventCollector.STOCK, t -> t < millis(10, 6, 0) ? 10.0 : 0.0,
				TrafficEventCollector.REDIS_RESTARTS, t -> 0.0));
		Summary summary = TrafficEventCollector.summary(s, instant(ENTRY), instant(EVENT_END), 10L, null);

		// 입장 전 웜업 · 종료 뒤 정리 값은 최대에 들어가지 않는다
		assertThat(summary.peakRps().value()).isEqualTo(3.2);
		assertThat(summary.peakRps().at().toLocalDateTime()).isEqualTo(kst(10, 0));
		// 대기 인원은 입장 전에만 있었다 → 구간 최대 0, 시각 없음(화면 "–")
		assertThat(summary.peakWaiting().value()).isEqualTo(0.0);
		assertThat(summary.peakWaiting().at()).isNull();
		assertThat(summary.soldOutAt().toLocalDateTime()).isEqualTo(kst(10, 5));
		assertThat(summary.issued()).isEqualTo(10L);
		assertThat(summary.errorRatioAvg()).isEqualTo(0.0);
		// Redis 지표(redis-exporter)가 없으면 재시작(kube-state-metrics 0)도 모름
		assertThat(summary.redisMemoryMax()).isNull();
		assertThat(summary.redisRestarts()).isNull();

		// RPS가 내내 0이면 최대 0 · 시각 없음
		PromSeries idle = grid(ENTRY, EVENT_END, Map.of(TrafficEventCollector.RPS, t -> 0.0,
				TrafficEventCollector.REDIS_MEMORY, t -> 0.2, TrafficEventCollector.REDIS_RESTARTS, t -> 1.0));
		Summary quiet = TrafficEventCollector.summary(idle, instant(ENTRY), instant(EVENT_END), 10L, 0L);
		assertThat(quiet.peakRps().value()).isEqualTo(0.0);
		assertThat(quiet.peakRps().at()).isNull();
		assertThat(quiet.redisRestarts()).isEqualTo(1L);
	}

	@Test
	void seriesBetweenKeepsBothEnds() {
		PromSeries s = grid(ENTRY.minusMinutes(1), ENTRY.plusMinutes(1), Map.of(TrafficEventCollector.RPS, t -> 1.0));
		PromSeries cut = s.between(instant(ENTRY), instant(ENTRY.plusSeconds(30)));
		assertThat(cut.timestamps()).containsExactly(millis(9, 55, 0), millis(9, 55, 15), millis(9, 55, 30));
		assertThat(cut.get(TrafficEventCollector.RPS)).containsExactly(1.0, 1.0, 1.0);
		assertThat(cut.get(TrafficEventCollector.STOCK)).containsExactly(null, null, null);
		assertThat(s.between(null, null).timestamps()).hasSize(9);
	}

	/** from ~ to, 15초 격자. 값 함수는 epoch 밀리초 → 값 */
	private static PromSeries grid(LocalDateTime from, LocalDateTime to, Map<String, LongFunction<Double>> values) {
		List<Long> times = new ArrayList<>();
		for (long t = instant(from).toEpochMilli(); t <= instant(to).toEpochMilli(); t += STEP.toMillis()) {
			times.add(t);
		}
		Map<String, List<Double>> out = new LinkedHashMap<>();
		values.forEach((name, f) -> out.put(name, times.stream().map(f::apply).toList()));
		return new PromSeries(Collections.unmodifiableList(times), out);
	}

	private static boolean at(long epochMillis, int hour, int minute, int second) {
		return epochMillis == millis(hour, minute, second);
	}

	private static long millis(int hour, int minute, int second) {
		return instant(LocalDateTime.of(2026, 10, 28, hour, minute, second)).toEpochMilli();
	}

	private static LocalDateTime at(long epochMillis) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), Kst.ZONE);
	}

	private static LocalDateTime kst(int hour, int minute) {
		return LocalDateTime.of(2026, 10, 28, hour, minute);
	}

	private static Instant instant(LocalDateTime kst) {
		return kst.atZone(Kst.ZONE).toInstant();
	}

}

package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.StringUtils;

/** 이벤트 결과 가공: p95(발급 · 번호표 중 큰 값)와 구역별 격리(한 구역의 예외는 그 구역만 null) */
@ExtendWith(OutputCaptureExtension.class)
class TrafficEventCollectorTest {

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

}

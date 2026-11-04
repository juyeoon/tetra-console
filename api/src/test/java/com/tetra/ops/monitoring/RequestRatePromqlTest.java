package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * 요청량 식 · 5xx 비율 · 홈 누적의 PromQL을 실제 Prometheus 엔진(promtool test rules)으로 합성 데이터에 돌린다.
 * 식은 이 앱이 보내는 문자열 그대로(TrafficQueries · PromQueries). 이미지는 클러스터 Prometheus(3.15.0)와 같은 판,
 * infra-k8s CI(경보 규칙 시험)와 같은 다이제스트.
 *
 * 합성 데이터(1초 = 점 하나):
 * - Pod a는 1초 수집, Pod b는 15초 수집(0 · 15 · 30 …초). 둘 다 /session 초당 10건
 * - 60초에 /ticket 시리즈가 처음 생긴다(a 첫 값 1000, b 첫 값 1500 — 이벤트 20의 번호표 몰림 모양). 그 뒤 더 오르지 않음
 * - Pod c는 100초에 뜬다(첫 수집 300, 그 뒤 초당 10)
 */
class RequestRatePromqlTest {

	static final DockerImageName PROMETHEUS = DockerImageName
		.parse("prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e");

	static final String T = "aaaaaaaaaaaa";

	static final int END = 200;

	@Test
	void requestRateCountsFirstValueAndIsPerScrapeInterval() {
		TrafficQueries.EventQueries q = TrafficQueries.forEvent(T, 7);
		String kpi = q.rps(TrafficQueries.RPS_INSTANT_WINDOW);
		String raw = RequestRate.byPod("event_id=\"7\",uri!~\"" + PromQueries.EXCLUDED_URI + "\"", "event_id=\"7\"", "pod");
		String yaml = header() + """
				  - interval: 1s
				    input_series:
				%s
				    promql_expr_test:
				%s
				""".formatted(String.join("\n", List.of(
				series(req("a", "/session", "200"), every(1, 0, t -> 10 * t)),
				series(uptime("a"), every(1, 0, t -> t)),
				series(req("b", "/session", "200"), every(15, 0, t -> 10 * t)),
				series(uptime("b"), every(15, 0, t -> t)),
				series(req("a", "/ticket", "200"), every(1, 60, t -> 1000)),
				series(req("b", "/ticket", "200"), every(15, 60, t -> 1500)),
				series(req("c", "/session", "200"), every(1, 100, t -> 300 + 10 * (t - 100))),
				series(uptime("c"), every(1, 100, t -> 20 + (t - 100))))),
				String.join("\n", List.of(
						// 59초: 두 Pod 모두 초당 10(15초 수집 b도 15초 평균 10 — 부풀지 않음)
						expect("sum by (pod) (" + raw + ")", 59, "{pod=\"a\"}", 10, "{pod=\"b\"}", 10),
						// 60초: 처음 생긴 시리즈의 첫 값 ÷ 그 Pod 수집 간격 — a 10 + 1000/1초, b 10 + 1500/15초
						expect("sum by (pod) (" + raw + ")", 60, "{pod=\"a\"}", 1010, "{pod=\"b\"}", 110),
						// 61초: a는 1초 뒤 다음 수집(증가 0), b는 다음 수집(75초)까지 같은 15초 평균
						expect("sum by (pod) (" + raw + ")", 61, "{pod=\"a\"}", 10, "{pod=\"b\"}", 110),
						expect("sum by (pod) (" + raw + ")", 75, "{pod=\"a\"}", 10, "{pod=\"b\"}", 10),
						// KPI(최근 15초 안 1초 값의 최대): 69초 창(54, 69]에 60초가 들어간다. 75초 창(60, 75]부터 빠진다
						expect(kpi, 69, "{}", 1120),
						expect(kpi, 75, "{}", 120),
						expect(kpi, 90, "{}", 20),
						// 새 Pod c의 첫 수집(100초)은 간격을 몰라 빠지고, 다음 수집부터 센다
						expect("sum(" + raw + ")", 100, "{}", 20),
						expect("sum(" + raw + ")", 101, "{}", 30),
						// 그래프 간격 30초면 창도 30초: 89초 창(59, 89]에 60초가 들어간다
						expect(q.rps(Duration.ofSeconds(30)), 89, "{}", 1120),
						// 지금까지의 식(rate 1m)은 첫 값을 보지 못한다 — 몰림이 끝내 안 보인다
						expect(q.ratioRps(), 60, "{}", 20), expect(q.ratioRps(), 75, "{}", 20),
						// 홈: 테넌트별 · 전체도 같은 식
						expect(PromQueries.RPS_BY_TENANT, 69, "{tenant_id=\"" + T + "\"}", 1120),
						expect(PromQueries.RPS_TOTAL, 69, "{}", 1120))));
		assertPromtool(yaml);
	}

	@Test
	void errorRatioStaysRateOverRate() {
		// 1초 수집 Pod 하나, 초당 98건 정상 + 2건 5xx(2%). 60초에 번호표 몰림 6,000건이 새 시리즈로 생긴다
		TrafficQueries.EventQueries q = TrafficQueries.forEvent(T, 7);
		String ratio = "(" + q.errorRps() + ") / (" + q.ratioRps() + ")";
		String yaml = header() + """
				  - interval: 1s
				    input_series:
				%s
				    promql_expr_test:
				%s
				""".formatted(String.join("\n", List.of(
				series(req("a", "/session", "200"), every(1, 0, t -> 98 * t)),
				series(req("a", "/session", "500"), every(1, 0, t -> 2 * t)),
				series(req("a", "/ticket", "200"), every(1, 60, t -> 6000)),
				series(uptime("a"), every(1, 0, t -> t)))),
				String.join("\n", List.of(
						// 화면 요청량은 몰림을 본다(98 + 2 + 6000)
						expect(q.rps(TrafficQueries.RPS_INSTANT_WINDOW), 69, "{}", 6100),
						// 5xx 비율은 rate 1m ÷ rate 1m 그대로 2% — 분모를 요청량 식으로 바꿨다면 2 ÷ 6100 = 0.03%로 가려졌다
						expect(ratio, 60, "{}", 0.02), expect(ratio, 69, "{}", 0.02), expect(ratio, 120, "{}", 0.02),
						// 상태 요약 판정 재료(이벤트별)도 같은 rate 짝
						expect("(" + TrafficQueries.ERROR_RPS_BY_EVENT + ") / on(event_id) (" + TrafficQueries.RPS_BY_EVENT + ")",
								69, "{event_id=\"7\"}", 0.02),
						// 홈 점 나누기(화면 효과)는 rps와 같은 요청량 식
						expect(PromQueries.errorRpsByTenant(List.of(T)), 69, "{tenant_id=\"" + T + "\"}", 2))));
		assertPromtool(yaml);
	}

	@Test
	void homeCumulativeCountsSeriesBornInWindow() {
		// 0초부터 초당 10건(200)인 시리즈 + 100초에 처음 생긴 시리즈(200 첫 값 1000, 5xx 첫 값 50). 180초에 최근 120초 누적
		String query = PromQueries.requestsByStatusSince(T, Duration.ofSeconds(120));
		String yaml = header() + """
				  - interval: 1s
				    input_series:
				%s
				    promql_expr_test:
				%s
				""".formatted(String.join("\n", List.of(
				series(req("a", "/session", "200"), every(1, 0, t -> 10 * t)),
				series(req("a", "/ticket", "200"), every(1, 100, t -> 1000)),
				series(req("a", "/ticket", "500"), every(1, 100, t -> 50)))),
				String.join("\n", List.of(
						// 있던 시리즈는 increase(1200), 처음 생긴 시리즈는 첫 값부터(1000 · 50). increase만이면 200 = 1200, 5xx 없음
						expect(query, 180, "{status=\"200\"}", 2200, "{status=\"500\"}", 50))));
		assertPromtool(yaml);
	}

	// ---------------------------------------------------------------- promtool

	private static String header() {
		return """
				rule_files: []
				evaluation_interval: 1s
				tests:
				""";
	}

	private static void assertPromtool(String yaml) {
		try (GenericContainer<?> promtool = new GenericContainer<>(PROMETHEUS)
			.withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("promtool"))
			.withCommand("test", "rules", "/work/request-rate.test.yml")
			.withCopyToContainer(Transferable.of(yaml), "/work/request-rate.test.yml")
			.withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(120)))) {
			String logs;
			try {
				promtool.start();
				logs = promtool.getLogs();
			}
			catch (RuntimeException ex) {
				throw new AssertionError("promtool 실패:\n" + promtool.getLogs() + "\n--- 시험 파일 ---\n" + yaml, ex);
			}
			assertThat(logs).as(yaml).contains("SUCCESS");
		}
	}

	/** 시험 파일 한 줄. PromQL은 작은따옴표(YAML) — 이 앱의 식에는 작은따옴표가 없다 */
	private static String series(String selector, String values) {
		assertThat(selector).doesNotContain("'");
		return "      - series: '" + selector + "'\n        values: '" + values + "'";
	}

	/** expr을 evalSeconds에 평가한 기대 결과. labelsAndValues = 라벨, 값, 라벨, 값 … */
	private static String expect(String expr, int evalSeconds, Object... labelsAndValues) {
		assertThat(expr).doesNotContain("'");
		StringBuilder out = new StringBuilder("      - expr: '" + expr + "'\n        eval_time: " + evalSeconds
				+ "s\n        exp_samples:");
		for (int i = 0; i < labelsAndValues.length; i += 2) {
			out.append("\n          - labels: '").append(labelsAndValues[i]).append("'\n            value: ")
				.append(labelsAndValues[i + 1]);
		}
		return out.toString();
	}

	private static String req(String pod, String uri, String status) {
		return "http_server_requests_seconds_count{tenant_id=\"%s\",event_id=\"7\",pod=\"%s\",uri=\"%s\",status=\"%s\"}"
			.formatted(T, pod, uri, status);
	}

	private static String uptime(String pod) {
		return "process_uptime_seconds{tenant_id=\"%s\",event_id=\"7\",pod=\"%s\"}".formatted(T, pod);
	}

	/** 0 ~ END초. from초부터 intervalSeconds마다 값(나머지는 '_' = 점 없음) — 1초 · 15초 수집을 흉내 낸다 */
	private static String every(int intervalSeconds, int from, LongFunction<Number> value) {
		StringBuilder out = new StringBuilder();
		for (long t = 0; t <= END; t++) {
			out.append(t == 0 ? "" : " ");
			out.append(t >= from && (t - from) % intervalSeconds == 0 ? value.apply(t).toString() : "_");
		}
		return out.toString();
	}

}

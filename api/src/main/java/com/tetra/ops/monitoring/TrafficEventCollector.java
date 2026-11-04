package com.tetra.ops.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.monitoring.PromValues.Grid;
import com.tetra.ops.monitoring.PrometheusClient.Sample;
import com.tetra.ops.monitoring.TrafficEventResponse.Live;
import com.tetra.ops.monitoring.TrafficEventResponse.Peak;
import com.tetra.ops.monitoring.TrafficEventResponse.RedisConnection;
import com.tetra.ops.monitoring.TrafficEventResponse.Summary;
import com.tetra.ops.monitoring.TrafficQueries.EventQueries;
import com.tetra.ops.monitoring.TrafficRules.EventSignals;

/**
 * OPS-05 이벤트 하나의 Prometheus 갱신 1회(C 준비 시간 · D KPI · 그래프). 상태별로 조회하는 것이 다르다(명세 4절):
 * - 입장 직전: 그래프 + Pod · 재고 · Redis · 자원 순간값(요청 처리 · 대기 인원은 조회하지 않음)
 * - 입장 중 · 진행 중 · 마무리: 그래프 + KPI 순간값 전부
 * - 호스팅 종료 뒤(종료 처리 중 · 종료 · 종료 실패): 그래프 + 전체 구간(full) 그래프(최종값 계산용, 범위가 full이면 같은 것)
 * 쿼리는 {@link PromBatch}(4개씩 병렬). Prometheus 자체 문제면 갱신 전체 실패, 쿼리 하나의 오류면 그 항목만 null.
 * 결과 가공은 구역(그래프 · 전체 구간 · KPI · 준비 시간)마다 따로 — 한 구역에서 예외가 나면 그 구역만 null, 나머지는 응답(2026-10-28).
 */
@Component
class TrafficEventCollector {

	private static final Logger log = LoggerFactory.getLogger(TrafficEventCollector.class);

	// ---- 그래프 시리즈 이름(모두 같은 격자) ----

	/** 화면 요청량 — 요청량 식({@link RequestRate}, 창 = 그래프 간격) */
	static final String RPS = "rps";

	/** 5xx 비율 분모(rate 1m, 응답 그래프에는 안 나감) — 비율 · 평균 5xx는 이 값과 {@link #ERROR_RPS}로만 */
	static final String RATIO_RPS = "ratioRps";

	static final String ERROR_RPS = "errorRps";

	static final String ERROR_RATIO = "errorRatio";

	static final String P95_ISSUANCE = "p95Issuance";

	static final String P95_TICKET = "p95Ticket";

	static final String ADMITTED = "admittedPerSec";

	static final String CURSOR = "cursorPerSec";

	static final String WAITING = "waiting";

	static final String STOCK = "stock";

	static final String READY = "ready";

	static final String PENDING = "pending";

	static final String HPA_MIN = "hpaMin";

	static final String HPA_MAX = "hpaMax";

	static final String CPU = "cpu";

	static final String MEMORY = "memory";

	static final String CPU_REQUEST = "cpuRequest";

	static final String MEMORY_REQUEST = "memoryRequest";

	static final String REDIS_MEMORY = "redisMemory";

	static final String REDIS_CPU = "redisCpu";

	static final String REDIS_RESTARTS = "redisRestarts";

	/**
	 * @param full 호스팅 종료 뒤 상태의 전체 구간(최종값용). window와 같으면 한 번만 조회. 다른 상태는 null
	 * @param prepareEvalAt 준비 시간을 잴 시각(입장 = OPEN 로그). null이면 재지 않음
	 * @param stockTotal event.stock_count
	 */
	record Request(long eventId, String tenantId, EventPhase phase, TrafficWindow window, TrafficWindow full,
			Instant prepareEvalAt, Long stockTotal) {
	}

	/** 같은 격자의 시리즈들(이름 → timestamps와 같은 길이, 값 없으면 null) */
	record PromSeries(List<Long> timestamps, Map<String, List<Double>> values) {

		List<Double> get(String name) {
			List<Double> v = values.get(name);
			return v != null ? v : Collections.nCopies(timestamps.size(), null);
		}

		/** from ~ to(양 끝 포함) 점만. null인 끝은 자르지 않는다 */
		PromSeries between(Instant from, Instant to) {
			List<Integer> keep = new ArrayList<>();
			for (int i = 0; i < timestamps.size(); i++) {
				long t = timestamps.get(i);
				if ((from == null || t >= from.toEpochMilli()) && (to == null || t <= to.toEpochMilli())) {
					keep.add(i);
				}
			}
			Map<String, List<Double>> cut = new LinkedHashMap<>();
			values.forEach((name, v) -> cut.put(name, keep.stream().map(v::get).toList()));
			return new PromSeries(keep.stream().map(timestamps::get).toList(), cut);
		}

	}

	/** live · signals는 입장 직전 · 진행 중만. 가공에 실패한 구역은 null */
	record Metrics(Instant at, PromSeries charts, PromSeries full, Live live, EventSignals signals, Long prepareSeconds) {
	}

	/** KPI 구역(순간값) */
	private record Instants(Live live, EventSignals signals) {
	}

	private final PrometheusClient prometheus;

	private final Clock clock;

	private final FailureStreaks streaks = new FailureStreaks();

	TrafficEventCollector(PrometheusClient prometheus, Clock clock) {
		this.prometheus = prometheus;
		this.clock = clock;
	}

	Metrics collect(Request req, long deadlineNanos, ExecutorService executor) {
		Instant now = clock.instant();
		String scope = "이벤트 " + req.eventId() + "번";
		EventQueries q = TrafficQueries.forEvent(req.tenantId(), req.eventId());
		PromBatch batch = new PromBatch(prometheus, executor, deadlineNanos, scope, streaks);
		batch.instant("ksm", TrafficQueries.KUBE_STATE_METRICS_UP, now);
		Grid grid = Grid.of(req.window().from(), req.window().to(), req.window().step());
		addRanges(batch, "c.", q, grid);
		Grid fullGrid = req.full() != null && !req.full().equals(req.window())
				? Grid.of(req.full().from(), req.full().to(), req.full().step()) : null;
		if (fullGrid != null) {
			addRanges(batch, "f.", q, fullGrid);
		}
		boolean live = req.phase().entryOpened();
		boolean instants = live || req.phase() == EventPhase.PRE_ENTRY;
		if (instants) {
			if (live) {
				batch.instant("rps", q.rps(TrafficQueries.RPS_INSTANT_WINDOW), now)
					.instant("ratioRps", q.ratioRps(), now)
					.instant("errorRps", q.errorRps(), now)
					.instant("p95", q.p95ByUri(), now)
					.instant("cursorChanges", q.cursorChanges(), now)
					.instant("waiting", q.waiting(), now);
			}
			batch.instant("stock", q.stock(), now)
				.instant("ready", q.apiReady(), now)
				.instant("pending", q.apiPending(), now)
				.instant("hpaMin", q.hpaMin(), now)
				.instant("hpaMax", q.hpaMax(), now)
				.instant("cpu", q.apiCpu(), now)
				.instant("cpuRequest", q.apiCpuRequest(), now)
				.instant("memory", q.apiMemory(), now)
				.instant("memoryRequest", q.apiMemoryRequest(), now)
				.instant("redisUsed", q.redisMemoryUsed(), now)
				.instant("redisMax", q.redisMemoryMax(), now)
				.instant("redisOps", q.redisOps(), now)
				.instant("redisClients", q.redisClients(), now)
				.instant("redisDown", q.redisDown(), now)
				.instant("redisDownPoints", q.redisDownPoints(), now)
				.instant("redisRestarts", q.redisRestarts(), now);
		}
		if (req.prepareEvalAt() != null) {
			batch.instant("prepare", q.prepareSeconds(), req.prepareEvalAt().isAfter(now) ? now : req.prepareEvalAt());
		}
		PromBatch.Results r = batch.execute();

		boolean ksm = !r.instant("ksm").isEmpty() && PromValues.single(r.instant("ksm")) != null;
		PromSeries charts = section(scope, "그래프", () -> series(r, "c.", grid, ksm));
		PromSeries full = fullGrid == null ? (req.full() == null ? null : charts)
				: section(scope, "전체 구간", () -> series(r, "f.", fullGrid, ksm));
		Instants kpi = instants ? section(scope, "KPI", () -> instantValues(r, live, ksm, req.stockTotal())) : null;
		Long prepareSeconds = r.has("prepare") ? section(scope, "준비 시간", () -> prepareSeconds(r)) : null;
		return new Metrics(now, charts, full, kpi == null ? null : kpi.live(), kpi == null ? null : kpi.signals(),
				prepareSeconds);
	}

	/** 가공 구역 하나. 예외가 나면 그 구역만 null(WARN — 스택 트레이스는 연속 실패의 처음 한 번만) */
	<T> T section(String scope, String name, Supplier<T> build) {
		String where = scope + " · " + name;
		try {
			T value = build.get();
			streaks.succeeded(where);
			return value;
		}
		catch (RuntimeException ex) {
			streaks.warn(log, where, "트래픽 모니터링 · 결과 가공 실패", where + ": " + ex.getClass().getSimpleName(),
					"그 구역만 비웁니다", ex);
			return null;
		}
	}

	private static Instants instantValues(PromBatch.Results r, boolean live, boolean ksm, Long stockTotal) {
		Live liveKpi = live(r, live, ksm, stockTotal);
		// 판정 재료가 하나라도 쿼리 오류면 complete = false(위험이 안 보이면 UNKNOWN). 5xx 오류면 분모도 비운다.
		// 판정의 5xx 비율 분모는 화면 요청량(요청량 식)이 아니라 rate 1m(분자와 같은 창, 2026-11-04 확정 4)
		boolean complete = r.allOk("ksm", "stock", "ready", "hpaMax", "redisDown", "redisDownPoints")
				&& (!live || r.allOk("rps", "ratioRps", "errorRps", "p95", "cursorChanges", "waiting"));
		EventSignals signals = new EventSignals(live && r.ok("errorRps") ? PromValues.single(r.instant("ratioRps")) : null,
				live ? PromValues.single(r.instant("errorRps")) : null,
				liveKpi.p95Ms() == null ? null : liveKpi.p95Ms() / 1000.0,
				live ? PromValues.single(r.instant("cursorChanges")) : null,
				liveKpi.waiting() == null ? null : liveKpi.waiting().doubleValue(),
				liveKpi.stockRemaining() == null ? null : liveKpi.stockRemaining().doubleValue(), redis(r),
				liveKpi.podsReady(), liveKpi.podsMax(), complete);
		return new Instants(liveKpi, signals);
	}

	/** Redis 연결(판정 · KPI 칸 공통). 끊김 쿼리가 실패하면 UP으로 나온다 — 판정은 complete = false, 칸은 null로 따로 막는다 */
	private static RedisConnection redis(PromBatch.Results r) {
		return TrafficRules.redisConnection(!r.instant("redisDown").isEmpty(),
				PromValues.single(r.instant("redisDownPoints")));
	}

	private static Long prepareSeconds(PromBatch.Results r) {
		Double v = PromValues.single(r.instant("prepare"));
		return v == null || v < 0 ? null : Math.round(v);
	}

	/**
	 * p95 · 입장 · 입장 허용 창은 max(1분, 그래프 간격)({@link TrafficQueries#rangeWindow}).
	 * 요청량 창은 그래프 간격 그대로 — 1분으로 넓히면 몰림 최대가 이웃 네 점에 번진다
	 */
	private static void addRanges(PromBatch batch, String prefix, EventQueries q, Grid g) {
		Duration window = TrafficQueries.rangeWindow(g.step());
		batch.range(prefix + RPS, q.rps(g.step()), g.start(), g.end(), g.step())
			.range(prefix + RATIO_RPS, q.ratioRps(), g.start(), g.end(), g.step())
			.range(prefix + ERROR_RPS, q.errorRps(), g.start(), g.end(), g.step())
			.range(prefix + "p95", q.p95ByUri(window), g.start(), g.end(), g.step())
			.range(prefix + ADMITTED, q.admittedPerSec(window), g.start(), g.end(), g.step())
			.range(prefix + CURSOR, q.cursorPerSec(window), g.start(), g.end(), g.step())
			.range(prefix + WAITING, q.waiting(), g.start(), g.end(), g.step())
			.range(prefix + STOCK, q.stock(), g.start(), g.end(), g.step())
			.range(prefix + READY, q.apiReady(), g.start(), g.end(), g.step())
			.range(prefix + PENDING, q.apiPending(), g.start(), g.end(), g.step())
			.range(prefix + HPA_MIN, q.hpaMin(), g.start(), g.end(), g.step())
			.range(prefix + HPA_MAX, q.hpaMax(), g.start(), g.end(), g.step())
			.range(prefix + CPU, q.apiCpu(), g.start(), g.end(), g.step())
			.range(prefix + MEMORY, q.apiMemory(), g.start(), g.end(), g.step())
			.range(prefix + CPU_REQUEST, q.apiCpuRequest(), g.start(), g.end(), g.step())
			.range(prefix + MEMORY_REQUEST, q.apiMemoryRequest(), g.start(), g.end(), g.step())
			.range(prefix + REDIS_MEMORY, q.redisMemoryRatio(), g.start(), g.end(), g.step())
			.range(prefix + REDIS_CPU, q.redisCpuRatio(), g.start(), g.end(), g.step())
			.range(prefix + REDIS_RESTARTS, q.redisRestarts(), g.start(), g.end(), g.step());
	}

	private static PromSeries series(PromBatch.Results r, String prefix, Grid g, boolean ksm) {
		List<Long> times = g.timestamps();
		Map<String, List<Double>> v = new LinkedHashMap<>();
		v.put(RPS, round(PromValues.onGrid(r.range(prefix + RPS), times, false), 2));
		// 5xx 비율은 rate 1m 짝(분모 RATIO_RPS · 분자 ERROR_RPS) — 화면 요청량(요청량 식)으로 나누지 않는다
		List<Double> ratioRps = round(PromValues.onGrid(r.range(prefix + RATIO_RPS), times, false), 2);
		List<Double> errorRps = PromValues.onGrid(r.range(prefix + ERROR_RPS), times, false);
		v.put(RATIO_RPS, ratioRps);
		v.put(ERROR_RPS, round(errorRps, 3));
		// 5xx 쿼리가 오류면 비율은 모름(null). "요청만 있고 5xx 없음 = 0"은 쿼리가 성공했을 때만
		boolean errorOk = r.ok(prefix + ERROR_RPS);
		List<Double> ratio = new ArrayList<>(times.size());
		for (int i = 0; i < times.size(); i++) {
			ratio.add(errorOk ? PromValues.round(TrafficRules.errorRatio(ratioRps.get(i), errorRps.get(i)), 5) : null);
		}
		v.put(ERROR_RATIO, ratio);
		v.put(P95_ISSUANCE, ms(PromValues.onGrid(r.range(prefix + "p95"), "uri", "claim", times)));
		v.put(P95_TICKET, ms(PromValues.onGrid(r.range(prefix + "p95"), "uri", "ticket", times)));
		v.put(ADMITTED, round(PromValues.onGrid(r.range(prefix + ADMITTED), times, false), 2));
		v.put(CURSOR, round(PromValues.onGrid(r.range(prefix + CURSOR), times, false), 2));
		// 쿼리가 이미 0 하한(clamp_min) — 여기 0 하한은 안전장치
		v.put(WAITING, PromValues.map(PromValues.onGrid(r.range(prefix + WAITING), times, false),
				x -> Math.max(0, Math.round(x))));
		v.put(STOCK, PromValues.map(PromValues.onGrid(r.range(prefix + STOCK), times, false), Math::round));
		v.put(READY, PromValues.onGrid(r.range(prefix + READY), times, ksm && r.ok(prefix + READY)));
		v.put(PENDING, PromValues.onGrid(r.range(prefix + PENDING), times, ksm && r.ok(prefix + PENDING)));
		for (String name : List.of(HPA_MIN, HPA_MAX, MEMORY, CPU_REQUEST, MEMORY_REQUEST, REDIS_RESTARTS)) {
			v.put(name, PromValues.onGrid(r.range(prefix + name), times, false));
		}
		v.put(CPU, round(PromValues.onGrid(r.range(prefix + CPU), times, false), 3));
		v.put(REDIS_MEMORY, round(PromValues.onGrid(r.range(prefix + REDIS_MEMORY), times, false), 4));
		v.put(REDIS_CPU, round(PromValues.onGrid(r.range(prefix + REDIS_CPU), times, false), 4));
		return new PromSeries(times, v);
	}

	/**
	 * 둘 중 큰 값. 하나만 있으면 그 값, 둘 다 없으면 null(입장 직전 · 최근 1분 요청 없음).
	 * Long끼리만 비교한다 — 삼항식에 long(Math.max)이 섞이면 식 전체가 long이 되어 null을 풀다가 NPE(2026-10-28)
	 */
	static Long larger(Long a, Long b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a >= b ? a : b;
	}

	private static Live live(PromBatch.Results r, boolean live, boolean ksm, Long stockTotal) {
		Double rps = live ? PromValues.round(PromValues.single(r.instant("rps")), 1) : null;
		// 5xx 비율 = rate 1m ÷ rate 1m(화면 rps와 따로)
		Double errorRatio = live && r.ok("errorRps") ? PromValues.round(
				TrafficRules.errorRatio(PromValues.single(r.instant("ratioRps")), PromValues.single(r.instant("errorRps"))),
				5) : null;
		Long p95Issuance = null;
		Long p95Ticket = null;
		if (live) {
			for (Sample s : r.instant("p95")) {
				String uri = s.labels().getOrDefault("uri", "");
				if (uri.contains("claim")) {
					p95Issuance = PromValues.millis(s.value());
				}
				else if (uri.contains("ticket")) {
					p95Ticket = PromValues.millis(s.value());
				}
			}
		}
		Long p95 = larger(p95Issuance, p95Ticket);
		// 쿼리가 이미 0 하한(clamp_min) — 아래 0 하한은 안전장치
		Double waiting = live ? PromValues.single(r.instant("waiting")) : null;
		Double used = PromValues.single(r.instant("redisUsed"));
		Double max = PromValues.single(r.instant("redisMax"));
		Double ops = PromValues.single(r.instant("redisOps"));
		Double clients = PromValues.single(r.instant("redisClients"));
		// 연결은 판정과 같은 값(끊긴 지 1분 안 = 확인 중, 1분 이어짐 = 끊김, 정리 중은 연결됨). 끊기면 메모리 등 다른 Redis 지표가 사라지므로
		// 끊김 · 확인 중도 Redis 지표가 있는 것으로 본다
		RedisConnection redis = redis(r);
		// 재시작 수는 kube-state-metrics 값이라 Redis 지표(redis-exporter)가 없어도 0으로 나온다 — 그때는 다른 Redis 칸처럼 비운다
		boolean redisMetrics = used != null || max != null || ops != null || clients != null
				|| redis != RedisConnection.UP;
		RedisConnection connection = redisMetrics && r.allOk("redisDown", "redisDownPoints") ? redis : null;
		return new Live(rps, errorRatio, p95, p95Issuance, p95Ticket,
				PromValues.count(PromValues.single(r.instant("ready")), ksm && r.ok("ready")),
				PromValues.toInt(PromValues.single(r.instant("hpaMax"))),
				PromValues.toInt(PromValues.single(r.instant("hpaMin"))),
				PromValues.count(PromValues.single(r.instant("pending")), ksm && r.ok("pending")),
				PromValues.round(PromValues.single(r.instant("cpu")), 3),
				PromValues.round(PromValues.single(r.instant("cpuRequest")), 3),
				PromValues.single(r.instant("memory")), PromValues.single(r.instant("memoryRequest")),
				waiting == null ? null : Math.max(0, Math.round(waiting)),
				PromValues.toLong(PromValues.single(r.instant("stock"))), stockTotal,
				used == null || max == null || max <= 0 ? null : PromValues.round(used / max, 4), used, max,
				PromValues.round(ops, 1),
				redisMetrics ? PromValues.toLong(PromValues.single(r.instant("redisRestarts"))) : null,
				PromValues.toLong(clients), connection);
	}

	/**
	 * 호스팅이 끝난 이벤트의 최종값(전체 구간 시리즈 + 결과 요약). 집계 구간은 입장 ~ 이벤트 종료(end_at) — 그래프 범위(입장 −10분 ~
	 * 호스팅 종료 +10분)보다 좁다. 입장 전 웜업(이벤트 12: 입장 전 57.9 RPS가 최대 RPS로 잡힘, 2026-10-28) · 종료 뒤 정리는 넣지 않는다.
	 * 발급 = success_count, 없으면 재고 − 최소 남은 재고. 최대값이 0이면 시각 없음(화면 "–").
	 * 시각은 1분 구간의 시작(결과 탭 분당 집계 · 분 단위 표시와 같게) — {@link #requestRateMinute} · {@link #minuteOf} · {@link #changedMinute}.
	 * 최대 RPS = 요청량 식 그래프의 최대, 평균 5xx = Σ 5xx ÷ Σ 요청(둘 다 rate 1m, 비율 분모는 {@link #RATIO_RPS}).
	 * @param from 입장, to 이벤트 종료(end_at). null인 끝은 자르지 않는다
	 */
	static Summary summary(PromSeries series, Instant from, Instant to, Long stockTotal, Long successCount) {
		PromSeries s = series.between(from, to);
		List<Long> t = s.timestamps();
		Peak peakRps = peak(t, s.get(RPS), TrafficEventCollector::requestRateMinute);
		double rpsSum = 0;
		double errorSum = 0;
		boolean anyRps = false;
		for (int i = 0; i < t.size(); i++) {
			Double rps = s.get(RATIO_RPS).get(i);
			if (rps != null) {
				anyRps = true;
				rpsSum += rps;
				Double err = s.get(ERROR_RPS).get(i);
				errorSum += err == null ? 0 : err;
			}
		}
		// 5xx 쿼리가 오류였으면(비율이 모두 null) 평균도 모름
		boolean errorKnown = s.get(ERROR_RATIO).stream().anyMatch(Objects::nonNull);
		Double errorAvg = !anyRps || !errorKnown ? null : rpsSum <= 0 ? 0.0 : PromValues.round(errorSum / rpsSum, 5);
		List<Double> p95All = new ArrayList<>(s.get(P95_ISSUANCE));
		p95All.addAll(s.get(P95_TICKET));
		Double minStock = min(s.get(STOCK));
		Long issued = successCount != null ? successCount
				: stockTotal != null && minStock != null ? Math.max(0, stockTotal - Math.round(minStock)) : null;
		Long soldOutAt = null;
		for (int i = 0; i < t.size(); i++) {
			Double stock = s.get(STOCK).get(i);
			if (stock != null && stock <= 0) {
				soldOutAt = changedMinute(t.get(i));
				break;
			}
		}
		Double redisMemoryMax = max(s.get(REDIS_MEMORY));
		return new Summary(peakRps, errorAvg, max(s.get(ERROR_RATIO)), PromValues.toLong(max(p95All)),
				PromValues.toLong(avg(s.get(P95_ISSUANCE))), PromValues.toInt(max(s.get(READY))),
				PromValues.toInt(max(s.get(HPA_MAX))), peak(t, s.get(WAITING), TrafficEventCollector::minuteOf), issued,
				stockTotal, soldOutAt == null ? null : offset(soldOutAt),
				max(s.get(CPU)), max(s.get(CPU_REQUEST)), max(s.get(MEMORY)), max(s.get(MEMORY_REQUEST)),
				redisMemoryMax,
				// Redis 지표(redis-exporter)가 없으면 재시작도 모름(kube-state-metrics 값 0을 쓰지 않음)
				redisMemoryMax == null ? null : PromValues.toLong(max(s.get(REDIS_RESTARTS))));
	}

	private static final long MINUTE_MILLIS = 60_000;

	/**
	 * 요청량 식 값(t 점 = t − 그래프 간격 ~ t 창 안 1초 값의 최대, 각 1초 값은 바로 앞 수집 간격)의 1분 구간 시작: t 직전(t − 1ms)이 든 분.
	 * 이벤트 20 최대 13:20:15(13:20:00~02 몰림) → 13:20. rate[1m] 때 쓰던 't − 1분이 든 분'이면 13:19로 찍힌다(2026-11-04)
	 */
	static long requestRateMinute(long epochMillis) {
		return minuteOf(epochMillis - 1);
	}

	/** 게이지 값(그 시각의 상태)이 든 분 */
	static long minuteOf(long epochMillis) {
		return Math.floorDiv(epochMillis, MINUTE_MILLIS) * MINUTE_MILLIS;
	}

	/** 처음 바뀐 값으로 보인 점 t: 바뀐 순간은 앞 점 ~ t 사이라 t 직전(t − 1ms)이 든 분(정각 점이면 앞 분) */
	static long changedMinute(long epochMillis) {
		return minuteOf(epochMillis - 1);
	}

	/** 최대값과 그 시각(minute로 1분 구간 시작). 값이 없으면 둘 다 null, 최대가 0이면 시각 null */
	private static Peak peak(List<Long> times, List<Double> values, LongUnaryOperator minute) {
		Double best = null;
		Long at = null;
		for (int i = 0; i < times.size(); i++) {
			Double v = values.get(i);
			if (v != null && (best == null || v > best)) {
				best = v;
				at = times.get(i);
			}
		}
		if (best == null) {
			return new Peak(null, null);
		}
		return new Peak(best, best == 0 ? null : offset(minute.applyAsLong(at)));
	}

	private static OffsetDateTime offset(long epochMillis) {
		return Kst.toOffset(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), Kst.ZONE));
	}

	private static Double max(List<Double> values) {
		return values.stream().filter(Objects::nonNull).max(Double::compare).orElse(null);
	}

	private static Double min(List<Double> values) {
		return values.stream().filter(Objects::nonNull).min(Double::compare).orElse(null);
	}

	private static Double avg(List<Double> values) {
		return values.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().stream()
			.boxed().findFirst().orElse(null);
	}

	private static List<Double> round(List<Double> values, int digits) {
		return values.stream().map(v -> PromValues.round(v, digits)).toList();
	}

	private static List<Double> ms(List<Double> seconds) {
		return PromValues.map(seconds, x -> Math.round(x * 1000));
	}

}

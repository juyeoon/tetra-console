package com.tetra.ops.monitoring;

import java.time.Clock;
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
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.monitoring.PromValues.Grid;
import com.tetra.ops.monitoring.PrometheusClient.Sample;
import com.tetra.ops.monitoring.TrafficEventResponse.Live;
import com.tetra.ops.monitoring.TrafficEventResponse.Peak;
import com.tetra.ops.monitoring.TrafficEventResponse.Summary;
import com.tetra.ops.monitoring.TrafficQueries.EventQueries;
import com.tetra.ops.monitoring.TrafficRules.EventSignals;

/**
 * OPS-05 이벤트 하나의 Prometheus 갱신 1회(C 준비 시간 · D KPI · 그래프). 상태별로 조회하는 것이 다르다(명세 4절):
 * - 입장 직전: 그래프 + Pod · 재고 · Redis · 자원 순간값(요청 처리 · 대기 인원은 조회하지 않음)
 * - 진행 중: 그래프 + KPI 순간값 전부
 * - 종료: 그래프 + 전체 구간(full) 그래프(최종값 계산용, 범위가 full이면 같은 것)
 * 쿼리는 {@link PromBatch}(4개씩 병렬). Prometheus 자체 문제면 갱신 전체 실패, 쿼리 하나의 오류면 그 항목만 null.
 * 결과 가공은 구역(그래프 · 전체 구간 · KPI · 준비 시간)마다 따로 — 한 구역에서 예외가 나면 그 구역만 null, 나머지는 응답(2026-10-28).
 */
@Component
class TrafficEventCollector {

	private static final Logger log = LoggerFactory.getLogger(TrafficEventCollector.class);

	// ---- 그래프 시리즈 이름(모두 같은 격자) ----

	static final String RPS = "rps";

	static final String ERROR_RPS = "errorRps";

	static final String ERROR_RATIO = "errorRatio";

	static final String P95_ISSUANCE = "p95Issuance";

	static final String P95_TICKET = "p95Ticket";

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
	 * @param full 종료 상태의 전체 구간(최종값용). window와 같으면 한 번만 조회. 다른 상태는 null
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
				batch.instant("rps", q.rps(), now)
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
				.instant("redisUp", q.redisUp(), now)
				.instant("exporterUp", q.redisExporterUp(), now)
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
		// 판정 재료가 하나라도 쿼리 오류면 complete = false(위험이 안 보이면 UNKNOWN). 5xx 오류면 rps도 비운다
		boolean complete = r.allOk("ksm", "stock", "ready", "hpaMax", "redisUp", "exporterUp")
				&& (!live || r.allOk("rps", "errorRps", "p95", "cursorChanges", "waiting"));
		EventSignals signals = new EventSignals(live && r.ok("errorRps") ? liveKpi.rps() : null,
				live ? PromValues.single(r.instant("errorRps")) : null,
				liveKpi.p95Ms() == null ? null : liveKpi.p95Ms() / 1000.0,
				live ? PromValues.single(r.instant("cursorChanges")) : null,
				liveKpi.waiting() == null ? null : liveKpi.waiting().doubleValue(),
				liveKpi.stockRemaining() == null ? null : liveKpi.stockRemaining().doubleValue(),
				PromValues.single(r.instant("redisUp")), PromValues.single(r.instant("exporterUp")),
				liveKpi.podsReady(), liveKpi.podsMax(), complete);
		return new Instants(liveKpi, signals);
	}

	private static Long prepareSeconds(PromBatch.Results r) {
		Double v = PromValues.single(r.instant("prepare"));
		return v == null || v < 0 ? null : Math.round(v);
	}

	private static void addRanges(PromBatch batch, String prefix, EventQueries q, Grid g) {
		batch.range(prefix + RPS, q.rps(), g.start(), g.end(), g.step())
			.range(prefix + ERROR_RPS, q.errorRps(), g.start(), g.end(), g.step())
			.range(prefix + "p95", q.p95ByUri(), g.start(), g.end(), g.step())
			.range(prefix + CURSOR, q.cursorPerSec(), g.start(), g.end(), g.step())
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
		List<Double> rps = round(PromValues.onGrid(r.range(prefix + RPS), times, false), 2);
		List<Double> errorRps = PromValues.onGrid(r.range(prefix + ERROR_RPS), times, false);
		v.put(RPS, rps);
		v.put(ERROR_RPS, round(errorRps, 3));
		// 5xx 쿼리가 오류면 비율은 모름(null). "요청만 있고 5xx 없음 = 0"은 쿼리가 성공했을 때만
		boolean errorOk = r.ok(prefix + ERROR_RPS);
		List<Double> ratio = new ArrayList<>(times.size());
		for (int i = 0; i < times.size(); i++) {
			ratio.add(errorOk ? PromValues.round(TrafficRules.errorRatio(rps.get(i), errorRps.get(i)), 5) : null);
		}
		v.put(ERROR_RATIO, ratio);
		v.put(P95_ISSUANCE, ms(PromValues.onGrid(r.range(prefix + "p95"), "uri", "claim", times)));
		v.put(P95_TICKET, ms(PromValues.onGrid(r.range(prefix + "p95"), "uri", "ticket", times)));
		v.put(CURSOR, round(PromValues.onGrid(r.range(prefix + CURSOR), times, false), 2));
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
		Double errorRatio = live && r.ok("errorRps") ? PromValues.round(
				TrafficRules.errorRatio(PromValues.single(r.instant("rps")), PromValues.single(r.instant("errorRps"))), 5)
				: null;
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
		Double waiting = live ? PromValues.single(r.instant("waiting")) : null;
		Double used = PromValues.single(r.instant("redisUsed"));
		Double max = PromValues.single(r.instant("redisMax"));
		Double redisUp = PromValues.single(r.instant("redisUp"));
		Double exporterUp = PromValues.single(r.instant("exporterUp"));
		Boolean up = redisUp == null && exporterUp == null ? null
				: !((redisUp != null && redisUp == 0) || (exporterUp != null && exporterUp == 0));
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
				PromValues.round(PromValues.single(r.instant("redisOps")), 1),
				PromValues.toLong(PromValues.single(r.instant("redisRestarts"))),
				PromValues.toLong(PromValues.single(r.instant("redisClients"))), up);
	}

	/**
	 * 종료된 이벤트 최종값(전체 구간 시리즈 + 결과 요약). 발급 = success_count, 없으면 재고 − 최소 남은 재고.
	 * 품절 시각 = 남은 재고가 처음 0이 된 격자 시각.
	 */
	static Summary summary(PromSeries s, Long stockTotal, Long successCount) {
		List<Long> t = s.timestamps();
		Peak peakRps = peak(t, s.get(RPS));
		double rpsSum = 0;
		double errorSum = 0;
		boolean anyRps = false;
		for (int i = 0; i < t.size(); i++) {
			Double rps = s.get(RPS).get(i);
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
				soldOutAt = t.get(i);
				break;
			}
		}
		return new Summary(peakRps, errorAvg, max(s.get(ERROR_RATIO)), PromValues.toLong(max(p95All)),
				PromValues.toLong(avg(s.get(P95_ISSUANCE))), PromValues.toInt(max(s.get(READY))),
				PromValues.toInt(max(s.get(HPA_MAX))), peak(t, s.get(WAITING)), issued, stockTotal,
				soldOutAt == null ? null : offset(soldOutAt),
				max(s.get(CPU)), max(s.get(CPU_REQUEST)), max(s.get(MEMORY)), max(s.get(MEMORY_REQUEST)),
				max(s.get(REDIS_MEMORY)), PromValues.toLong(max(s.get(REDIS_RESTARTS))));
	}

	private static Peak peak(List<Long> times, List<Double> values) {
		Double best = null;
		Long at = null;
		for (int i = 0; i < times.size(); i++) {
			Double v = values.get(i);
			if (v != null && (best == null || v > best)) {
				best = v;
				at = times.get(i);
			}
		}
		return best == null ? new Peak(null, null)
				: new Peak(best, offset(at));
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

package com.tetra.ops.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import com.tetra.ops.monitoring.PrometheusClient.Sample;
import com.tetra.ops.monitoring.PrometheusClient.Series;

/** OPS-05 Prometheus 결과 다듬기(시각 격자 · 라벨별 값 · 반올림). 시리즈가 없으면 null이 원칙 */
final class PromValues {

	private PromValues() {
	}

	/** 같은 격자(step 배수)로 맞춘 구간. 모든 range 쿼리가 같은 시각을 쓰게 한다 */
	record Grid(Instant start, Instant end, Duration step) {

		static Grid of(Instant from, Instant to, Duration step) {
			long stepMillis = step.toMillis();
			Instant end = Instant.ofEpochMilli(Math.floorDiv(to.toEpochMilli(), stepMillis) * stepMillis);
			Instant start = Instant.ofEpochMilli(Math.floorDiv(from.toEpochMilli(), stepMillis) * stepMillis);
			return new Grid(start.isAfter(end) ? end : start, end, step);
		}

		List<Long> timestamps() {
			List<Long> times = new ArrayList<>();
			for (long t = start.toEpochMilli(); t <= end.toEpochMilli(); t += step.toMillis()) {
				times.add(t);
			}
			return times;
		}

	}

	static Double single(List<Sample> samples) {
		return samples.isEmpty() ? null : samples.get(0).value();
	}

	static Map<String, Double> byLabel(List<Sample> samples, String label) {
		Map<String, Double> values = new HashMap<>();
		for (Sample s : samples) {
			String key = s.labels().get(label);
			if (key != null && s.value() != null) {
				values.merge(key, s.value(), Double::sum);
			}
		}
		return values;
	}

	/** 시리즈 하나(sum · max 등 라벨 없는 집계)를 격자 값 배열로. 시리즈가 없으면 모두 null, missingIsZero면 모두 0 */
	static List<Double> onGrid(List<Series> series, List<Long> grid, boolean missingIsZero) {
		Map<Long, Double> values = new HashMap<>();
		if (!series.isEmpty()) {
			series.get(0).points().forEach(p -> values.put(p.epochMillis(), p.value()));
		}
		List<Double> out = new ArrayList<>(grid.size());
		for (Long t : grid) {
			Double v = values.get(t);
			out.add(v == null && missingIsZero ? Double.valueOf(0) : v);
		}
		return out;
	}

	/** 라벨 값이 contains인 시리즈 하나를 격자로(예: uri에 "claim") */
	static List<Double> onGrid(List<Series> series, String label, String contains, List<Long> grid) {
		List<Series> matched = series.stream()
			.filter(s -> s.labels().getOrDefault(label, "").contains(contains))
			.limit(1)
			.toList();
		return onGrid(matched, grid, false);
	}

	static List<Double> map(List<Double> values, DoubleUnaryOperator op) {
		List<Double> out = new ArrayList<>(values.size());
		for (Double v : values) {
			out.add(v == null ? null : op.applyAsDouble(v));
		}
		return out;
	}

	static Integer toInt(Double v) {
		return v == null ? null : (int) Math.round(v);
	}

	static Long toLong(Double v) {
		return v == null ? null : Math.round(v);
	}

	/** 개수: kube-state-metrics 응답이 있으면 시리즈 없음 = 0, 응답 자체가 없으면 null */
	static Integer count(Double v, boolean missingIsZero) {
		if (v == null) {
			return missingIsZero ? 0 : null;
		}
		return toInt(v);
	}

	static Double round(Double v, int digits) {
		if (v == null) {
			return null;
		}
		double scale = Math.pow(10, digits);
		return Math.round(v * scale) / scale;
	}

	/** 초 → ms 정수 */
	static Long millis(Double seconds) {
		return seconds == null ? null : Math.round(seconds * 1000);
	}

	/** 숫자만인 라벨 값 → Long(아니면 null) */
	static Long parseId(String value) {
		if (value == null || value.isEmpty() || value.length() > 10 || !value.chars().allMatch(Character::isDigit)) {
			return null;
		}
		return Long.parseLong(value);
	}

}

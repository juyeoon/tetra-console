package com.tetra.ops.monitoring;

import java.time.Duration;

/**
 * 요청량 식(2026-11-04 결정, Grafana 이벤트 대시보드와 같은 식 — 조사 `번호표_순간RPS_조사_1104.md` 4절). 홈 · OPS-05가 같이 쓴다.
 *
 * X = Pod별 '마지막 수집 간격의 평균 req/s' + 처음 생긴 시리즈의 첫 값 ÷ 그 Pod의 수집 간격.
 * - irate: 마지막 두 샘플의 차 ÷ 실제 간격(카운터 리셋 처리). 1초 수집이면 초당, 15초 수집이면 15초 평균(부풀지 않음)
 * - or 뒤: 지금 처음 생긴 시리즈(샘플이 하나뿐이라 irate가 없다)의 첫 값 ÷ 그 Pod의 수집 간격(process_uptime_seconds의 마지막 차).
 *   rate[1m]은 새 시리즈의 첫 값을 버리고 몰림을 1분에 묻는다 — 이벤트 20의 번호표 1만 건이 0.5 req/s로 보였다(13:20:15)
 * - 새 Pod의 첫 수집(웜업 등)은 간격을 몰라 빠진다. 다음 수집부터 센다
 *
 * 화면 값은 {@link #peak}: 창 안 1초 점의 최대. 창 = 그래프 간격이면 1초 값이 빠짐없이 어느 한 점에 들어간다.
 * 수집이 15초면 X가 다음 수집까지 그대로라 몰림이 이웃 두 점에 같은 값으로 보일 수 있다(1초 수집이면 1초 이하).
 *
 * 5xx 비율(판정 · KPI · 에러 그래프 · 평균 5xx)은 이 식을 쓰지 않는다 — 분모 · 분자 모두 rate 1m 그대로(2026-11-04 확정 4).
 */
final class RequestRate {

	/** irate · 수집 간격을 보는 창. 마지막 두 샘플이 들어가면 되고, 15초 수집에서도 넉넉하다 */
	static final String IRATE_WINDOW = "1m";

	/** {@link #peak}의 서브쿼리 해상도. 수집 간격과 상관없이 1초 값이 다 보인다 */
	static final String PEAK_RESOLUTION = "1s";

	private RequestRate() {
	}

	/**
	 * Pod별 X를 {@code by}로 합친 것.
	 * @param selector http_server_requests_seconds_count 라벨 조건(쉼표로 이은 것, 검증한 값만)
	 * @param uptime process_uptime_seconds 라벨 조건(같은 이벤트 · 테넌트로 좁힘, 비면 전체)
	 * @param by 합칠 라벨(pod 포함 — 예: "pod", "pod, tenant_id")
	 */
	static String byPod(String selector, String uptime, String by) {
		String req = "http_server_requests_seconds_count{" + selector + "}";
		String interval = "idelta(process_uptime_seconds" + (uptime.isEmpty() ? "" : "{" + uptime + "}") + "[" + IRATE_WINDOW
				+ "])";
		return "sum by (" + by + ") (irate(" + req + "[" + IRATE_WINDOW + "]) or ((" + req + " unless " + req + " offset "
				+ IRATE_WINDOW + ") / on(pod) group_left() " + interval + "))";
	}

	/** 창(window) 안 1초 점의 최대. expr은 이미 합친 식(sum · sum by) */
	static String peak(String expr, Duration window) {
		return "max_over_time((" + expr + ")[" + TrafficQueries.promDuration(window) + ":" + PEAK_RESOLUTION + "])";
	}

	/**
	 * 구간(window) 동안 늘어난 카운터. 구간 시작에 없던 시리즈는 increase 대신 지금 값(0부터 늘어난 양)을 쓴다 —
	 * increase는 시리즈가 생길 때 이미 붙어 있던 첫 값을 세지 못한다(이벤트 20: 13:20에 생긴 시리즈의 첫 값 10,940건이 빠짐).
	 * 구간 시작에 있었는지는 offset으로 본다(그 시각 기준 5분 lookback 안에 점이 있으면 있던 시리즈).
	 * 구간 안에 생겼다가 지금은 사라진 시리즈(끝난 Pod)는 첫 값이 여전히 빠진다.
	 * @param series 카운터 선택자(이름 + 라벨)
	 * @param window PromQL 기간 문자열(예: "1m", "3900s")
	 */
	static String increaseWithBorn(String series, String window) {
		String born = "(" + series + " unless " + series + " offset " + window + ")";
		return "(increase(" + series + "[" + window + "]) unless " + born + ") or " + born;
	}

}

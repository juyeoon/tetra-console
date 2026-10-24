package com.tetra.ops.event;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 승인 전 검사 규칙(3-13, 순수 로직). 시각은 모두 KST LocalDateTime.
 * 위반하면 사유 코드 → 화면 문구를 모아 409 APPLICATION_DATA_INVALID의 fields로 돌려준다.
 * WF1-2가 승인 뒤 같은 입력을 검사하다 실패하면(DEPLOY_FAILED) 되돌릴 수 없으므로, 승인 전에 미리 막는다.
 */
final class ApproveRules {

	/**
	 * event-open 스케줄(WF2-2, T−90) = 이벤트 시작 − 90분. Redis도 이때 뜬다(scaledUp).
	 * ops-workflows wf1-2/EventRegister.asl.json Init의 inputErrors(5400000ms) · eventOpenScheduleAt과 같은 값.
	 */
	static final Duration HOSTING_LEAD_BEFORE_START = Duration.ofMinutes(90);

	/** WF2-1(호스팅 시작)과 WF2-2(T−90)가 같은 시각에 돌지 않게 두는 간격 */
	static final Duration SCHEDULE_GAP = Duration.ofMinutes(10);

	/** 승인 뒤 WF1-2가 끝날 때까지 걸릴 수 있는 시간. ops-workflows wf1-2/EventRegister.asl.json TimeoutSeconds 1800 */
	static final Duration APPROVE_MIN_LEAD = Duration.ofMinutes(30);

	static final String HOSTING_START_TOO_LATE = "HOSTING_START_TOO_LATE";

	static final String HOSTING_START_TOO_SOON = "HOSTING_START_TOO_SOON";

	static final String NAME_BLANK = "NAME_BLANK";

	static final String ENDPOINT_URL_INVALID = "ENDPOINT_URL_INVALID";

	static final String CONCURRENT_EVENT_LIMIT = "CONCURRENT_EVENT_LIMIT";

	private static final DateTimeFormatter MONTH_DAY_TIME = DateTimeFormatter.ofPattern("MM.dd HH:mm");

	private ApproveRules() {
	}

	/**
	 * 신청 데이터 규칙. 호스팅 시작이 지났으면 HOSTING_START_TOO_SOON에 포함된다(지금 + 30분보다 이르므로).
	 * @return 사유 코드 → 문구(위반 순서대로). 비었으면 통과
	 */
	static Map<String, String> dataReasons(String name, String endpointUrl, LocalDateTime hostingStartAt,
			LocalDateTime startAt, LocalDateTime now) {
		Map<String, String> reasons = new LinkedHashMap<>();
		Duration lead = HOSTING_LEAD_BEFORE_START.plus(SCHEDULE_GAP);
		if (hostingStartAt.isAfter(startAt.minus(lead))) {
			reasons.put(HOSTING_START_TOO_LATE, "호스팅 시작은 이벤트 시작 " + lead.toMinutes() + "분 전까지여야 합니다. (호스팅 시작 "
					+ format(hostingStartAt) + " · 이벤트 시작 " + format(startAt) + ")");
		}
		if (hostingStartAt.isBefore(now.plus(APPROVE_MIN_LEAD))) {
			reasons.put(HOSTING_START_TOO_SOON, "호스팅 시작까지 " + APPROVE_MIN_LEAD.toMinutes()
					+ "분 이상 남아 있어야 승인할 수 있습니다. (호스팅 시작 " + format(hostingStartAt) + ")");
		}
		if (name == null || name.isBlank()) {
			reasons.put(NAME_BLANK, "이벤트 이름이 비어 있습니다.");
		}
		if (!isHttpUrl(endpointUrl)) {
			reasons.put(ENDPOINT_URL_INVALID, "결과를 받을 주소가 비어 있거나 http(s) 주소가 아닙니다.");
		}
		return reasons;
	}

	/** Portal 신청 API(ApplyService.isHttpUrl)와 같은 규칙: http · https, 호스트에 점이 있고 점으로 시작 · 끝나지 않음 */
	static boolean isHttpUrl(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		try {
			URI uri = new URI(value.strip());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
					&& host != null && host.contains(".") && !host.startsWith(".") && !host.endsWith(".");
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	/** 이벤트가 Redis 노드를 쓰는 구간(닫힌 구간): event-open 스케줄(시작 − 90분)부터 WF4가 자원을 내리기 시작하는 hosting_end까지 */
	record Window(LocalDateTime from, LocalDateTime to) {

		boolean overlaps(Window other) {
			return !from.isAfter(other.to) && !other.from.isAfter(to);
		}

	}

	static Window redisWindow(LocalDateTime startAt, LocalDateTime hostingEndAt) {
		return new Window(startAt.minus(HOSTING_LEAD_BEFORE_START), hostingEndAt);
	}

	/** 동시 수가 가장 많은 시점과 그 수 */
	record Peak(int count, LocalDateTime at) {
	}

	/**
	 * target 구간 안 어느 시점에서든 동시에 떠 있는 이벤트 수(target 포함)의 최댓값.
	 * 닫힌 구간이라 한 구간의 끝과 다른 구간의 시작이 같은 시각이면 겹친 것으로 센다(같은 시각이면 시작을 먼저 더한다).
	 */
	static Peak peak(Window target, List<Window> others) {
		record Point(LocalDateTime at, int delta) {
		}
		List<Point> points = new ArrayList<>();
		for (Window w : others) {
			if (w.overlaps(target)) {
				points.add(new Point(w.from.isBefore(target.from) ? target.from : w.from, 1));
				points.add(new Point(w.to, -1));
			}
		}
		points.sort(Comparator.comparing(Point::at).thenComparing(Point::delta, Comparator.reverseOrder()));
		int count = 1;
		Peak peak = new Peak(1, target.from);
		for (Point p : points) {
			count += p.delta();
			if (count > peak.count()) {
				peak = new Peak(count, p.at());
			}
		}
		return peak;
	}

	/** 동시 이벤트 상한(Redis 노드 수)을 넘으면 문구 */
	static Optional<String> capacityReason(Window target, List<Window> others, int limit) {
		Peak peak = peak(target, others);
		if (peak.count() <= limit) {
			return Optional.empty();
		}
		return Optional.of("같은 기간에 진행되는 이벤트가 최대 " + limit + "개를 넘습니다. (" + format(peak.at()) + "에 "
				+ peak.count() + "개)");
	}

	static String format(LocalDateTime value) {
		return MONTH_DAY_TIME.format(value);
	}

}

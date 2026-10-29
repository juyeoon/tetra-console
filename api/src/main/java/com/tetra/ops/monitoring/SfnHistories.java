package com.tetra.ops.monitoring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.tetra.ops.monitoring.AwsMonitoringGateway.ExecutionHistory;
import com.tetra.ops.monitoring.AwsMonitoringGateway.HistoryEvent;

/**
 * Step Functions 실행 기록(GetExecutionHistory, includeExecutionData=false)을 화면용 단계 행으로 줄인다(OPS-05 자동화 실행 상세, 2026-10-29).
 * - 짝짓기: *StateExited에서 previousEventId를 거꾸로 따라가 같은 이름의 *StateEntered를 찾는다. Parallel 가지 · Map 항목이
 *   섞여도 맞는다(dev approve-9 · approve-13 · 이벤트 13 WF2-2 · WF4 기록에서 짝 못 찾음 0건). Task 재시도 = 그 사이 TaskScheduled 수 − 1
 * - 같은 이름은 한 행(처음 들어간 순서): count = 들어간 횟수, 시작 = 처음 들어간 시각, 끝 = 마지막으로 나온 시각
 * - 반복: Wait에 두 번 이상 들어갔으면 첫 진입과 두 번째 진입 사이에 처음 들어간 행(횟수가 Wait − 1 이상)을 묶어 LOOP 한 행,
 *   count = Wait 진입 수(앱 확인 30초 × 최대 40회 등). Map 항목처럼 Wait 없는 반복은 count만
 * - 끝나지 않은 진입: 실행 중이면 RUNNING(끝 null), 실행이 끝났으면 FAILED(끝 = 실행 끝)
 * - 실패 단계: Fail 상태 Cause가 "<실패한 상태 이름>: <원래 cause>"(ops-workflows 규칙)라 그 접두어(지나간 상태 이름일 때만),
 *   없으면(시간 초과 · 중단) 끝나지 않은 마지막 상태. 그 상태가 든 행은 FAILED
 * - Pass · Choice · Succeed 행은 0초라 빼고(실패 단계면 남김, 반복 안의 이름은 LOOP names에 남음), Fail 행은 실패 정보(error)로 대신한다
 * - 원인은 {@link FailureReasons#mask}로 가린다: reason = 요약 120자, cause = 원문 {@link #CAUSE_MAX}자
 */
final class SfnHistories {

	static final int CAUSE_MAX = 500;

	/** Fail 상태 Cause 앞의 "<실패한 상태 이름>: " */
	private static final Pattern FAILED_STATE = Pattern.compile("^([A-Za-z0-9_.-]{1,80}): ");

	/** 화면에서 빼는 0초 행(실패 단계가 아니면) */
	private static final Set<Kind> HIDDEN = Set.of(Kind.PASS, Kind.CHOICE, Kind.SUCCEED);

	enum Kind {

		TASK, WAIT, CHOICE, PASS, PARALLEL, MAP, SUCCEED, FAIL, LOOP

	}

	enum RowStatus {

		SUCCEEDED, FAILED, RUNNING

	}

	/** 단계 한 줄. LOOP면 names = 반복 묶음(Wait부터), count = 반복 횟수. endedAt은 진행 중이면 null */
	record Row(Kind kind, List<String> names, int count, Instant startedAt, Instant endedAt, int retries,
			RowStatus status) {
	}

	/** state = 실패한 단계(모르면 null), reason = 요약(120자), cause = 가린 원문(500자) */
	record Failure(String state, String error, String reason, String cause) {
	}

	/** status = 기록 기준 실행 상태(기록이 잘렸으면 목록 값), stoppedAt = 끝난 시각(진행 중이면 null), failure는 실패 · 시간 초과 · 중단만 */
	record Summary(String status, Instant stoppedAt, Failure failure, List<Row> rows, boolean truncated) {
	}

	private SfnHistories() {
	}

	static Summary summarize(ExecutionHistory history, String listedStatus, Instant listedStopDate) {
		List<HistoryEvent> events = history.events();

		// 실행 상태: 마지막 실행 수준 이벤트. 재실행(redrive)이면 다시 RUNNING부터
		String status = listedStatus;
		Instant stoppedAt = listedStopDate;
		HistoryEvent ended = null;
		for (HistoryEvent e : events) {
			switch (e.type()) {
				case "ExecutionStarted", "ExecutionRedriven" -> {
					status = "RUNNING";
					stoppedAt = null;
					ended = null;
				}
				case "ExecutionSucceeded" -> {
					status = "SUCCEEDED";
					stoppedAt = e.timestamp();
					ended = null;
				}
				case "ExecutionFailed", "ExecutionTimedOut", "ExecutionAborted" -> {
					status = switch (e.type()) {
						case "ExecutionFailed" -> "FAILED";
						case "ExecutionTimedOut" -> "TIMED_OUT";
						default -> "ABORTED";
					};
					stoppedAt = e.timestamp();
					ended = e;
				}
				default -> {
				}
			}
		}
		if (history.truncated()) {
			// 뒤쪽 기록이 없으니 상태는 목록 값
			status = listedStatus;
			stoppedAt = listedStopDate;
		}
		boolean running = "RUNNING".equals(status);

		List<Visit> visits = visits(events);
		for (Visit v : visits) {
			if (v.end == null && !running) {
				v.end = stoppedAt;
				v.failed = true;
			}
		}

		Failure failure = null;
		if (!running && !"SUCCEEDED".equals(status)) {
			failure = failure(ended, visits);
		}
		String failedState = failure == null ? null : failure.state();

		Map<String, List<Visit>> byName = new LinkedHashMap<>();
		visits.forEach(v -> byName.computeIfAbsent(v.name, k -> new ArrayList<>()).add(v));
		List<List<Visit>> groups = new ArrayList<>(byName.values());

		List<Row> rows = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			List<Visit> head = groups.get(i);
			if (head.get(0).kind != Kind.WAIT || head.size() < 2) {
				rows.add(row(head.get(0).kind, List.of(head), failedState));
				continue;
			}
			// 반복: 첫 진입 ~ 두 번째 진입 사이에 처음 들어간 행이 몸통
			long second = head.get(1).enteredId;
			List<List<Visit>> members = new ArrayList<>(List.of(head));
			List<List<Visit>> others = new ArrayList<>();
			int j = i + 1;
			while (j < groups.size()) {
				List<Visit> g = groups.get(j);
				if (g.get(0).enteredId > second || g.get(0).kind == Kind.WAIT) {
					break;
				}
				(g.size() >= head.size() - 1 ? members : others).add(g);
				j++;
			}
			rows.add(row(Kind.LOOP, members, failedState));
			others.forEach(g -> rows.add(row(g.get(0).kind, List.of(g), failedState)));
			i = j - 1;
		}
		List<Row> shown = rows.stream()
			.filter(r -> r.kind() != Kind.FAIL)
			.filter(r -> !HIDDEN.contains(r.kind()) || r.status() == RowStatus.FAILED)
			.toList();
		return new Summary(status, stoppedAt, failure, shown, history.truncated());
	}

	/** 진입마다 하나(진입 순서). 끝 = 짝인 나옴 시각(없으면 null) */
	private static List<Visit> visits(List<HistoryEvent> events) {
		Map<Long, HistoryEvent> byId = new HashMap<>();
		Map<Long, Visit> byEntered = new LinkedHashMap<>();
		for (HistoryEvent e : events) {
			byId.put(e.id(), e);
			if (entered(e)) {
				byEntered.put(e.id(), new Visit(e.stateName(), kindOf(e.type()), e.id(), e.timestamp()));
			}
		}
		for (HistoryEvent e : events) {
			if (!e.type().endsWith("StateExited") || e.stateName() == null) {
				continue;
			}
			int scheduled = 0;
			HistoryEvent p = byId.get(e.previousEventId());
			while (p != null && !(entered(p) && e.stateName().equals(p.stateName()))) {
				if ("TaskScheduled".equals(p.type())) {
					scheduled++;
				}
				p = byId.get(p.previousEventId());
			}
			Visit v = p == null ? null : byEntered.get(p.id());
			if (v == null || v.end != null) {
				// 짝 없음(기록이 잘림)
				continue;
			}
			v.end = e.timestamp();
			if (v.kind == Kind.TASK) {
				v.retries = Math.max(0, scheduled - 1);
			}
		}
		return new ArrayList<>(byEntered.values());
	}

	private static boolean entered(HistoryEvent e) {
		return e.type().endsWith("StateEntered") && e.stateName() != null;
	}

	private static Failure failure(HistoryEvent ended, List<Visit> visits) {
		String error = ended == null ? null : ended.error();
		String cause = ended == null ? null : ended.cause();
		Set<String> names = visits.stream().map(v -> v.name).collect(Collectors.toSet());
		String state = null;
		String rest = cause;
		if (cause != null) {
			Matcher m = FAILED_STATE.matcher(cause);
			if (m.find() && names.contains(m.group(1))) {
				state = m.group(1);
				rest = cause.substring(m.end());
			}
		}
		if (state == null) {
			state = lastUnfinished(visits);
		}
		String summary = error == null || error.isBlank() ? rest : rest == null ? error : error + " / " + rest;
		return new Failure(state, error == null ? null : FailureReasons.mask(error), FailureReasons.summarize(summary),
				cause == null ? null : truncate(FailureReasons.mask(cause).strip()));
	}

	/** 끝나지 않은 마지막 진입(Fail 제외, Parallel · Map보다 안쪽 상태 먼저) */
	private static String lastUnfinished(List<Visit> visits) {
		String container = null;
		for (int i = visits.size() - 1; i >= 0; i--) {
			Visit v = visits.get(i);
			if (!v.failed || v.kind == Kind.FAIL) {
				continue;
			}
			if (v.kind != Kind.PARALLEL && v.kind != Kind.MAP) {
				return v.name;
			}
			if (container == null) {
				container = v.name;
			}
		}
		return container;
	}

	private static Row row(Kind kind, List<List<Visit>> members, String failedState) {
		List<String> names = members.stream().map(g -> g.get(0).name).toList();
		List<Visit> all = members.stream().flatMap(List::stream).toList();
		boolean failed = all.stream().anyMatch(v -> v.failed) || names.contains(failedState);
		boolean running = all.stream().anyMatch(v -> v.end == null);
		Instant start = all.stream().map(v -> v.start).min(Instant::compareTo).orElse(null);
		Instant end = running ? null : all.stream().map(v -> v.end).max(Instant::compareTo).orElse(null);
		int retries = all.stream().mapToInt(v -> v.retries).sum();
		RowStatus status = failed ? RowStatus.FAILED : running ? RowStatus.RUNNING : RowStatus.SUCCEEDED;
		return new Row(kind, names, members.get(0).size(), start, end, retries, status);
	}

	private static Kind kindOf(String enteredType) {
		return switch (enteredType) {
			case "WaitStateEntered" -> Kind.WAIT;
			case "ChoiceStateEntered" -> Kind.CHOICE;
			case "PassStateEntered" -> Kind.PASS;
			case "ParallelStateEntered" -> Kind.PARALLEL;
			case "MapStateEntered" -> Kind.MAP;
			case "SucceedStateEntered" -> Kind.SUCCEED;
			case "FailStateEntered" -> Kind.FAIL;
			default -> Kind.TASK;
		};
	}

	private static String truncate(String text) {
		return text.length() <= CAUSE_MAX ? text : text.substring(0, CAUSE_MAX - 1).stripTrailing() + "…";
	}

	/** 상태 진입 한 번 */
	private static final class Visit {

		private final String name;

		private final Kind kind;

		private final long enteredId;

		private final Instant start;

		private Instant end;

		private int retries;

		/** 실행이 끝났는데 나오지 못함 */
		private boolean failed;

		Visit(String name, Kind kind, long enteredId, Instant start) {
			this.name = name;
			this.kind = kind;
			this.enteredId = enteredId;
			this.start = start;
		}

	}

}

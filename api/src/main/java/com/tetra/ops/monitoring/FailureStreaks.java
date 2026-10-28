package com.tetra.ops.monitoring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

/**
 * 예상 밖 예외(코드 버그 등) WARN. 같은 곳(key)에서 이어지면 스택 트레이스는 처음 한 번만, 이후는 한 줄에 연속 횟수(2026-10-28).
 * 그곳이 성공하면 처음부터 다시 센다. 실패 중인 곳만 들고 있다 — Pod마다 따로 두는 로그 상태(공유 상태 아님).
 * 갱신 1회의 성공 · 실패는 {@link RefreshCache}가 따로 센다. 이건 갱신은 성공했지만 일부만 비운 경우(쿼리 하나 · 가공 구역 하나)용.
 */
final class FailureStreaks {

	private final Map<String, Integer> streaks = new ConcurrentHashMap<>();

	/** key에서 실패 한 번 — "what(detail) — outcome". 쿼리 원문 · 응답 본문은 넣지 않는다 */
	void warn(Logger log, String key, String what, String detail, String outcome, Throwable error) {
		int streak = streaks.merge(key, 1, Integer::sum);
		if (streak == 1) {
			log.warn("{}({}) — {}", what, detail, outcome, error);
		}
		else {
			log.warn("{}({} · 연속 {}번째) — {}", what, detail, streak, outcome);
		}
	}

	void succeeded(String key) {
		streaks.remove(key);
	}

}

package com.tetra.ops.monitoring;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 1분 주기 확인이 보는 DLQ 하나(MONITORING_DLQ_NAMES). 종류는 이름 접미사로, 결과 반영 실패의 결과 종류는 그 앞 이름으로 정한다
 * (ops-workflows terraform 이름 규칙 tetra-{env}-…). 어디에도 맞지 않는 이름은 알림 대상이 아니다.
 * @param resultLabel 결과 반영 실패(RESULT)일 때만 — 테넌트 환경 준비 · 이벤트 등록 · 이벤트 수명 주기
 */
public record DlqQueue(String name, Kind kind, String resultLabel) {

	public enum Kind {

		/** {…}-{tenant|approve|event-lifecycle}-result-dlq: Console 결과 리스너가 처리하지 못한 결과 메시지(결과 반영 실패) */
		RESULT,
		/** {…}-wf-events-dlq: EventBridge가 실행 결과를 결과 큐로 보내지 못함(결과 전달 실패) */
		DELIVERY,
		/** {…}-scheduler-dlq: EventBridge Scheduler가 예약된 워크플로우를 시작하지 못함(예약 실행 실패) */
		SCHEDULER

	}

	private static final Map<String, String> RESULT_LABELS = new LinkedHashMap<>();

	static {
		RESULT_LABELS.put("-tenant-result-dlq", "테넌트 환경 준비");
		RESULT_LABELS.put("-approve-result-dlq", "이벤트 등록");
		RESULT_LABELS.put("-event-lifecycle-result-dlq", "이벤트 수명 주기");
	}

	static Optional<DlqQueue> classify(String name) {
		for (Map.Entry<String, String> result : RESULT_LABELS.entrySet()) {
			if (name.endsWith(result.getKey())) {
				return Optional.of(new DlqQueue(name, Kind.RESULT, result.getValue()));
			}
		}
		if (name.endsWith("-wf-events-dlq")) {
			return Optional.of(new DlqQueue(name, Kind.DELIVERY, null));
		}
		if (name.endsWith("-scheduler-dlq")) {
			return Optional.of(new DlqQueue(name, Kind.SCHEDULER, null));
		}
		return Optional.empty();
	}

}

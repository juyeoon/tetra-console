package com.tetra.ops.monitoring;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;

/**
 * OPS-05 상태 판정(명세 2절, 초안 — 경보 8장 임계값, 부하 시험 뒤 조정). 판정은 백엔드 한 곳에서만 한다(상태 요약 · 드롭다운 기본 선택 ·
 * KPI 주의 표시가 같은 기준을 쓰고, 뒤에 붙일 경보도 이 기준을 쓴다). 화면은 level · reason · warn을 그대로 그린다.
 * 임계값은 상수(설정으로 빼지 않음, 2026-10-26 결정).
 */
final class TrafficRules {

	// ---- 이벤트 ----

	static final double ERROR_RATIO_DANGER = 0.02;

	/** 설계 D-2 확정(2026-10-26): 주의 1% · 위험 2%. 화면 KPI 문구 · 에러 그래프 기준선(web trafficMonitoringView ERROR_RATIO_*)도 같은 값 */
	static final double ERROR_RATIO_WARN = 0.01;

	static final double P95_DANGER_SECONDS = 2.0;

	/** TODO(미정): 부하 시험 뒤 확정(초안 500ms) */
	static final double P95_WARN_SECONDS = 0.5;

	static final double REDIS_MEMORY_WARN = 0.8;

	// ---- 클러스터 · 플랫폼 ----

	static final int SYSTEM_NODES_MIN = 2;

	static final double RDS_CPU_WARN_PERCENT = 80;

	// ---- 자동화 ----

	static final Duration AUTOMATION_FAILURE_WINDOW = Duration.ofHours(24);

	static final Duration STEP_DELAY = Duration.ofMinutes(10);

	record Judgment(MonitoringLevel level, String reason) {

		static final Judgment NO_DATA = new Judgment(MonitoringLevel.UNKNOWN, "데이터 없음");

	}

	/**
	 * 이벤트 판정 재료(Prometheus 순간값). 시리즈가 없으면 null.
	 * complete = 재료 쿼리가 모두 성공(false면 일부 쿼리 오류로 비어 있음 — 위험이 보이지 않으면 UNKNOWN).
	 * 5xx 쿼리가 실패하면 rps도 null로 넣는다(요청만 있고 5xx 없음 = 0 규칙이 실패를 0으로 바꾸지 않게).
	 */
	record EventSignals(Double rps, Double errorRps, Double p95Seconds, Double cursorChanges, Double waiting,
			Double stock, Double redisUp, Double exporterUp, Integer apiReady, Integer hpaMax, boolean complete) {

		EventSignals(Double rps, Double errorRps, Double p95Seconds, Double cursorChanges, Double waiting, Double stock,
				Double redisUp, Double exporterUp, Integer apiReady, Integer hpaMax) {
			this(rps, errorRps, p95Seconds, cursorChanges, waiting, stock, redisUp, exporterUp, apiReady, hpaMax, true);
		}

		/** 5xx 비율. 요청 시리즈가 있고 5xx만 없으면 0, 요청 시리즈가 없으면 null */
		Double errorRatio() {
			return TrafficRules.errorRatio(rps, errorRps);
		}

		boolean redisDown() {
			return isZero(redisUp) || isZero(exporterUp);
		}

		boolean podsAtMax() {
			return apiReady != null && hpaMax != null && hpaMax > 0 && apiReady >= hpaMax;
		}

	}

	private TrafficRules() {
	}

	static Double errorRatio(Double rps, Double errorRps) {
		if (rps == null) {
			return null;
		}
		if (rps <= 0) {
			return 0.0;
		}
		return (errorRps == null ? 0 : errorRps) / rps;
	}

	private static boolean isZero(Double v) {
		return v != null && v == 0;
	}

	/**
	 * 진행 중(OPEN) 이벤트 하나.
	 * 위험: 5xx 2% 이상 · p95 2초 이상 · 커서 1분 정지(시작 뒤, 대기 · 재고 있을 때) · Redis 연결 끊김(redis_up = 0 또는 exporter up = 0).
	 * 주의: 5xx 1% 이상 · p95 500ms 이상 · Pod = KEDA 최대.
	 * 순서: 보이는 위험 → 재료 일부가 비었으면 UNKNOWN(위험을 배제할 수 없음) → 주의 → 정상.
	 * @param started 지금 ≥ 시작 시각(그전에는 커서가 움직이지 않는 게 정상)
	 */
	static Judgment event(EventSignals s, boolean started) {
		if (s == null) {
			return Judgment.NO_DATA;
		}
		Double ratio = s.errorRatio();
		if (s.redisDown()) {
			return new Judgment(MonitoringLevel.DANGER, "Redis 연결 끊김");
		}
		if (ratio != null && ratio >= ERROR_RATIO_DANGER) {
			return new Judgment(MonitoringLevel.DANGER, "5xx " + percent(ratio));
		}
		if (s.p95Seconds() != null && s.p95Seconds() >= P95_DANGER_SECONDS) {
			return new Judgment(MonitoringLevel.DANGER, "p95 " + latency(s.p95Seconds()));
		}
		if (started && cursorStalled(s)) {
			return new Judgment(MonitoringLevel.DANGER, "커서 1분 정지");
		}
		if (!s.complete()) {
			return Judgment.NO_DATA;
		}
		if (ratio != null && ratio >= ERROR_RATIO_WARN) {
			return new Judgment(MonitoringLevel.WARN, "5xx " + percent(ratio));
		}
		if (s.p95Seconds() != null && s.p95Seconds() >= P95_WARN_SECONDS) {
			return new Judgment(MonitoringLevel.WARN, "p95 " + latency(s.p95Seconds()));
		}
		if (s.podsAtMax()) {
			return new Judgment(MonitoringLevel.WARN, "Pod 최대 " + s.apiReady() + "/" + s.hpaMax());
		}
		return new Judgment(MonitoringLevel.OK, "정상");
	}

	/** 입장 직전(T−10분 ~ 입장): Redis · Pod만 본다 */
	static Judgment preEntry(EventSignals s) {
		if (s == null) {
			return Judgment.NO_DATA;
		}
		if (s.redisDown()) {
			return new Judgment(MonitoringLevel.DANGER, "Redis 연결 끊김");
		}
		if (!s.complete()) {
			return Judgment.NO_DATA;
		}
		if (s.podsAtMax()) {
			return new Judgment(MonitoringLevel.WARN, "Pod 최대 " + s.apiReady() + "/" + s.hpaMax());
		}
		return new Judgment(MonitoringLevel.OK, "정상");
	}

	/**
	 * 커서 1분 정지: 커서 변화 0 · 대기 인원(번호표 끝 − 커서, 0 하한) > 0 · 재고 > 0.
	 * 대기가 있으면 커서 < 번호표 끝이라 입장 수 = 커서 — 그래서 입장 그래프 식이 아니라 커서 변화로 본다.
	 * 커서가 번호표 끝보다 앞서 있으면(테스트 13) 대기 0이라 정지가 아니다
	 */
	static boolean cursorStalled(EventSignals s) {
		return s.cursorChanges() != null && s.cursorChanges() == 0 && s.waiting() != null && s.waiting() > 0
				&& s.stock() != null && s.stock() > 0;
	}

	/** KPI 중 주의 값(화면은 황토색 글씨) */
	static List<String> kpiWarnings(Double errorRatio, Double p95Seconds, Integer podsReady, Integer podsMax,
			Double redisMemoryRatio, Double redisRestarts, Boolean redisUp) {
		List<String> warn = new ArrayList<>();
		if (errorRatio != null && errorRatio >= ERROR_RATIO_WARN) {
			warn.add("errorRatio");
		}
		if (p95Seconds != null && p95Seconds >= P95_WARN_SECONDS) {
			warn.add("p95");
		}
		if (podsReady != null && podsMax != null && podsMax > 0 && podsReady >= podsMax) {
			warn.add("pods");
		}
		if (redisMemoryRatio != null && redisMemoryRatio >= REDIS_MEMORY_WARN) {
			warn.add("redisMemory");
		}
		if (redisRestarts != null && redisRestarts > 0) {
			warn.add("redisRestarts");
		}
		if (Boolean.FALSE.equals(redisUp)) {
			warn.add("redisUp");
		}
		return warn;
	}

	// ---------------------------------------------------------------- 클러스터

	/** 애드온 하나(Deployment · StatefulSet). available이 null이면 시리즈 없음(배포 안 됨) */
	record AddonMember(String group, String namespace, String name, Integer available, Integer desired) {

		boolean healthy() {
			return available != null && desired != null && available >= desired;
		}

		/** 핵심 애드온 가용 0 */
		boolean down() {
			return available == null || available == 0;
		}

	}

	/**
	 * 위험: system 노드 Ready < 2, 핵심 애드온 가용 0. 주의: Pending Pod 5분 이상.
	 * 쿼리 오류로 빈 재료(null)가 있으면 보이는 위험이 없을 때 UNKNOWN.
	 * @param available Prometheus를 읽었는지(kube-state-metrics 응답 포함)
	 * @param addons null이면 애드온 쿼리 오류(모름)
	 */
	static Judgment cluster(boolean available, Integer systemReady, int readyNodes, List<AddonMember> addons,
			Integer pendingOver5m) {
		if (!available) {
			return Judgment.NO_DATA;
		}
		if (systemReady != null && systemReady < SYSTEM_NODES_MIN) {
			return new Judgment(MonitoringLevel.DANGER, "system 노드 Ready " + systemReady);
		}
		for (AddonMember a : addons == null ? List.<AddonMember>of() : addons) {
			if (a.down()) {
				return new Judgment(MonitoringLevel.DANGER, a.group() + " 가용 0");
			}
		}
		if (systemReady == null || addons == null || pendingOver5m == null) {
			return Judgment.NO_DATA;
		}
		if (pendingOver5m > 0) {
			return new Judgment(MonitoringLevel.WARN, "Pending Pod " + pendingOver5m + "개 5분 이상");
		}
		return new Judgment(MonitoringLevel.OK, "노드 " + readyNodes + " Ready");
	}

	// ---------------------------------------------------------------- 자동화

	/**
	 * 위험: 최근 24시간 *_FAILED(메일 제외), DLQ 메시지 있음. 주의: 예정 단계가 계획보다 10분 이상 늦음.
	 * @param dlq 큐 이름 → 보이는 메시지 수. null이면 DLQ를 보지 않음(미설정 · AWS 조회 실패)
	 */
	static Judgment automation(List<TrafficCatalog.FailureItem> recentFailures, Map<String, Integer> dlq,
			List<String> delays) {
		List<String> danger = new ArrayList<>();
		if (!recentFailures.isEmpty()) {
			TrafficCatalog.FailureItem latest = recentFailures.get(0);
			danger.add("24시간 실패 " + recentFailures.size()
					+ (latest.eventId() == null ? "" : " · " + latest.eventId() + "번 " + stepLabel(latest.step())));
		}
		if (dlq != null) {
			List<String> queues = dlq.entrySet().stream().filter(e -> e.getValue() != null && e.getValue() > 0)
				.map(Map.Entry::getKey).sorted().toList();
			if (!queues.isEmpty()) {
				danger.add("DLQ " + String.join(", ", queues));
			}
		}
		if (!danger.isEmpty()) {
			return new Judgment(MonitoringLevel.DANGER, String.join(" · ", danger));
		}
		if (!delays.isEmpty()) {
			return new Judgment(MonitoringLevel.WARN, delays.get(0) + (delays.size() > 1 ? " 외 " + (delays.size() - 1) : ""));
		}
		return new Judgment(MonitoringLevel.OK, "최근 24시간 실패 0");
	}

	/** 사유 문구용 짧은 단계 이름 */
	static String stepLabel(FailureStep step) {
		return switch (step) {
			case DEPLOY -> "등록";
			case HOSTING_START -> "호스팅 시작";
			case PREPARE -> "준비";
			case END -> "종료";
			case PROVISIONING -> "환경 준비";
			case MAIL -> "메일";
			case OTHER -> "알림";
		};
	}

	/**
	 * 예정 단계 지연(계획 + 10분이 지났는데 상태가 그대로).
	 * - 호스팅 시작(WF2-1): hosting_start_at, 호스팅 PENDING · DEPLOYED 그대로
	 * - 준비(WF2-2): 입장(시작 − 60분) — 입장 직후 OPEN이 된다. 이벤트 SCHEDULED 그대로
	 * - 종료(WF4): hosting_end_at, 호스팅 ENDED · END_FAILED가 아님
	 * 배포 · 호스팅 시작 실패(DEPLOY_FAILED · HOSTING_FAILED)는 넣지 않는다 — 실패 목록 · 진행 단계가 보여 준다.
	 * 이벤트가 SCHEDULED로 남아 준비 지연이 끝없이 늘었다(이벤트 9: "준비 1625분 지연", 2026-10-28).
	 * 준비 실패(OPEN_FAILED)는 SCHEDULED가 아니라 준비 지연이 없고, 종료 지연은 본다(hosting_end_at이 지났는데 호스팅이 남아 있으면 정리가 안 된 것).
	 */
	static List<String> delays(List<MonitoredEvent> events, LocalDateTime now) {
		List<String> delays = new ArrayList<>();
		for (MonitoredEvent e : events) {
			HostingStatus h = e.hostingStatus();
			if (h == HostingStatus.DEPLOY_FAILED || h == HostingStatus.HOSTING_FAILED) {
				continue;
			}
			if ((h == HostingStatus.PENDING || h == HostingStatus.DEPLOYED) && late(e.hostingStartAt(), now)) {
				delays.add(delay(e, "호스팅 시작", e.hostingStartAt(), now));
			}
			else if (e.eventStatus() == EventStatus.SCHEDULED && late(e.entryAt(), now)) {
				delays.add(delay(e, "준비", e.entryAt(), now));
			}
			else if (e.hostingEndAt() != null && h != HostingStatus.ENDED && h != HostingStatus.END_FAILED
					&& late(e.hostingEndAt(), now)) {
				delays.add(delay(e, "종료", e.hostingEndAt(), now));
			}
		}
		return delays;
	}

	private static boolean late(LocalDateTime planned, LocalDateTime now) {
		return planned != null && now.isAfter(planned.plus(STEP_DELAY));
	}

	private static String delay(MonitoredEvent e, String step, LocalDateTime planned, LocalDateTime now) {
		return e.eventId() + "번 " + step + " " + Duration.between(planned, now).toMinutes() + "분 지연";
	}

	// ---------------------------------------------------------------- 플랫폼

	/**
	 * 위험: RDS가 available 아님, Portal 정상 대상 0. 주의: RDS CPU 80% 이상.
	 * @param available AWS를 읽었는지
	 */
	static Judgment platform(boolean available, String rdsStatus, Double rdsCpuPercent, Integer portalHealthy,
			int healthyTargets, int totalTargets) {
		if (!available) {
			return Judgment.NO_DATA;
		}
		if (rdsStatus != null && !"available".equals(rdsStatus)) {
			return new Judgment(MonitoringLevel.DANGER, "RDS " + rdsStatus);
		}
		if (portalHealthy != null && portalHealthy == 0) {
			return new Judgment(MonitoringLevel.DANGER, "Portal 정상 대상 0");
		}
		if (rdsCpuPercent != null && rdsCpuPercent >= RDS_CPU_WARN_PERCENT) {
			return new Judgment(MonitoringLevel.WARN, "RDS CPU " + Math.round(rdsCpuPercent) + "%");
		}
		String rds = rdsStatus == null ? "" : "RDS 가동 · ";
		return new Judgment(MonitoringLevel.OK, rds + "Portal·Console " + healthyTargets + "/" + totalTargets);
	}

	// ---------------------------------------------------------------- 표기

	/** 0.0083 → "0.8%" */
	static String percent(double ratio) {
		return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
	}

	/** 0.62 → "620ms", 2.3 → "2.3초" */
	static String latency(double seconds) {
		return seconds < 1 ? Math.round(seconds * 1000) + "ms" : String.format(Locale.ROOT, "%.1f초", seconds);
	}

}

package com.tetra.ops.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tetra.ops.common.time.Kst;
import com.tetra.ops.domain.EventStatus;
import com.tetra.ops.domain.HostingStatus;
import com.tetra.ops.monitoring.TrafficRules.AddonMember;
import com.tetra.ops.monitoring.TrafficRules.EventSignals;
import com.tetra.ops.monitoring.TrafficRules.Judgment;

/** 명세 2절 상태 판정 경계값 · 상태(4절) · 그래프 범위 */
class TrafficRulesTest {

	private static EventSignals signals(Double rps, Double errorRps, Double p95) {
		return new EventSignals(rps, errorRps, p95, 3.0, 100.0, 50.0, 1.0, 1.0, 6, 12);
	}

	private static Judgment event(EventSignals s) {
		return TrafficRules.event(s, true);
	}

	@Test
	void eventThresholds() {
		assertThat(event(signals(1000.0, 0.0, 0.18))).isEqualTo(new Judgment(MonitoringLevel.OK, "정상"));
		// 5xx: 1% 주의, 2% 위험(경계 포함, 설계 D-2)
		assertThat(event(signals(1000.0, 9.9, 0.18)).level()).isEqualTo(MonitoringLevel.OK);
		assertThat(event(signals(1000.0, 10.0, 0.18))).isEqualTo(new Judgment(MonitoringLevel.WARN, "5xx 1.0%"));
		assertThat(event(signals(1000.0, 19.9, 0.18)).level()).isEqualTo(MonitoringLevel.WARN);
		assertThat(event(signals(1000.0, 20.0, 0.18))).isEqualTo(new Judgment(MonitoringLevel.DANGER, "5xx 2.0%"));
		// p95: 500ms 주의, 2초 위험
		assertThat(event(signals(1000.0, 0.0, 0.5))).isEqualTo(new Judgment(MonitoringLevel.WARN, "p95 500ms"));
		assertThat(event(signals(1000.0, 0.0, 2.3))).isEqualTo(new Judgment(MonitoringLevel.DANGER, "p95 2.3초"));
		// 요청 시리즈가 있고 5xx만 없으면 0
		assertThat(event(signals(1000.0, null, 0.1)).level()).isEqualTo(MonitoringLevel.OK);
		// Prometheus를 못 읽음
		assertThat(event(null)).isEqualTo(Judgment.NO_DATA);
	}

	@Test
	void incompleteSignalsAreUnknownUnlessDangerVisible() {
		// 쿼리 일부 오류: 주의 · 정상은 판단하지 않고 UNKNOWN
		EventSignals warnButIncomplete = new EventSignals(1000.0, 5.0, 0.18, 3.0, 100.0, 50.0, 1.0, 1.0, 6, 12, false);
		assertThat(event(warnButIncomplete)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.preEntry(warnButIncomplete)).isEqualTo(Judgment.NO_DATA);
		// 보이는 위험은 그대로 위험
		EventSignals dangerIncomplete = new EventSignals(1000.0, 30.0, 0.18, 3.0, 100.0, 50.0, null, null, 6, 12, false);
		assertThat(event(dangerIncomplete).level()).isEqualTo(MonitoringLevel.DANGER);
		EventSignals redisDownIncomplete = new EventSignals(null, null, null, null, null, null, 0.0, null, null, null, false);
		assertThat(TrafficRules.preEntry(redisDownIncomplete).level()).isEqualTo(MonitoringLevel.DANGER);
	}

	@Test
	void redisDownCursorStallAndPodsAtMax() {
		EventSignals redisDown = new EventSignals(1000.0, 0.0, 0.1, 3.0, 100.0, 50.0, 0.0, 1.0, 6, 12);
		assertThat(event(redisDown)).isEqualTo(new Judgment(MonitoringLevel.DANGER, "Redis 연결 끊김"));
		EventSignals exporterDown = new EventSignals(1000.0, 0.0, 0.1, 3.0, 100.0, 50.0, null, 0.0, 6, 12);
		assertThat(event(exporterDown).level()).isEqualTo(MonitoringLevel.DANGER);

		EventSignals stalled = new EventSignals(1000.0, 0.0, 0.1, 0.0, 100.0, 50.0, 1.0, 1.0, 6, 12);
		assertThat(event(stalled)).isEqualTo(new Judgment(MonitoringLevel.DANGER, "커서 1분 정지"));
		// 시작 전에는 커서가 안 움직이는 게 정상, 대기 · 재고가 없으면 정지가 아님
		assertThat(TrafficRules.event(stalled, false).level()).isEqualTo(MonitoringLevel.OK);
		assertThat(event(new EventSignals(1000.0, 0.0, 0.1, 0.0, 0.0, 50.0, 1.0, 1.0, 6, 12)).level())
			.isEqualTo(MonitoringLevel.OK);
		assertThat(event(new EventSignals(1000.0, 0.0, 0.1, 0.0, 100.0, 0.0, 1.0, 1.0, 6, 12)).level())
			.isEqualTo(MonitoringLevel.OK);

		EventSignals atMax = new EventSignals(1000.0, 0.0, 0.1, 3.0, 100.0, 50.0, 1.0, 1.0, 12, 12);
		assertThat(event(atMax)).isEqualTo(new Judgment(MonitoringLevel.WARN, "Pod 최대 12/12"));
		// 입장 직전은 Redis · Pod만
		EventSignals slowBeforeEntry = new EventSignals(null, null, 3.0, null, null, 50.0, 1.0, 1.0, 6, 12);
		assertThat(TrafficRules.preEntry(slowBeforeEntry).level()).isEqualTo(MonitoringLevel.OK);
		assertThat(TrafficRules.preEntry(redisDown).level()).isEqualTo(MonitoringLevel.DANGER);
	}

	@Test
	void cluster() {
		List<AddonMember> ok = List.of(new AddonMember("KEDA", "keda", "keda-operator", 1, 1));
		assertThat(TrafficRules.cluster(true, 2, 16, ok, 0)).isEqualTo(new Judgment(MonitoringLevel.OK, "노드 16 Ready"));
		assertThat(TrafficRules.cluster(true, 1, 16, ok, 0))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "system 노드 Ready 1"));
		assertThat(TrafficRules.cluster(true, 2, 16, List.of(new AddonMember("KEDA", "keda", "keda-operator", 0, 1)), 0))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "KEDA 가용 0"));
		// 시리즈 없음(배포 안 됨)도 가용 0
		assertThat(TrafficRules.cluster(true, 2, 16, List.of(new AddonMember("LBC", "kube-system", "x", null, null)), 0)
			.level()).isEqualTo(MonitoringLevel.DANGER);
		assertThat(TrafficRules.cluster(true, 2, 16, ok, 3))
			.isEqualTo(new Judgment(MonitoringLevel.WARN, "Pending Pod 3개 5분 이상"));
		assertThat(TrafficRules.cluster(false, null, 0, List.of(), null)).isEqualTo(Judgment.NO_DATA);
		// 쿼리 오류로 빈 재료(null): 보이는 위험이 없으면 UNKNOWN, 있으면 위험
		assertThat(TrafficRules.cluster(true, null, 16, ok, 0)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.cluster(true, 2, 16, null, 0)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.cluster(true, 2, 16, ok, null)).isEqualTo(Judgment.NO_DATA);
		assertThat(TrafficRules.cluster(true, 1, 16, null, null).level()).isEqualTo(MonitoringLevel.DANGER);
	}

	@Test
	void automationFailuresDlqAndDelays() {
		LocalDateTime now = LocalDateTime.of(2026, 10, 26, 15, 0);
		var failure = new TrafficCatalog.FailureItem(now.minusHours(2), 5L, "aaaaaaaaaaaa", "C사", "신규 가입 쿠폰",
				FailureStep.PREPARE, "동결 확인 시간 초과");
		Map<String, Integer> emptyDlq = new LinkedHashMap<>();
		emptyDlq.put("tetra-dev-approve-result-dlq", 0);
		emptyDlq.put("tetra-dev-scheduler-dlq", 0);
		assertThat(TrafficRules.automation(List.of(), emptyDlq, List.of()))
			.isEqualTo(new Judgment(MonitoringLevel.OK, "최근 24시간 실패 0"));
		assertThat(TrafficRules.automation(List.of(failure), null, List.of()))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "24시간 실패 1 · 5번 준비"));
		Map<String, Integer> dlq = new LinkedHashMap<>(emptyDlq);
		dlq.put("tetra-dev-scheduler-dlq", 2);
		dlq.put("tetra-dev-wf-events-dlq", 1);
		assertThat(TrafficRules.automation(List.of(), dlq, List.of()))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "DLQ tetra-dev-scheduler-dlq, tetra-dev-wf-events-dlq"));
		assertThat(TrafficRules.automation(List.of(), null, List.of("7번 준비 12분 지연", "8번 종료 11분 지연")))
			.isEqualTo(new Judgment(MonitoringLevel.WARN, "7번 준비 12분 지연 외 1"));

		LocalDateTime start = now.plusMinutes(48); // 입장 = now − 12분
		var late = new MonitoredEvent(7L, "aaaaaaaaaaaa", "A사", "쿠폰", start.minusHours(2), start, start.plusHours(2),
				EventStatus.SCHEDULED, HostingStatus.HOSTING, 5000L);
		assertThat(TrafficRules.delays(List.of(late), now)).containsExactly("7번 준비 12분 지연");
		// 10분 이내면 지연 아님
		assertThat(TrafficRules.delays(List.of(late), now.minusMinutes(3))).isEmpty();
		var open = new MonitoredEvent(8L, "aaaaaaaaaaaa", "A사", "쿠폰", start.minusHours(2), start, now.minusMinutes(11),
				EventStatus.OPEN, HostingStatus.HOSTING, 5000L);
		assertThat(TrafficRules.delays(List.of(open), now)).containsExactly("8번 종료 11분 지연");
		var hosting = new MonitoredEvent(9L, "aaaaaaaaaaaa", "A사", "쿠폰", now.minusMinutes(15), now.plusHours(3),
				now.plusHours(5), EventStatus.SCHEDULED, HostingStatus.DEPLOYED, 5000L);
		assertThat(TrafficRules.delays(List.of(hosting), now)).containsExactly("9번 호스팅 시작 15분 지연");
	}

	@Test
	void platform() {
		assertThat(TrafficRules.platform(true, "available", 48.0, 1, 2, 2))
			.isEqualTo(new Judgment(MonitoringLevel.OK, "RDS 가동 · Portal·Console 2/2"));
		assertThat(TrafficRules.platform(true, "modifying", 48.0, 1, 2, 2))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "RDS modifying"));
		assertThat(TrafficRules.platform(true, "available", 48.0, 0, 1, 2))
			.isEqualTo(new Judgment(MonitoringLevel.DANGER, "Portal 정상 대상 0"));
		assertThat(TrafficRules.platform(true, "available", 80.0, 1, 2, 2))
			.isEqualTo(new Judgment(MonitoringLevel.WARN, "RDS CPU 80%"));
		assertThat(TrafficRules.platform(false, null, null, null, 0, 0)).isEqualTo(Judgment.NO_DATA);
	}

	@Test
	void kpiWarnings() {
		assertThat(TrafficRules.kpiWarnings(0.012, 0.18, 8, 12, 0.38, 0.0, true)).containsExactly("errorRatio");
		assertThat(TrafficRules.kpiWarnings(0.009, 0.18, 8, 12, 0.38, 0.0, true)).isEmpty();
		assertThat(TrafficRules.kpiWarnings(0.0, 0.6, 12, 12, 0.85, 1.0, false))
			.containsExactly("p95", "pods", "redisMemory", "redisRestarts", "redisUp");
		assertThat(TrafficRules.kpiWarnings(null, null, null, null, null, null, null)).isEmpty();
	}

	@Test
	void phaseByTimeAndStatus() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 26, 15, 0);
		LocalDateTime end = start.plusMinutes(40);
		assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING, start.minusMinutes(71)))
			.isEqualTo(EventPhase.SCHEDULED);
		assertThat(EventPhase.of(start, end, EventStatus.SCHEDULED, HostingStatus.HOSTING, start.minusMinutes(70)))
			.isEqualTo(EventPhase.PRE_ENTRY);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, start.minusMinutes(60)))
			.isEqualTo(EventPhase.LIVE);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN, HostingStatus.HOSTING, end)).isEqualTo(EventPhase.ENDED);
		assertThat(EventPhase.of(start, end, EventStatus.EXPIRED, HostingStatus.ENDED, start)).isEqualTo(EventPhase.ENDED);
		assertThat(EventPhase.of(start, end, EventStatus.OPEN_FAILED, HostingStatus.HOSTING, start))
			.isEqualTo(EventPhase.PREPARE_FAILED);
	}

	@Test
	void windows() {
		LocalDateTime start = LocalDateTime.of(2026, 10, 26, 15, 0);
		var now = start.plusMinutes(10).atZone(Kst.ZONE).toInstant();
		TrafficWindow entry = TrafficWindow.of(EventPhase.LIVE, null, start, start.plusMinutes(40), now,
				TrafficWindow.PROMETHEUS_MIN_STEP);
		assertThat(entry.key()).isEqualTo("entry");
		assertThat(Duration.between(entry.from(), entry.to())).isEqualTo(Duration.ofMinutes(80));
		assertThat(entry.step()).isEqualTo(Duration.ofSeconds(30)); // 4800초 ÷ 300 = 16초 → 15초 배수 올림
		assertThat(TrafficWindow.of(EventPhase.LIVE, "15m", start, null, now, TrafficWindow.PROMETHEUS_MIN_STEP).step())
			.isEqualTo(Duration.ofSeconds(15));
		// 종료 상태에서 15m은 기본값 full로
		var later = start.plusHours(5).atZone(Kst.ZONE).toInstant();
		TrafficWindow full = TrafficWindow.of(EventPhase.ENDED, "15m", start, start.plusMinutes(40), later,
				TrafficWindow.AWS_MIN_STEP);
		assertThat(full.key()).isEqualTo("full");
		assertThat(Duration.between(full.from(), full.to())).isEqualTo(Duration.ofMinutes(120));
		assertThat(full.step()).isEqualTo(Duration.ofSeconds(60));
		TrafficWindow around = TrafficWindow.of(EventPhase.ENDED, "start", start, start.plusMinutes(40), later,
				TrafficWindow.PROMETHEUS_MIN_STEP);
		assertThat(Duration.between(around.from(), around.to())).isEqualTo(Duration.ofMinutes(20));
		assertThat(TrafficWindow.of(EventPhase.SCHEDULED, null, start, null, now, TrafficWindow.PROMETHEUS_MIN_STEP))
			.isNull();
		assertThat(TrafficWindow.isKnown("2h")).isFalse();
	}

}

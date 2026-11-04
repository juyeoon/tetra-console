package com.tetra.ops.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.tetra.ops.event.ApproveRules.Window;

/** 승인 전 검사 규칙(3-13) — 경계값 · 문구 · 동시 이벤트 최댓값. */
class ApproveRulesTest {

	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 9, 0);

	static final LocalDateTime START = LocalDateTime.of(2026, 10, 25, 13, 0);

	private static List<String> codes(LocalDateTime hostingStartAt, LocalDateTime startAt, LocalDateTime now) {
		return List.copyOf(ApproveRules.dataReasons("이벤트", "https://shop.a-corp.com/e", hostingStartAt, startAt, now).keySet());
	}

	@Test
	void constantsComeFromWorkflows() {
		// TEST-LEAD: 서비스 값 90
		// assertThat(ApproveRules.HOSTING_LEAD_BEFORE_START).hasMinutes(90);
		assertThat(ApproveRules.HOSTING_LEAD_BEFORE_START).hasMinutes(25); // TEST-LEAD
		assertThat(ApproveRules.SCHEDULE_GAP).hasMinutes(10);
		// TEST-LEAD: 서비스 값 30
		// assertThat(ApproveRules.APPROVE_MIN_LEAD).hasMinutes(30);
		assertThat(ApproveRules.APPROVE_MIN_LEAD).hasMinutes(10); // TEST-LEAD
	}

	@Test
	void hostingStartMustBeAtLeast100MinutesBeforeStart() {
		// TEST-LEAD: 서비스 값 100 · 101 · 99 · 90
		// assertThat(codes(START.minusMinutes(100), START, NOW)).isEmpty();
		// assertThat(codes(START.minusMinutes(101), START, NOW)).isEmpty();
		// for (LocalDateTime hostingStart : List.of(START.minusMinutes(99), START.minusMinutes(90), START, START.plusMinutes(1))) {
		assertThat(codes(START.minusMinutes(35), START, NOW)).isEmpty(); // TEST-LEAD
		assertThat(codes(START.minusMinutes(36), START, NOW)).isEmpty(); // TEST-LEAD
		// TEST-LEAD: 서비스 값 99분 전 · 90분 전
		// // 99분 전 · 90분 전(WF2-1과 WF2-2가 같은 시각) · 같은 시각 · 시작 뒤는 막는다
		// 34분 전 · 25분 전(WF2-1과 WF2-2가 같은 시각) · 같은 시각 · 시작 뒤는 막는다 // TEST-LEAD
		for (LocalDateTime hostingStart : List.of(START.minusMinutes(34), START.minusMinutes(25), START, START.plusMinutes(1))) { // TEST-LEAD
			assertThat(codes(hostingStart, START, NOW)).as(hostingStart.toString()).containsExactly(ApproveRules.HOSTING_START_TOO_LATE);
		}
		// TEST-LEAD: 서비스 값 START − 60분 · 100분
		// assertThat(ApproveRules.dataReasons("이벤트", "https://a.com", START.minusMinutes(60), START, NOW))
		// 	.containsEntry(ApproveRules.HOSTING_START_TOO_LATE,
		// 			"호스팅 시작은 이벤트 시작 100분 전까지여야 합니다. (호스팅 시작 10.25 12:00 · 이벤트 시작 10.25 13:00)");
		assertThat(ApproveRules.dataReasons("이벤트", "https://a.com", START.minusMinutes(20), START, NOW)) // TEST-LEAD
			.containsEntry(ApproveRules.HOSTING_START_TOO_LATE, // TEST-LEAD
					"호스팅 시작은 이벤트 시작 35분 전까지여야 합니다. (호스팅 시작 10.25 12:40 · 이벤트 시작 10.25 13:00)"); // TEST-LEAD
	}

	@Test
	void hostingStartMustBeAtLeast30MinutesAfterApproval() {
		LocalDateTime start = NOW.plusDays(1);
		// TEST-LEAD: 서비스 값 30 · 29분 59초
		// assertThat(codes(NOW.plusMinutes(30), start, NOW)).isEmpty();
		// assertThat(codes(NOW.plusMinutes(29).plusSeconds(59), start, NOW)).containsExactly(ApproveRules.HOSTING_START_TOO_SOON);
		assertThat(codes(NOW.plusMinutes(10), start, NOW)).isEmpty(); // TEST-LEAD
		assertThat(codes(NOW.plusMinutes(9).plusSeconds(59), start, NOW)).containsExactly(ApproveRules.HOSTING_START_TOO_SOON); // TEST-LEAD
		// 이미 지난 호스팅 시작도 같은 사유
		assertThat(codes(NOW.minusDays(1), start, NOW)).containsExactly(ApproveRules.HOSTING_START_TOO_SOON);
		// TEST-LEAD: 서비스 값 NOW + 10분 · 30분
		// assertThat(ApproveRules.dataReasons("이벤트", "https://a.com", NOW.plusMinutes(10), start, NOW))
		// 	.containsEntry(ApproveRules.HOSTING_START_TOO_SOON,
		// 			"호스팅 시작까지 30분 이상 남아 있어야 승인할 수 있습니다. (호스팅 시작 10.24 09:10)");
		assertThat(ApproveRules.dataReasons("이벤트", "https://a.com", NOW.plusMinutes(5), start, NOW)) // TEST-LEAD
			.containsEntry(ApproveRules.HOSTING_START_TOO_SOON, // TEST-LEAD
					"호스팅 시작까지 10분 이상 남아 있어야 승인할 수 있습니다. (호스팅 시작 10.24 09:05)"); // TEST-LEAD
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t", "　 " })
	void blankNameIsRejected(String name) {
		assertThat(ApproveRules.dataReasons(name, "https://a.com", START.minusHours(2), START, NOW))
			.containsOnlyKeys(ApproveRules.NAME_BLANK);
		assertThat(ApproveRules.dataReasons(null, "https://a.com", START.minusHours(2), START, NOW))
			.containsOnlyKeys(ApproveRules.NAME_BLANK);
	}

	@Test
	void endpointUrlMustBeHttp() {
		for (String ok : List.of("https://shop.a-corp.com/early", "http://a.com", " HTTPS://A.COM/x?y=1 ")) {
			assertThat(ApproveRules.isHttpUrl(ok)).as(ok).isTrue();
		}
		for (String bad : List.of("", " ", "ftp://a.com", "a.com", "https://localhost", "https://.a.com", "https://a.com.",
				"javascript:alert(1)", "https://a b.com")) {
			assertThat(ApproveRules.isHttpUrl(bad)).as(bad).isFalse();
		}
		assertThat(ApproveRules.isHttpUrl(null)).isFalse();
	}

	@Test
	void allReasonsInOrder() {
		assertThat(ApproveRules.dataReasons(" ", "ftp://a.com", NOW.plusMinutes(5), NOW.plusMinutes(10), NOW).keySet())
			.containsExactly(ApproveRules.HOSTING_START_TOO_LATE, ApproveRules.HOSTING_START_TOO_SOON, ApproveRules.NAME_BLANK,
					ApproveRules.ENDPOINT_URL_INVALID);
	}

	// ---- 동시 이벤트 ----

	private static Window w(int fromHour, int toHour) {
		return new Window(START.withHour(fromHour), START.withHour(toHour));
	}

	@Test
	void redisWindowStartsAt90MinutesBeforeStart() {
		assertThat(ApproveRules.redisWindow(START, START.plusHours(5)))
			// TEST-LEAD: 서비스 값 90
			// .isEqualTo(new Window(START.minusMinutes(90), START.plusHours(5)));
			.isEqualTo(new Window(START.minusMinutes(25), START.plusHours(5))); // TEST-LEAD
	}

	@Test
	void peakIsMaximumAtOnePointNotOverlapCount() {
		// 서로 안 겹치는 3건이 새 건(10~20시)과 각각 겹친다 → 어느 시점이든 2개
		Window target = w(10, 20);
		List<Window> disjoint = List.of(w(9, 11), w(12, 14), w(15, 21));
		assertThat(ApproveRules.peak(target, disjoint).count()).isEqualTo(2);
		assertThat(ApproveRules.capacityReason(target, disjoint, 3)).isEmpty();

		// 13시에 기존 3건이 모두 떠 있고 새 건도 그 시각 → 4개
		List<Window> stacked = List.of(w(9, 14), w(12, 15), w(13, 22), w(1, 2));
		assertThat(ApproveRules.peak(target, stacked)).isEqualTo(new ApproveRules.Peak(4, START.withHour(13)));
		assertThat(ApproveRules.capacityReason(target, stacked, 3))
			.hasValue("같은 기간에 진행되는 이벤트가 최대 3개를 넘습니다. (10.25 13:00에 4개)");
		assertThat(ApproveRules.capacityReason(target, stacked, 4)).isEmpty();
	}

	@Test
	void touchingEndpointsOverlap() {
		// 닫힌 구간: 한 이벤트의 끝(15시)과 다른 이벤트의 시작(15시)이 같으면 겹친다
		Window target = w(15, 20);
		assertThat(ApproveRules.peak(target, List.of(w(10, 15), w(11, 15), w(12, 15))).count()).isEqualTo(4);
		assertThat(ApproveRules.peak(w(10, 15), List.of(w(15, 20))).count()).isEqualTo(2);
		// 1분이라도 떨어지면 겹치지 않는다
		assertThat(ApproveRules.peak(target, List.of(new Window(START.withHour(10), START.withHour(14).withMinute(59)))).count())
			.isEqualTo(1);
	}

	@Test
	void noOthersIsOne() {
		assertThat(ApproveRules.peak(w(10, 20), List.of()).count()).isEqualTo(1);
		assertThat(ApproveRules.capacityReason(w(10, 20), List.of(), 1)).isEmpty();
	}

}

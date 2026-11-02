package com.tetra.ops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.tetra.ops.domain.platform.OperatorNotification;

/** Slack 문구: 머리 · 본문 · 링크, 이스케이프(멘션 · 링크 주입), 반려 사유 · 실패 원인 빼기, 재전송 표시, 콘솔 주소 없음. */
class SlackMessageTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 2, 14, 3, 27);

	private static final String BASE = "https://console.example.test:8444/";

	private static OperatorNotification row(String category, String priority, Long eventId, String content) {
		return OperatorNotification.unread("aaaaaaaaaaaa", eventId, category, priority, AT, content);
	}

	private static String body(String text) {
		return text.split("\n")[1];
	}

	@Test
	void urgentFailureHasHeadSummaryAndNotificationCenterLink() {
		String text = SlackMessage.text(row("HOSTING", "URGENT", 123L,
				"[테넌트A] [123:가을 쿠폰] 이벤트 배포에 실패했습니다. 확인이 필요합니다. 원인: FAILED: States.TaskFailed / "
						+ "{\"errorMessage\":\"AccessDenied arn:aws:iam::123456789012:role/x\"}"),
				false, BASE);

		assertThat(text).isEqualTo("""
				*긴급* · 이벤트 · 14:03
				[테넌트A] [123:가을 쿠폰] 이벤트 배포에 실패했습니다. 확인이 필요합니다.
				<https://console.example.test:8444/logs/notifications?priority=URGENT|알림센터>""");
	}

	/** 결정 11-02: 반려 사유(자유 입력) · 실패 원인(Step Functions 원문)은 Slack에 넣지 않는다. 앞 문장만 */
	@Test
	void rejectReasonAndFailureCausesAreOmitted() {
		assertThat(body(SlackMessage.text(row("APPLICATION", "INFO", 7L,
				"[테넌트A] [7:봄 쿠폰] 이벤트 신청을 반려했습니다. 반려 사유: 담당자 010-1234-5678 확인 <!channel>"), false, BASE)))
			.isEqualTo("[테넌트A] [7:봄 쿠폰] 이벤트 신청을 반려했습니다.");
		// 호스팅 시작 · 이벤트 준비 · 종료 · 환경 준비 실패(WF5 원인 원문 포함)
		assertThat(body(SlackMessage.text(row("EVENT", "URGENT", 7L,
				"[테넌트A] [7:봄 쿠폰] 이벤트 준비(입장 열기)에 실패했습니다. 확인이 필요합니다. 원인: FAILED: Lambda.Unknown / stack"),
				false, BASE))).isEqualTo("[테넌트A] [7:봄 쿠폰] 이벤트 준비(입장 열기)에 실패했습니다. 확인이 필요합니다.");
		assertThat(body(SlackMessage.text(row("PROVISIONING", "URGENT", null,
				"[테넌트A] 테넌트 환경 준비에 실패했습니다. 확인이 필요합니다. 원인: StartExecution 실패. 스키마는 만들어졌으니 …"), false,
				BASE))).isEqualTo("[테넌트A] 테넌트 환경 준비에 실패했습니다. 확인이 필요합니다.");
		// Portal 신청 실패(고정 원인)도 같은 규칙
		assertThat(body(SlackMessage.text(
				row("APPLICATION", "WARNING", 7L, "[테넌트A] [7] 이벤트 신청 접수에 실패했습니다. 원인: 배너 업로드 실패"), false, BASE)))
			.isEqualTo("[테넌트A] [7] 이벤트 신청 접수에 실패했습니다.");
		// 표지가 없는 문구는 그대로
		String dlq = "자동화 결과를 반영하지 못했습니다. 이벤트 등록 DLQ에 메시지 3건이 있습니다. 이벤트·테넌트 상태가 갱신되지 않았을 수 있습니다.";
		assertThat(body(SlackMessage.text(row("SYSTEM", "URGENT", null, dlq), false, BASE))).isEqualTo(dlq);
	}

	/** 테넌트명에 표지 글자가 있으면 거기서 더 일찍 잘린다 — 진짜 사유 · 원인이 나가지는 않는다 */
	@Test
	void labelInsideUserInputCutsEarlierButNeverLeaks() {
		String text = SlackMessage.text(row("APPLICATION", "INFO", 7L,
				"[가짜 원인: 이름] [7:봄] 이벤트 신청을 반려했습니다. 반려 사유: 비밀 사유"), false, BASE);

		assertThat(body(text)).isEqualTo("[가짜");
		assertThat(text).doesNotContain("비밀 사유");
	}

	/** 사용자 입력의 & < > 를 이스케이프해 <!channel> · <@U…> · <주소|글자>가 해석되지 않는다 */
	@Test
	void escapesMentionAndLinkInjection() {
		String text = SlackMessage.text(row("APPLICATION", "WARNING", 7L,
				"[<!channel> A&B <@U123> <https://evil.example|여기>] [7:<!here>] 이벤트 신청이 접수되었습니다. 승인 검토가 필요합니다."),
				false, BASE);

		assertThat(body(text)).isEqualTo("[&lt;!channel&gt; A&amp;B &lt;@U123&gt; &lt;https://evil.example|여기&gt;] [7:&lt;!here&gt;] "
				+ "이벤트 신청이 접수되었습니다. 승인 검토가 필요합니다.");
		// 꺾쇠는 마지막 줄의 알림센터 링크 하나뿐
		assertThat(text.chars().filter(c -> c == '<').count()).isEqualTo(1);
	}

	/** 줄바꿈 · 제어 문자는 공백 — 사용자 입력으로 가짜 머리 줄을 만들지 못한다 */
	@Test
	void controlCharactersBecomeSpaces() {
		String text = SlackMessage.text(row("ACCOUNT", "INFO", null, "[A\n*긴급* · 시스템\r\n\tB] 테넌트가 등록되었습니다. 끝"), false,
				BASE);

		assertThat(text.split("\n")).hasSize(3);
		assertThat(body(text)).isEqualTo("[A *긴급* · 시스템 B] 테넌트가 등록되었습니다. 끝");
	}

	@Test
	void resendIsMarkedAtTheFront() {
		String text = SlackMessage.text(row("SYSTEM", "URGENT", null, "DLQ 메시지"), true, BASE);

		assertThat(text).startsWith("[재전송] *긴급* · 시스템 · 14:03\n");
	}

	@Test
	void linkIsOmittedWithoutConsoleBaseUrl() {
		for (String base : new String[] { "", "  ", null }) {
			String text = SlackMessage.text(row("SYSTEM", "WARNING", null, "문구"), false, base);
			assertThat(text).isEqualTo("*주의* · 시스템 · 14:03\n문구");
		}
	}

	/** 분류 = 알림센터 칩(NotificationRules.chip). 모르는 category면 분류를 뺀다 */
	@Test
	void headUsesNotificationCenterChipNames() {
		assertThat(head("APPLICATION", 1L)).isEqualTo("*정보* · 신청 · 14:03");
		assertThat(head("HOSTING", 1L)).isEqualTo("*정보* · 이벤트 · 14:03");
		assertThat(head("DELAY", 1L)).isEqualTo("*정보* · 이벤트 · 14:03");
		assertThat(head("MAIL", 1L)).isEqualTo("*정보* · 이벤트 · 14:03");
		assertThat(head("MAIL", null)).isEqualTo("*정보* · 테넌트 · 14:03");
		assertThat(head("PROVISIONING", null)).isEqualTo("*정보* · 테넌트 · 14:03");
		assertThat(head("REPORT", null)).isEqualTo("*정보* · 리포트 · 14:03");
		assertThat(head("SYSTEM", null)).isEqualTo("*정보* · 시스템 · 14:03");
		assertThat(head("SOMETHING_NEW", null)).isEqualTo("*정보* · 14:03");
	}

	private static String head(String category, Long eventId) {
		return SlackMessage.text(row(category, "INFO", eventId, "문구"), false, "").split("\n")[0];
	}

	@Test
	void linkFiltersByPriority() {
		assertThat(SlackMessage.link("https://c.example", "WARNING"))
			.isEqualTo("<https://c.example/logs/notifications?priority=WARNING|알림센터>");
		assertThat(SlackMessage.link("https://c.example//", "INFO"))
			.isEqualTo("<https://c.example/logs/notifications?priority=INFO|알림센터>");
		assertThat(SlackMessage.link("https://c.example", "LOW")).isEqualTo("<https://c.example/logs/notifications|알림센터>");
	}

}

package com.tetra.ops.notification;

import java.time.format.DateTimeFormatter;
import java.util.List;

import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.notification.NotificationRules.Chip;

/**
 * 운영자 알림 한 건의 Slack 문구(chat.postMessage text, mrkdwn). 순수 함수 — 값은 부르는 쪽이 넘긴다.
 * <pre>
 * [재전송] *긴급* · 이벤트 · 14:03
 * [테넌트A] [123:가을 쿠폰] 이벤트 배포에 실패했습니다. 확인이 필요합니다.
 * &lt;https://console.example.com/logs/notifications?priority=URGENT|알림센터&gt;
 * </pre>
 * - 머리: 재전송 표시(URGENT를 한 번 더 보낼 때만) · 우선순위(화면 이름 긴급 · 주의 · 정보) · 분류(알림센터 칩, 모르는 category면 뺌) ·
 *   occurred_at의 HH:mm(DB의 KST 값 그대로).
 * - 본문: 알림 문구. 사용자 입력(테넌트명 · 이벤트명 등)이 들어 있어 &amp; &lt; &gt; 를 이스케이프한다 — &lt;!channel&gt; · &lt;@U…&gt; ·
 *   &lt;주소|글자&gt; 주입을 막는다. 제어 문자(줄바꿈 등)는 공백으로 바꾼다(가짜 머리 줄을 만들지 못하게).
 *   " 반려 사유: " · " 원인: " 뒤는 넣지 않는다(결정 11-02) — 반려 사유는 자유 입력, 실패 원인은 Step Functions 오류 원문이라
 *   외부 서비스에 보내지 않는다. 앞 문장(…반려했습니다. · …실패했습니다. 확인이 필요합니다.)만 남고, 자세한 것은 Console에서 본다.
 * - 끝: 알림센터 링크 하나(같은 우선순위로 거른 화면). 콘솔 주소(CONSOLE_BASE_URL)가 비어 있으면 뺀다.
 */
final class SlackMessage {

	static final String RESEND_MARK = "[재전송]";

	static final String NOTIFICATIONS_PATH = "/logs/notifications";

	static final String LINK_TEXT = "알림센터";

	/**
	 * 이 표지부터 끝까지는 Slack에 넣지 않는다. 처음 나온 곳에서 자른다 — 테넌트명 · 이벤트명에 같은 글자가 있으면 더 일찍 잘릴 뿐
	 * 표지 뒤가 나가지는 않는다
	 */
	static final List<String> OMITTED_FROM = List.of(" 반려 사유: ", " 원인: ");

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private SlackMessage() {
	}

	/**
	 * @param resend 앞 시도가 보냈는지 모르는 URGENT를 한 번 더 보낼 때 true
	 * @param consoleBaseUrl 콘솔 주소(끝 / 무관). 비어 있으면 링크 없음
	 */
	static String text(OperatorNotification n, boolean resend, String consoleBaseUrl) {
		StringBuilder head = new StringBuilder();
		if (resend) {
			head.append(RESEND_MARK).append(' ');
		}
		head.append('*').append(priorityLabel(n.getPriority())).append('*');
		String chip = chipLabel(NotificationRules.chip(n.getCategory(), n.getEventId()));
		if (chip != null) {
			head.append(" · ").append(chip);
		}
		head.append(" · ").append(n.getOccurredAt() == null ? "--:--" : HH_MM.format(n.getOccurredAt()));
		StringBuilder text = head.append('\n').append(escape(summary(n.getContent())));
		String link = link(consoleBaseUrl, n.getPriority());
		if (link != null) {
			text.append('\n').append(link);
		}
		return text.toString();
	}

	/** 알림 문구에서 반려 사유 · 실패 원인을 뺀 앞부분 */
	static String summary(String content) {
		if (content == null) {
			return "";
		}
		int cut = content.length();
		for (String label : OMITTED_FROM) {
			int at = content.indexOf(label);
			if (at >= 0 && at < cut) {
				cut = at;
			}
		}
		return content.substring(0, cut);
	}

	/** Slack mrkdwn 이스케이프(&amp; &lt; &gt;) + 제어 문자 · 줄 구분 문자를 공백 하나로 */
	static String escape(String value) {
		return value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ")
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;");
	}

	/** 화면 이름(2026-10-30 결정). 모르는 값은 그대로(이스케이프) */
	static String priorityLabel(String priority) {
		return switch (String.valueOf(priority)) {
			case "URGENT" -> "긴급";
			case "WARNING" -> "주의";
			case "INFO" -> "정보";
			default -> escape(String.valueOf(priority));
		};
	}

	/** 알림센터 칩 이름. 칩이 없으면(모르는 category) null */
	static String chipLabel(Chip chip) {
		if (chip == null) {
			return null;
		}
		return switch (chip) {
			case APPLICATION -> "신청";
			case EVENT -> "이벤트";
			case TENANT -> "테넌트";
			case REPORT -> "리포트";
			case SYSTEM -> "시스템";
		};
	}

	/** &lt;{콘솔}/logs/notifications?priority={우선순위}|알림센터&gt;. 콘솔 주소가 비면 null, 모르는 우선순위면 거르지 않은 화면 */
	static String link(String consoleBaseUrl, String priority) {
		String base = consoleBaseUrl == null ? "" : consoleBaseUrl.strip().replaceAll("/+$", "");
		if (base.isEmpty()) {
			return null;
		}
		boolean known = "URGENT".equals(priority) || "WARNING".equals(priority) || "INFO".equals(priority);
		return "<" + base + NOTIFICATIONS_PATH + (known ? "?priority=" + priority : "") + "|" + LINK_TEXT + ">";
	}

}

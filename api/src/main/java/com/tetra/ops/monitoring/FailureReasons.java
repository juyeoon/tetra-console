package com.tetra.ops.monitoring;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자동화 실패 사유를 화면용으로 줄인다(OPS-05 E 최근 자동화 실패 · 진행 단계 실패 문구, 2026-10-28).
 * 상태 로그 detail은 "FAILED: 오류 / 원인"이고 원인이 Lambda · ECS 오류 JSON 원문(수백 자, 스택 트레이스 포함)이라 그대로 보이지 않는다.
 * - 앞부분 요약: "상태: 오류 · 짧은 원인". 짧은 원인은 원인 JSON의 메시지 하나(errorMessage · StoppedReason · Reason · message …),
 *   없으면 JSON 기호를 뺀 앞부분. 전체는 이벤트 진행 상세(상태 로그)에서 본다
 * - 토큰 · 비밀번호 형태 값은 가린다: 키 이름이 password · secret · token · api key · authorization 등인 값, Bearer · Basic 값,
 *   URL 안 사용자:비밀번호, AWS 액세스 키, JWT, 32자 이상 난수 문자열
 * - 최대 {@link #MAX_LENGTH}자, 넘으면 "…"
 */
final class FailureReasons {

	static final int MAX_LENGTH = 120;

	static final String MASK = "***";

	private static final Pattern SECRET_FIELD = Pattern.compile("(?i)(\"?[a-z0-9_.-]*"
			+ "(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|authorization|credential)"
			+ "[a-z0-9_.-]*\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\s,;}&\"]+)");

	private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");

	private static final Pattern URL_CREDENTIALS = Pattern.compile("(://[^/\\s:@]+:)[^@\\s/]+@");

	private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");

	private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

	/** 난수 토큰 · 키. '-' · '/'는 넣지 않는다(UUID · 경로를 가리지 않게) */
	private static final Pattern LONG_TOKEN = Pattern.compile("(?<![A-Za-z0-9+=_])[A-Za-z0-9+=_]{32,}(?![A-Za-z0-9+=_])");

	/** 원인 JSON에서 먼저 보는 메시지 키(앞이 먼저) */
	private static final List<String> MESSAGE_KEYS = List.of("errorMessage", "StoppedReason", "stoppedReason", "Reason",
			"reason", "message", "Message", "Cause", "cause");

	private FailureReasons() {
	}

	static String summarize(String raw) {
		if (raw == null) {
			return null;
		}
		String text = mask(unescape(raw)).strip();
		// ExecutionStatusChange.failureDetail 형식: "상태: 오류 / 원인"
		int split = text.indexOf(" / ");
		String head = split < 0 ? null : text.substring(0, split).strip();
		String cause = split < 0 ? text : text.substring(split + 3).strip();
		String shortCause = looksLikeJson(cause) ? messageOf(cause) : cause;
		String summary = head == null || head.isEmpty() ? shortCause
				: shortCause.isEmpty() ? head : head + " · " + shortCause;
		return truncate(summary.replaceAll("\\s+", " ").strip());
	}

	static String mask(String text) {
		String out = SECRET_FIELD.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK));
		out = AUTH_SCHEME.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + MASK));
		out = URL_CREDENTIALS.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK + "@"));
		out = AWS_ACCESS_KEY.matcher(out).replaceAll(MASK);
		out = JWT.matcher(out).replaceAll(MASK);
		return LONG_TOKEN.matcher(out).replaceAll(MASK);
	}

	/** JSON 문자열 안의 JSON(\" · \\n)을 푼다 */
	private static String unescape(String text) {
		String out = text;
		for (int i = 0; i < 3 && out.contains("\\"); i++) {
			out = out.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ");
		}
		return out;
	}

	private static boolean looksLikeJson(String text) {
		return text.startsWith("{") || text.startsWith("[");
	}

	/** 메시지 키 값 하나, 없으면 JSON 기호를 뺀 원문 */
	private static String messageOf(String json) {
		for (String key : MESSAGE_KEYS) {
			Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
			if (m.find() && !m.group(1).isBlank()) {
				return m.group(1).strip();
			}
		}
		return json.replaceAll("[{}\\[\\]\"]", " ").replaceAll("\\s*,\\s*", ", ").strip();
	}

	private static String truncate(String text) {
		return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 1).stripTrailing() + "…";
	}

}

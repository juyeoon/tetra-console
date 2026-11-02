package com.tetra.ops.notification;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.net.ssl.SSLHandshakeException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.tetra.ops.config.TetraProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slack Web API chat.postMessage(봇 토큰, Authorization: Bearer). Slack SDK는 메서드 하나에 OkHttp · Gson까지 들여오는 셈이라 쓰지 않고
 * RestClient + JdkClientHttpRequestFactory(PrometheusClient와 같은 조합, 의존성 추가 없음)로 부른다. 연결 2초 · 읽기 5초(설정).
 * 결과는 예외 없이 {@link Result}로 나눈다 — Slack은 대부분 HTTP 200 + ok:false로 오류를 준다.
 * <pre>
 * SENT          200 ok:true
 * RATE_LIMITED  429(Retry-After 초, 없으면 30초) · ok:false ratelimited — 안 보냄, 시도로 세지 않음
 * RETRY         연결 실패 · 연결 시간 초과 · TLS 실패 · HTTP 502~504 등 5xx · ok:false service_unavailable · request_timeout — 안 보냄
 * FAILED        그 밖의 4xx · 그 밖의 ok:false(invalid_auth · channel_not_found · not_in_channel · account_inactive …) — 다시 해도 안 됨
 * UNKNOWN       읽기 시간 초과 · 응답 중 끊김 · HTTP 500 · ok:false internal_error · fatal_error · 응답 형식 오류 — 보냈는지 모름
 * </pre>
 * 토큰 · 요청 본문 · 응답 본문은 결과 · 예외에 넣지 않는다 — error는 Slack error 코드 · HTTP 상태 · 예외 종류만(100자 이내).
 */
final class SlackClient {

	enum Outcome {

		SENT, RATE_LIMITED, RETRY, FAILED, UNKNOWN

	}

	/**
	 * @param error 실패 원인 코드(SENT면 null)
	 * @param channel · ts SENT일 때 응답의 채널 ID · 메시지 ts
	 * @param retryAfter RATE_LIMITED일 때 다시 보내도 되는 때까지
	 */
	record Result(Outcome outcome, String error, String channel, String ts, Duration retryAfter) {

		/** 응답의 채널 · ts가 열(slack_channel 20자 · slack_ts 32자) 형식이 아니면 null — 채널은 부르는 쪽이 요청 채널로 채운다 */
		static Result sent(String channel, String ts) {
			return new Result(Outcome.SENT, null, channel != null && CHANNEL_ID.matcher(channel).matches() ? channel : null,
					ts != null && TS.matcher(ts).matches() ? ts : null, null);
		}

		static Result rateLimited(Duration retryAfter, String error) {
			return new Result(Outcome.RATE_LIMITED, code(error), null, null, retryAfter);
		}

		static Result retry(String error) {
			return new Result(Outcome.RETRY, code(error), null, null, null);
		}

		static Result failed(String error) {
			return new Result(Outcome.FAILED, code(error), null, null, null);
		}

		static Result unknown(String error) {
			return new Result(Outcome.UNKNOWN, code(error), null, null, null);
		}

	}

	static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(30);

	/** Retry-After 상한(형식이 이상한 큰 값으로 행이 오래 묶이지 않게) */
	static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

	static final int ERROR_MAX = 100;

	/** Slack 채널 ID(C… · G…, 대문자 · 숫자). TetraProperties.SlackChannels와 같은 형식 */
	static final Pattern CHANNEL_ID = Pattern.compile("[A-Z0-9]{1,20}");

	/** 메시지 ts(예: 1503435956.000247) */
	static final Pattern TS = Pattern.compile("[0-9.]{1,32}");

	private static final Set<String> RATE_LIMIT_ERRORS = Set.of("ratelimited", "rate_limited");

	/** 요청이 처리되지 않은 일시 오류(service_unavailable: 일시 중단, request_timeout: 요청 본문이 다 오지 않음) */
	private static final Set<String> RETRY_ERRORS = Set.of("service_unavailable", "request_timeout");

	/** Slack 문서: 처리 중 오류라 일부가 처리됐을 수 있음 */
	private static final Set<String> UNKNOWN_ERRORS = Set.of("internal_error", "fatal_error");

	private static final MediaType JSON_UTF8 = new MediaType("application", "json", StandardCharsets.UTF_8);

	private final RestClient restClient;

	private final String authorization;

	private final JsonMapper jsonMapper;

	SlackClient(TetraProperties.Slack settings, JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		this.authorization = "Bearer " + (settings.botToken() == null ? "" : settings.botToken().strip());
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(settings.readTimeout());
		String base = settings.apiUrl().strip().replaceAll("/+$", "");
		this.restClient = RestClient.builder().baseUrl(base).requestFactory(factory).build();
	}

	/** text = mrkdwn(이스케이프는 부르는 쪽, SlackMessage). 링크 펼침은 끈다 */
	Result post(String channel, String text) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("channel", channel);
		payload.put("text", text);
		payload.put("unfurl_links", false);
		payload.put("unfurl_media", false);
		String body = jsonMapper.writeValueAsString(payload);
		try {
			return restClient.post()
				.uri("/chat.postMessage")
				.header(HttpHeaders.AUTHORIZATION, authorization)
				.contentType(JSON_UTF8)
				.body(body)
				.exchangeForRequiredValue((request, response) -> judge(response.getStatusCode().value(),
						response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
						StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8), jsonMapper));
		}
		catch (RestClientException ex) {
			return judge(ex);
		}
	}

	/** HTTP 응답 판정(순수). body는 판정에만 쓰고 결과에 넣지 않는다 */
	static Result judge(int status, String retryAfter, String body, JsonMapper jsonMapper) {
		if (status == 429) {
			return Result.rateLimited(retryAfter(retryAfter), "HTTP_429");
		}
		if (status == 500) {
			return Result.unknown("HTTP_500");
		}
		if (status >= 500) {
			return Result.retry("HTTP_" + status);
		}
		if (status < 200 || status >= 300) {
			return Result.failed("HTTP_" + status);
		}
		JsonNode root;
		try {
			root = jsonMapper.readTree(body == null ? "" : body);
		}
		catch (JacksonException ex) {
			return Result.unknown("BAD_RESPONSE");
		}
		if (root == null || !root.path("ok").isBoolean()) {
			return Result.unknown("BAD_RESPONSE");
		}
		if (root.path("ok").asBoolean()) {
			return Result.sent(root.path("channel").asString(null), root.path("ts").asString(null));
		}
		String error = root.path("error").asString("");
		if (RATE_LIMIT_ERRORS.contains(error)) {
			return Result.rateLimited(retryAfter(retryAfter), error);
		}
		if (RETRY_ERRORS.contains(error)) {
			return Result.retry(error);
		}
		if (UNKNOWN_ERRORS.contains(error)) {
			return Result.unknown(error);
		}
		return Result.failed(error.isEmpty() ? "NO_ERROR_CODE" : error);
	}

	/**
	 * 전송 오류 판정(순수). 연결 전 단계(연결 거부 · 연결 시간 초과 · 이름 해석 · TLS)면 요청이 가지 않았으므로 RETRY,
	 * 읽기 시간 초과와 그 밖의 I/O 오류(응답 중 끊김 등)는 요청이 갔을 수 있어 UNKNOWN
	 */
	static Result judge(Throwable ex) {
		for (Throwable c = ex; c != null; c = c.getCause()) {
			if (c instanceof HttpConnectTimeoutException) {
				return Result.retry("CONNECT_TIMEOUT");
			}
			if (c instanceof ConnectException || c instanceof NoRouteToHostException || c instanceof UnknownHostException
					|| c instanceof SSLHandshakeException) {
				return Result.retry(c.getClass().getSimpleName());
			}
		}
		Throwable last = ex;
		for (Throwable c = ex; c != null; c = c.getCause()) {
			if (c instanceof HttpTimeoutException) {
				return Result.unknown("READ_TIMEOUT");
			}
			last = c;
		}
		return Result.unknown(last.getClass().getSimpleName());
	}

	/** Retry-After 초. 없거나 형식이 틀리면 30초, 상한 1시간 */
	static Duration retryAfter(String header) {
		if (header != null) {
			try {
				long seconds = Long.parseLong(header.strip());
				if (seconds > 0) {
					return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER.toSeconds()));
				}
			}
			catch (NumberFormatException ex) {
				// HTTP 날짜 형식 등은 기본값
			}
		}
		return DEFAULT_RETRY_AFTER;
	}

	/** slack_error에 넣을 코드: 영문 · 숫자 · _ . : - 만(나머지는 _), 100자 이내 */
	static String code(String raw) {
		String safe = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_.:-]", "_");
		return safe.length() <= ERROR_MAX ? safe : safe.substring(0, ERROR_MAX);
	}

}

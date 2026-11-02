package com.tetra.ops.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.notification.SlackClient.Outcome;
import com.tetra.ops.notification.SlackClient.Result;
import com.tetra.ops.support.FakeSlack;

import tools.jackson.databind.json.JsonMapper;

/**
 * chat.postMessage 호출(FakeSlack — 실제 HTTP)과 결과 판정(순수 함수). 토큰 · 응답 본문은 결과에 들어가지 않는다.
 */
class SlackClientTest {

	static final String TOKEN = "xoxb-test-token-0123456789";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final FakeSlack fake = new FakeSlack();

	@AfterAll
	static void stop() {
		fake.close();
	}

	@BeforeEach
	void reset() {
		fake.reset();
	}

	static SlackClient client(String apiUrl, Duration connectTimeout, Duration readTimeout) {
		var settings = new TetraProperties.Slack(true, TOKEN, new TetraProperties.SlackChannels("CURGENT", "CWARNING", "CINFO"),
				Duration.ofSeconds(10), Duration.ofMinutes(10), connectTimeout, readTimeout, apiUrl);
		return new SlackClient(settings, JSON);
	}

	private final SlackClient client = client(fake.url() + "/", Duration.ofSeconds(2), Duration.ofSeconds(2));

	// ---------------------------------------------------------------- HTTP

	@Test
	void postsJsonWithBearerTokenAndUnfurlOff() {
		Result result = client.post("CINFO", "*정보* · 신청 · 14:03\n문구 &lt;x&gt;");

		assertThat(result.outcome()).isEqualTo(Outcome.SENT);
		assertThat(result.channel()).isEqualTo("CINFO");
		assertThat(result.ts()).isEqualTo("1762070400.000001");
		assertThat(result.error()).isNull();
		assertThat(fake.requests()).singleElement().satisfies(r -> {
			assertThat(r.path()).isEqualTo("/api/chat.postMessage");
			assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN);
			assertThat(r.contentType()).isEqualToIgnoringCase("application/json;charset=UTF-8");
			assertThat(r.channel()).isEqualTo("CINFO");
			assertThat(r.text()).isEqualTo("*정보* · 신청 · 14:03\n문구 &lt;x&gt;");
			assertThat(r.json().path("unfurl_links").isBoolean()).isTrue();
			assertThat(r.json().path("unfurl_links").asBoolean()).isFalse();
			assertThat(r.json().path("unfurl_media").asBoolean(true)).isFalse();
		});
	}

	@Test
	void rateLimitedWithRetryAfter() {
		fake.thenRateLimited(7);

		Result result = client.post("CINFO", "t");

		assertThat(result.outcome()).isEqualTo(Outcome.RATE_LIMITED);
		assertThat(result.retryAfter()).hasSeconds(7);
		assertThat(result.error()).isEqualTo("HTTP_429");
	}

	@Test
	void transientErrorsAreRetried() {
		fake.then(503, "{\"secret\":\"response body\"}").thenError("service_unavailable");

		Result http = client.post("CINFO", "t");
		Result slack = client.post("CINFO", "t");

		assertThat(http.outcome()).isEqualTo(Outcome.RETRY);
		assertThat(http.error()).isEqualTo("HTTP_503");
		assertThat(slack.outcome()).isEqualTo(Outcome.RETRY);
		assertThat(slack.error()).isEqualTo("service_unavailable");
		assertThat(http.toString()).doesNotContain("response body").doesNotContain(TOKEN);
	}

	@Test
	void permanentErrorsFailAtOnce() {
		fake.thenError("channel_not_found").thenError("invalid_auth").then(404, "not found");

		assertThat(client.post("CINFO", "t")).extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.FAILED, "channel_not_found");
		assertThat(client.post("CINFO", "t")).extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.FAILED, "invalid_auth");
		assertThat(client.post("CINFO", "t")).extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.FAILED, "HTTP_404");
	}

	/** 응답이 늦으면(읽기 시간 초과) Slack이 받아 처리했을 수 있다 — 모름 */
	@Test
	void readTimeoutIsUnknown() {
		SlackClient quick = client(fake.url(), Duration.ofSeconds(2), Duration.ofMillis(300));
		fake.thenSlowOk(1500);

		Result result = quick.post("CURGENT", "t");

		assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(result.error()).isEqualTo("READ_TIMEOUT");
		assertThat(fake.requests()).hasSize(1);
	}

	/** 서버가 없으면(연결 거부 · 연결 시간 초과) 요청이 가지 않았다 — 다시 시도 */
	@Test
	void connectionFailureIsRetried() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		SlackClient closed = client("http://127.0.0.1:" + port + "/api", Duration.ofMillis(500), Duration.ofSeconds(1));

		Result result = closed.post("CINFO", "t");

		assertThat(result.outcome()).isEqualTo(Outcome.RETRY);
		assertThat(result.error()).isIn("ConnectException", "CONNECT_TIMEOUT");
	}

	// ---------------------------------------------------------------- 판정(순수)

	@Test
	void judgesSlackErrorCodes() {
		assertThat(judge(200, null, "{\"ok\":false,\"error\":\"ratelimited\"}")).extracting(Result::outcome, Result::retryAfter)
			.containsExactly(Outcome.RATE_LIMITED, Duration.ofSeconds(30));
		assertThat(judge(200, "12", "{\"ok\":false,\"error\":\"ratelimited\"}").retryAfter()).hasSeconds(12);
		assertThat(judge(200, null, "{\"ok\":false,\"error\":\"request_timeout\"}").outcome()).isEqualTo(Outcome.RETRY);
		assertThat(judge(200, null, "{\"ok\":false,\"error\":\"internal_error\"}").outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(judge(200, null, "{\"ok\":false,\"error\":\"fatal_error\"}").outcome()).isEqualTo(Outcome.UNKNOWN);
		for (String permanent : new String[] { "invalid_auth", "not_authed", "token_revoked", "account_inactive",
				"missing_scope", "channel_not_found", "not_in_channel", "is_archived", "msg_too_long", "no_text",
				"something_new" }) {
			assertThat(judge(200, null, "{\"ok\":false,\"error\":\"" + permanent + "\"}")).as(permanent)
				.extracting(Result::outcome, Result::error)
				.containsExactly(Outcome.FAILED, permanent);
		}
		assertThat(judge(200, null, "{\"ok\":false}").error()).isEqualTo("NO_ERROR_CODE");
	}

	@Test
	void judgesHttpStatusAndBadBodies() {
		assertThat(judge(429, null, "").retryAfter()).hasSeconds(30);
		assertThat(judge(429, "abc", "").retryAfter()).hasSeconds(30);
		assertThat(judge(429, "999999999", "").retryAfter()).isEqualTo(SlackClient.MAX_RETRY_AFTER);
		// 500은 처리 중 오류라 모름, 502 · 503 · 504는 앞단에서 막힘
		assertThat(judge(500, null, "")).extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.UNKNOWN, "HTTP_500");
		assertThat(judge(502, null, "").outcome()).isEqualTo(Outcome.RETRY);
		assertThat(judge(504, null, "").outcome()).isEqualTo(Outcome.RETRY);
		assertThat(judge(400, null, "").outcome()).isEqualTo(Outcome.FAILED);
		assertThat(judge(200, null, "<html>")).extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.UNKNOWN, "BAD_RESPONSE");
		assertThat(judge(200, null, "{}").error()).isEqualTo("BAD_RESPONSE");
		assertThat(judge(200, null, "").error()).isEqualTo("BAD_RESPONSE");
	}

	/** 응답의 채널 · ts가 열 형식이 아니면 버린다(채널은 요청 채널로 채움). error 코드는 안전한 글자만 100자 */
	@Test
	void sanitizesStoredValues() {
		Result sent = judge(200, null, "{\"ok\":true,\"channel\":\"#ops-urgent-very-long-name\",\"ts\":\"x'; drop\"}");
		assertThat(sent.outcome()).isEqualTo(Outcome.SENT);
		assertThat(sent.channel()).isNull();
		assertThat(sent.ts()).isNull();

		Result weird = judge(200, null, "{\"ok\":false,\"error\":\"bad code <x>" + "a".repeat(200) + "\"}");
		assertThat(weird.error()).hasSize(100).startsWith("bad_code__x_").matches("[A-Za-z0-9_.:-]+");
	}

	@Test
	void judgesTransportErrors() {
		assertThat(SlackClient.judge(new ResourceAccessException("I/O", new HttpConnectTimeoutException("connect"))))
			.extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.RETRY, "CONNECT_TIMEOUT");
		assertThat(SlackClient.judge(new ResourceAccessException("I/O", new ConnectException())))
			.extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.RETRY, "ConnectException");
		assertThat(SlackClient.judge(new ResourceAccessException("I/O", new HttpTimeoutException("request timed out"))))
			.extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.UNKNOWN, "READ_TIMEOUT");
		// 응답 중 끊김 등은 요청이 갔을 수 있어 모름
		assertThat(SlackClient.judge(new ResourceAccessException("I/O", new IOException("connection reset"))))
			.extracting(Result::outcome, Result::error)
			.containsExactly(Outcome.UNKNOWN, "IOException");
	}

	private static Result judge(int status, String retryAfter, String body) {
		return SlackClient.judge(status, retryAfter, body, JSON);
	}

	@Test
	void resultNeverCarriesTokenOrBody() {
		fake.then(200, "{\"ok\":false,\"error\":\"invalid_auth\",\"detail\":\"" + TOKEN + "\"}", Map.of());

		Result result = client.post("CINFO", "t");

		assertThat(result.toString()).doesNotContain(TOKEN).doesNotContain("detail");
	}

}

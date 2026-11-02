package com.tetra.ops.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slack Web API 목(JDK 내장 HttpServer — FakePrometheus와 같은 방식, 라이브러리 추가 없음). 실제 HTTP라 헤더 · 본문 · 타임아웃까지 확인한다.
 * 정해 둔 응답을 요청 순서대로 하나씩 주고, 남은 것이 없으면 ok:true(요청 채널, ts는 1번부터 차례로).
 */
public final class FakeSlack implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 받은 요청 하나. receivedNanos = 받은 시각(System.nanoTime, 같은 채널 간격 확인용) */
	public record Request(String path, String authorization, String contentType, String body, long receivedNanos) {

		public JsonNode json() {
			return JSON.readTree(body);
		}

		public String channel() {
			return json().path("channel").asString(null);
		}

		public String text() {
			return json().path("text").asString(null);
		}

	}

	private record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
	}

	private final HttpServer server;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final AtomicInteger ts = new AtomicInteger();

	private volatile long delayMillis;

	public FakeSlack() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.createContext("/", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	/** tetra.slack.api-url로 쓴다(그 뒤에 /chat.postMessage) */
	public String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
	}

	/** 다음 요청 하나에 줄 응답(정한 순서대로) */
	public FakeSlack then(int status, String body) {
		return then(status, body, Map.of());
	}

	public FakeSlack then(int status, String body, Map<String, String> headers) {
		replies.add(new Reply(status, body, headers, 0));
		return this;
	}

	/** HTTP 200 ok:false + error 코드 */
	public FakeSlack thenError(String error) {
		return then(200, "{\"ok\":false,\"error\":\"" + error + "\"}");
	}

	/** 429 + Retry-After(초) */
	public FakeSlack thenRateLimited(int retryAfterSeconds) {
		return then(429, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", String.valueOf(retryAfterSeconds)));
	}

	/** 늦게 ok:true(읽기 시간 초과 — 받은 뒤 처리했지만 응답이 늦은 경우) */
	public FakeSlack thenSlowOk(long millis) {
		replies.add(new Reply(200, null, Map.of(), millis));
		return this;
	}

	/** 모든 요청을 늦게 */
	public void delay(long millis) {
		this.delayMillis = millis;
	}

	public List<Request> requests() {
		return requests;
	}

	public void reset() {
		replies.clear();
		requests.clear();
		delayMillis = 0;
	}

	private void handle(HttpExchange exchange) throws IOException {
		long received = System.nanoTime();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Request request = new Request(exchange.getRequestURI().getPath(),
				exchange.getRequestHeaders().getFirst("Authorization"),
				exchange.getRequestHeaders().getFirst("Content-Type"), body, received);
		requests.add(request);
		Reply reply = replies.poll();
		long delay = delayMillis + (reply == null ? 0 : reply.delayMillis());
		if (delay > 0) {
			try {
				Thread.sleep(delay);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		int status = reply == null ? 200 : reply.status();
		String responseBody = reply != null && reply.body() != null ? reply.body() : ok(request);
		byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		if (reply != null) {
			reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
		}
		try {
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		catch (IOException ex) {
			// 클라이언트가 타임아웃으로 먼저 끊은 경우
		}
	}

	private String ok(Request request) {
		String channel;
		try {
			channel = request.channel();
		}
		catch (RuntimeException ex) {
			channel = "";
		}
		return "{\"ok\":true,\"channel\":\"" + channel + "\",\"ts\":\"1762070400." + String.format("%06d", ts.incrementAndGet())
				+ "\",\"message\":{\"text\":\"echo\"}}";
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

}

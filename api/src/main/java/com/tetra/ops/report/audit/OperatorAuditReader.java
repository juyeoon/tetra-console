package com.tetra.ops.report.audit;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import org.springframework.stereotype.Component;

import com.tetra.ops.common.audit.AuditEvent;
import com.tetra.ops.common.time.Kst;
import com.tetra.ops.external.ObjectReader;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 감사 버킷에서 운영자(Console) 감사 줄을 기간(KST 날짜, 양끝 포함)으로 읽는다. 위치는 Firehose 규칙
 * {@code audit-logs/tenant_id=<12자 또는 none>/dt=YYYY-MM-DD/*.jsonl.gz}.
 * <ul>
 * <li>목록은 늘 audit-logs/로 시작하는 접두어로만 부른다(태스크 역할 s3:ListBucket 조건 s3:prefix = audit-logs/*).
 * audit-logs/ 아래 tenant_id= 폴더를 받고, 폴더마다 dt=from ~ to+1일 폴더를 읽는다</li>
 * <li>dt는 Firehose가 받은 시각(KST)이라 자정 근처 줄은 다음 날 폴더에 있을 수 있다 → to+1일까지 읽고 줄의 time으로 기간을 다시 거른다
 * (from 폴더에 든 전날 줄은 빠진다). 하루 넘게 늦게 옮겨진 줄은 없다고 본다</li>
 * <li>파일은 gzip(첫 두 바이트 1f 8b, 이어 붙은 gzip도 읽음) 또는 평문. 안은 JSON 한 줄씩 — 레코드 사이에만 줄바꿈(마지막 줄 끝 줄바꿈 없음)이라
 * 줄바꿈으로 나누고 빈 줄은 무시한다</li>
 * <li>"app":"console"인 감사 줄(log_type AUDIT)만 — Portal 줄은 넣지 않는다. 읽지 못한 줄(JSON 아님 · time 형식 오류)은 빼고 개수만 센다</li>
 * <li>결과는 일시 오름차순(같으면 audit_id). 목록 · 읽기는 동시에 {@value #CONCURRENCY}개까지</li>
 * </ul>
 * 읽은 줄은 메모리에 모은다(기간 상한 31일). S3 실패는 그대로 예외.
 */
@Component
public class OperatorAuditReader {

	/** 감사 줄 맨 위 접두어 — 목록 접두어는 모두 이것으로 시작한다 */
	static final String ROOT = "audit-logs/";

	static final String TENANT_FOLDER = ROOT + "tenant_id=";

	static final int CONCURRENCY = 8;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Comparator<OperatorAuditRecord> ORDER = Comparator.comparing(OperatorAuditRecord::instant)
		.thenComparing(OperatorAuditRecord::auditId, Comparator.nullsLast(Comparator.naturalOrder()));

	private final ObjectReader objects;

	public OperatorAuditReader(ObjectReader objects) {
		this.objects = objects;
	}

	/**
	 * @param records 기간 안 console 줄, 일시 오름차순
	 * @param files 읽은 파일 수
	 * @param skipped 읽지 못해 뺀 줄 수(JSON 아님 · time 형식 오류)
	 */
	record Result(List<OperatorAuditRecord> records, int files, int skipped) {
	}

	Result read(String bucket, LocalDate from, LocalDate to) {
		List<String> dayFolders = new ArrayList<>();
		for (String partition : objects.listFolders(bucket, ROOT)) {
			if (!partition.startsWith(TENANT_FOLDER)) {
				continue;
			}
			for (LocalDate day = from; !day.isAfter(to.plusDays(1)); day = day.plusDays(1)) {
				dayFolders.add(partition + "dt=" + day + "/");
			}
		}
		List<String> keys = new ArrayList<>();
		List<Parsed> parsed;
		try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY,
				Thread.ofPlatform().name("operator-audit-read-", 1).factory())) {
			all(pool, dayFolders, folder -> objects.listKeys(bucket, requireRoot(folder))).forEach(keys::addAll);
			parsed = all(pool, keys, key -> parse(objects.read(bucket, requireRoot(key)), from, to));
		}
		List<OperatorAuditRecord> records = new ArrayList<>();
		int skipped = 0;
		for (Parsed file : parsed) {
			records.addAll(file.records());
			skipped += file.skipped();
		}
		records.sort(ORDER);
		return new Result(records, keys.size(), skipped);
	}

	/** 파일 하나에서 읽은 줄(기간 안 console 줄)과 읽지 못한 줄 수 */
	record Parsed(List<OperatorAuditRecord> records, int skipped) {
	}

	static Parsed parse(byte[] content, LocalDate from, LocalDate to) {
		List<OperatorAuditRecord> records = new ArrayList<>();
		int skipped = 0;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(content), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				Line result = line(line, from, to);
				if (result.record() != null) {
					records.add(result.record());
				}
				else if (result.unreadable()) {
					skipped++;
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("감사 로그 파일을 읽지 못했습니다(gzip 형식 오류)", ex);
		}
		return new Parsed(records, skipped);
	}

	/** record = 넣을 줄, 아니면 null(unreadable이면 읽지 못한 줄, 아니면 Portal · 기간 밖처럼 대상이 아닌 줄) */
	private record Line(OperatorAuditRecord record, boolean unreadable) {

		static final Line NOT_TARGET = new Line(null, false);

		static final Line UNREADABLE = new Line(null, true);

	}

	private static Line line(String line, LocalDate from, LocalDate to) {
		JsonNode node;
		try {
			node = JSON.readTree(line);
		}
		catch (JacksonException ex) {
			return Line.UNREADABLE;
		}
		if (node == null || !node.isObject()) {
			return Line.UNREADABLE;
		}
		if (!AuditEvent.LOG_TYPE.equals(text(node, "log_type")) || !AuditEvent.APP.equals(text(node, "app"))) {
			return Line.NOT_TARGET;
		}
		OffsetDateTime time;
		try {
			String value = text(node, "time");
			if (value == null) {
				return Line.UNREADABLE;
			}
			time = OffsetDateTime.parse(value);
		}
		catch (DateTimeParseException ex) {
			return Line.UNREADABLE;
		}
		LocalDateTime kst = time.atZoneSameInstant(Kst.ZONE).toLocalDateTime();
		LocalDate day = kst.toLocalDate();
		if (day.isBefore(from) || day.isAfter(to)) {
			return Line.NOT_TARGET;
		}
		JsonNode actor = node.path("actor");
		JsonNode target = node.path("target");
		JsonNode changes = node.path("changes");
		return new Line(new OperatorAuditRecord(time.toInstant(), kst, text(node, "audit_id"), text(actor, "type"),
				text(actor, "id"), text(node, "action"), text(node, "path"), text(target, "type"), text(target, "id"),
				text(node, "tenant_id"), eventId(node.path("event_id")), text(node, "result"), text(node, "reason"),
				text(node, "ip"), changes.isObject() ? changes.toString() : null, text(node, "attempt"),
				text(node, "request_id")), false);
	}

	private static InputStream open(byte[] content) throws IOException {
		InputStream in = new ByteArrayInputStream(content);
		boolean gzip = content.length >= 2 && (content[0] & 0xff) == 0x1f && (content[1] & 0xff) == 0x8b;
		return gzip ? new GZIPInputStream(in) : in;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isString()) {
			return value.asString();
		}
		return value.isNumber() ? value.asString() : null;
	}

	private static Long eventId(JsonNode value) {
		if (value.isIntegralNumber()) {
			return value.asLong();
		}
		if (value.isString() && value.asString().matches("\\d{1,18}")) {
			return Long.valueOf(value.asString());
		}
		return null;
	}

	private static String requireRoot(String prefixOrKey) {
		if (!prefixOrKey.startsWith(ROOT)) {
			throw new IllegalStateException("감사 버킷은 " + ROOT + " 아래만 읽습니다: " + prefixOrKey);
		}
		return prefixOrKey;
	}

	/** 모두 동시에(풀 크기까지) 돌려 입력 순서대로. 하나가 실패하면 나머지를 멈추고 그 예외를 던진다 */
	private static <T, R> List<R> all(ExecutorService pool, List<T> inputs, Function<T, R> work) {
		List<Future<R>> futures = inputs.stream().map(input -> pool.submit(() -> work.apply(input))).toList();
		List<R> results = new ArrayList<>(futures.size());
		try {
			for (Future<R> future : futures) {
				results.add(future.get());
			}
		}
		catch (InterruptedException ex) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
			throw new IllegalStateException("감사 로그 읽기가 중단되었습니다.", ex);
		}
		catch (ExecutionException ex) {
			pool.shutdownNow();
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("감사 로그 읽기 실패", ex.getCause());
		}
		return results;
	}

}

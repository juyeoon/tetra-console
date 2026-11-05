package com.tetra.ops.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

import com.tetra.ops.external.ObjectReader;

/**
 * 메모리 S3(읽기 전용 쪽). 버킷 → 키 → 내용. 폴더 목록은 S3 delimiter '/'와 같이 prefix 바로 아래 접두어만. 부른 목록 접두어를 남긴다
 * (권한 조건 audit-logs/* 확인용). 여러 스레드에서 읽어도 된다.
 */
public class FakeObjectReader implements ObjectReader {

	private final Map<String, TreeMap<String, byte[]>> buckets = new ConcurrentHashMap<>();

	/** listFolders · listKeys에 넘어온 prefix(부른 순서) */
	public final List<String> listedPrefixes = new CopyOnWriteArrayList<>();

	/** 빈 버킷을 만든다(없는 버킷을 읽으면 NoSuchBucket 예외 — 버킷 이름이 틀린 것을 잡는다) */
	public FakeObjectReader bucket(String bucket) {
		synchronized (buckets) {
			buckets.computeIfAbsent(bucket, b -> new TreeMap<>());
		}
		return this;
	}

	public FakeObjectReader put(String bucket, String key, byte[] content) {
		synchronized (buckets) {
			buckets.computeIfAbsent(bucket, b -> new TreeMap<>()).put(key, content);
		}
		return this;
	}

	/** 줄들을 '\n'으로 잇고(마지막 줄 끝 줄바꿈 없음 — Firehose 형식) gzip해서 둔다 */
	public FakeObjectReader putGzipLines(String bucket, String key, String... lines) {
		return put(bucket, key, gzip(String.join("\n", lines)));
	}

	public void clear() {
		synchronized (buckets) {
			buckets.clear();
		}
		listedPrefixes.clear();
	}

	public static byte[] gzip(String text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
			gz.write(text.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	@Override
	public List<String> listFolders(String bucket, String prefix) {
		listedPrefixes.add(prefix);
		Set<String> folders = new TreeSet<>();
		for (String key : keys(bucket)) {
			if (key.startsWith(prefix)) {
				int slash = key.indexOf('/', prefix.length());
				if (slash >= 0) {
					folders.add(key.substring(0, slash + 1));
				}
			}
		}
		return new ArrayList<>(folders);
	}

	@Override
	public List<String> listKeys(String bucket, String prefix) {
		listedPrefixes.add(prefix);
		return keys(bucket).stream().filter(key -> key.startsWith(prefix)).toList();
	}

	@Override
	public byte[] read(String bucket, String key) {
		synchronized (buckets) {
			TreeMap<String, byte[]> objects = buckets.get(bucket);
			byte[] content = objects == null ? null : objects.get(key);
			if (content == null) {
				throw new IllegalStateException("NoSuchKey: " + key);
			}
			return content;
		}
	}

	private List<String> keys(String bucket) {
		synchronized (buckets) {
			TreeMap<String, byte[]> objects = buckets.get(bucket);
			if (objects == null) {
				throw new IllegalStateException("NoSuchBucket: " + bucket);
			}
			return new ArrayList<>(objects.keySet());
		}
	}

}

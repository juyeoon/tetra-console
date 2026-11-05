package com.tetra.ops.external;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 실행 전용(local 프로필). S3 대신 LocalObjectStorage와 같은 폴더 build/local-s3/{bucket}/{key}를 읽는다.
 * 운영자 접속기록 리포트를 로컬에서 보려면 감사 버킷 폴더 아래 audit-logs/tenant_id=…/dt=…/ 에 파일을 둔다. 없으면 빈 목록.
 */
@Component
@Profile("local")
class LocalObjectReader implements ObjectReader {

	@Override
	public List<String> listFolders(String bucket, String prefix) {
		Path dir = resolve(bucket, prefix);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> children = Files.list(dir)) {
			return children.filter(Files::isDirectory).map(p -> prefix + p.getFileName() + "/").sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public List<String> listKeys(String bucket, String prefix) {
		Path root = LocalObjectStorage.DIR.resolve(bucket);
		Path dir = resolve(bucket, prefix);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public byte[] read(String bucket, String key) {
		try {
			return Files.readAllBytes(resolve(bucket, key));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Path resolve(String bucket, String keyOrPrefix) {
		Path root = LocalObjectStorage.DIR.resolve(bucket);
		Path path = root.resolve(keyOrPrefix).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("키 형식 오류: " + keyOrPrefix);
		}
		return path;
	}

}

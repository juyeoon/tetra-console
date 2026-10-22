package com.tetra.ops.external;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬 실행 전용(local 프로필). S3에 올리지 않고 build/local-s3/{bucket}/{key}에 파일로 남긴다.
 * CSV 다운로드(presigned URL)는 로컬에서 열리지 않으므로 이 파일로 내용을 확인한다.
 */
@Component
@Profile("local")
class LocalObjectStorage implements ObjectStorage {

	private static final Logger log = LoggerFactory.getLogger(LocalObjectStorage.class);

	static final Path DIR = Path.of("build", "local-s3");

	@Override
	public void put(String bucket, String key, byte[] content, String contentType) {
		Path file = DIR.resolve(bucket).resolve(key).normalize();
		if (!file.startsWith(DIR.resolve(bucket))) {
			throw new IllegalArgumentException("키 형식 오류: " + key);
		}
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, content);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		log.info("[local] S3 업로드 대신 파일로 저장: s3://{}/{} → {}", bucket, key, file.toAbsolutePath());
	}

}

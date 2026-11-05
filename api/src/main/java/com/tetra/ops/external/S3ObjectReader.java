package com.tetra.ops.external;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * 권한은 태스크 역할(감사 버킷 s3:ListBucket — 조건 s3:prefix = audit-logs/* · audit-logs/* s3:GetObject). 목록은 1,000개씩 페이지로
 * 끝까지 받는다.
 */
@Component
@Profile("!local")
class S3ObjectReader implements ObjectReader {

	private static final String DELIMITER = "/";

	private final S3Client s3;

	S3ObjectReader(S3Client s3) {
		this.s3 = s3;
	}

	@Override
	public List<String> listFolders(String bucket, String prefix) {
		return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix).delimiter(DELIMITER))
			.commonPrefixes()
			.stream()
			.map(CommonPrefix::prefix)
			.toList();
	}

	@Override
	public List<String> listKeys(String bucket, String prefix) {
		return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream().map(S3Object::key).toList();
	}

	@Override
	public byte[] read(String bucket, String key) {
		return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
	}

}

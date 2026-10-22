package com.tetra.ops.external;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** 버킷 기본 암호화(SSE-S3)를 그대로 쓴다. 권한은 태스크 역할(tetra-audit-logs-{env}-* PutObject). */
@Component
@Profile("!local")
class S3ObjectStorage implements ObjectStorage {

	private final S3Client s3;

	S3ObjectStorage(S3Client s3) {
		this.s3 = s3;
	}

	@Override
	public void put(String bucket, String key, byte[] content, String contentType) {
		s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
	}

}

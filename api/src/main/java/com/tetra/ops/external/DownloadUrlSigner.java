package com.tetra.ops.external;

import java.time.Duration;
import java.time.Instant;

/** S3 presigned GET URL. 요청할 때마다 새로 만든다. */
public interface DownloadUrlSigner {

	/**
	 * @param downloadFileName 링크를 열면 이 이름의 파일로 저장되게 한다(Content-Disposition: attachment). ASCII만
	 */
	SignedUrl presignGet(String bucket, String key, Duration ttl, String downloadFileName);

	record SignedUrl(String url, Instant expiresAt) {
	}

}

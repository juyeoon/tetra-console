package com.tetra.ops.external;

/** S3 객체 쓰기. 같은 키면 덮어쓴다. 실패하면 예외를 던진다. */
public interface ObjectStorage {

	void put(String bucket, String key, byte[] content, String contentType);

}

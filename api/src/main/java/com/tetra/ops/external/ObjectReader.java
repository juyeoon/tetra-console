package com.tetra.ops.external;

import java.util.List;

/**
 * S3 객체 읽기(목록 · 내용). 운영자 접속기록 리포트가 감사 버킷(audit-logs/)을 읽는다. 실패하면 예외를 던진다.
 * prefix는 '/'로 끝나는 폴더 모양만 쓴다(권한 조건 s3:prefix가 접두어 단위).
 */
public interface ObjectReader {

	/** prefix 바로 아래 폴더(구분자 '/')들의 전체 접두어. 예: audit-logs/ → [audit-logs/tenant_id=none/, …] */
	List<String> listFolders(String bucket, String prefix);

	/** prefix 아래 모든 객체 키(하위 폴더 포함) */
	List<String> listKeys(String bucket, String prefix);

	/** 객체 내용 전체 */
	byte[] read(String bucket, String key);

}

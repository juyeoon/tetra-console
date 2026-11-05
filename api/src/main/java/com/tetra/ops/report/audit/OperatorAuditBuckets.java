package com.tetra.ops.report.audit;

import java.util.Optional;

/**
 * 운영자 접속기록 리포트가 읽는 감사 버킷 이름. Console에는 아직 감사 버킷 환경 변수가 없어(인프라 다음 계획) 설정
 * tetra.report.audit-bucket(REPORT_AUDIT_BUCKET)이 비면 운영자 리포트 버킷 이름에서 만든다:
 * {@code -operator-logs-} → {@code -audit-} (dev 규칙 tetra-dev-operator-logs-&lt;계정&gt; ↔ tetra-dev-audit-&lt;계정&gt;).
 */
final class OperatorAuditBuckets {

	static final String OPERATOR_PART = "-operator-logs-";

	static final String AUDIT_PART = "-audit-";

	private OperatorAuditBuckets() {
	}

	/** 설정값(앞뒤 공백 제거)이 있으면 그것, 없으면 운영자 리포트 버킷 이름에서 한 번 바꾼 이름. 둘 다 안 되면 empty */
	static Optional<String> resolve(String configured, String operatorBucket) {
		if (configured != null && !configured.isBlank()) {
			return Optional.of(configured.strip());
		}
		if (operatorBucket == null || operatorBucket.indexOf(OPERATOR_PART) < 0) {
			return Optional.empty();
		}
		return Optional.of(operatorBucket.strip().replaceFirst(OPERATOR_PART, AUDIT_PART));
	}

}

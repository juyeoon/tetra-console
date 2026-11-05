package com.tetra.ops.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 행위 한글 이름 표(AuditActions.NAMES, 운영자 접속기록 CSV '행위' 열)가 행위 코드 표와 같은 코드를 갖는지. 표에 코드를 더하고 이름을
 * 빠뜨리면(또는 지운 코드의 이름이 남으면) 여기서 실패한다.
 */
class AuditActionNamesTest {

	@Test
	void everyActionCodeHasShortKoreanNameAndNoExtras() {
		Set<String> codes = new HashSet<>();
		AuditActions.ENTRIES.forEach(entry -> codes.add(entry.action()));
		codes.add(AuditActions.API_REQUEST);

		assertThat(codes).hasSize(33); // 행위 코드 32개 + API_REQUEST
		assertThat(AuditActions.NAMES.keySet()).as("이름이 없는 코드 · 남는 이름").isEqualTo(codes);
		assertThat(AuditActions.NAMES.values()).allSatisfy(name -> assertThat(name).isNotBlank().hasSizeLessThanOrEqualTo(20));
	}

	@Test
	void nameExamplesAndUnknownCodeFallsBackToCode() {
		assertThat(AuditActions.name("AUTH_LOGIN")).isEqualTo("로그인");
		assertThat(AuditActions.name("DASHBOARD_VIEW")).isEqualTo("대시보드 조회");
		assertThat(AuditActions.name("ISSUANCE_CSV_DOWNLOAD")).isEqualTo("발급 이력 CSV 다운로드");
		assertThat(AuditActions.name("EVENT_APPROVE")).isEqualTo("이벤트 승인");
		assertThat(AuditActions.name("REPORT_GENERATE")).isEqualTo("리포트 생성");
		assertThat(AuditActions.name("API_REQUEST")).isEqualTo("기타 요청");
		// 다른 버전이 남긴 모르는 코드는 코드 그대로
		assertThat(AuditActions.name("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
		assertThat(AuditActions.name(null)).isNull();
	}

}

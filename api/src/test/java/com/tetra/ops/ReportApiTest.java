package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.tetra.ops.external.DownloadUrlSigner.SignedUrl;
import com.tetra.ops.external.MailGateway;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

/** OPS-09 리포트: 목록(최근 생성순) · 다운로드 링크 · 이메일 전송. 리포트 생성은 범위 밖이라 행은 테스트가 직접 넣는다. */
class ReportApiTest extends IntegrationTest {

	static final String BUCKET = "tetra-operator-logs-test";

	private long insert(String name, LocalDateTime periodStart, LocalDateTime periodEnd, LocalDateTime createdAt,
			String filePath) {
		data.jdbc().update("""
				INSERT INTO tetra_platform.operator_report_meta (name, period_start, period_end, category, created_at, file_path)
				VALUES (?, ?, ?, 'MONTHLY', ?, ?)""", name, periodStart, periodEnd, createdAt, filePath);
		return data.jdbc().queryForObject("SELECT MAX(report_id) FROM tetra_platform.operator_report_meta", Long.class);
	}

	private long september() {
		return insert("2026년 9월 운영 리포트", LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 30, 23, 59, 59),
				TestData.BASE, "reports/1.pdf");
	}

	@Test
	void listsNewestFirstWithoutFilePath() throws Exception {
		long older = september();
		long newer = insert("2026년 10월 운영 리포트", LocalDateTime.of(2026, 10, 1, 0, 0),
				LocalDateTime.of(2026, 10, 31, 23, 59, 59), TestData.BASE.plusDays(31), "reports/2.pdf");
		// 같은 시각이면 나중 리포트가 먼저
		long sameTime = insert("같은 시각", TestData.BASE, TestData.BASE, TestData.BASE, "reports/3.pdf");

		mvc.perform(get("/api/ops/reports").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[*].reportId").value(Matchers.contains((int) newer, (int) sameTime, (int) older)))
			.andExpect(jsonPath("$.items[2].name").value("2026년 9월 운영 리포트"))
			.andExpect(jsonPath("$.items[2].category").value("MONTHLY"))
			.andExpect(jsonPath("$.items[2].periodStart").value("2026-09-01T00:00:00+09:00"))
			.andExpect(jsonPath("$.items[2].periodEnd").value("2026-09-30T23:59:59+09:00"))
			.andExpect(jsonPath("$.items[2].createdAt").value("2026-10-01T10:00:00+09:00"))
			.andExpect(jsonPath("$.items[2].filePath").doesNotExist());
	}

	@Test
	void emptyListIsItemsArray() throws Exception {
		mvc.perform(get("/api/ops/reports").cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void downloadSignsOperatorBucketKeyForFiveMinutes() throws Exception {
		long id = september();
		when(urlSigner.presignGet(any(), any(), any(), any()))
			.thenReturn(new SignedUrl("https://signed.example/report", Instant.parse("2026-11-02T00:00:00Z")));

		mvc.perform(get("/api/ops/reports/{id}/download", id).cookie(operatorCookie()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.url").value("https://signed.example/report"))
			.andExpect(jsonPath("$.expiresAt").value("2026-11-02T09:00:00+09:00"));
		verify(urlSigner).presignGet(eq(BUCKET), eq("reports/1.pdf"), eq(Duration.ofMinutes(5)), eq("report-" + id + ".pdf"));
	}

	@Test
	void downloadAcceptsS3UriOfOperatorBucket() throws Exception {
		long id = insert("s3 경로", TestData.BASE, TestData.BASE, TestData.BASE, "s3://" + BUCKET + "/reports/2026/10.CSV");
		when(urlSigner.presignGet(any(), any(), any(), any()))
			.thenReturn(new SignedUrl("https://signed.example/report", Instant.parse("2026-11-02T00:00:00Z")));

		mvc.perform(get("/api/ops/reports/{id}/download", id).cookie(operatorCookie())).andExpect(status().isOk());
		verify(urlSigner).presignGet(eq(BUCKET), eq("reports/2026/10.CSV"), eq(Duration.ofMinutes(5)), eq("report-" + id + ".csv"));
	}

	@Test
	void downloadOfOtherBucketIsServerError() throws Exception {
		long id = insert("다른 버킷", TestData.BASE, TestData.BASE, TestData.BASE, "s3://tetra-audit-logs-test-" + A + "/reports/1.pdf");

		mvc.perform(get("/api/ops/reports/{id}/download", id).cookie(operatorCookie()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		verify(urlSigner, never()).presignGet(any(), any(), any(), any());
	}

	@Test
	void emailGoesToLoggedInOperatorWithoutSignedUrl() throws Exception {
		long id = september();

		mvc.perform(post("/api/ops/reports/{id}/email", id).cookie(operatorCookie())).andExpect(status().isNoContent());

		ArgumentCaptor<MailGateway.Mail> captor = ArgumentCaptor.forClass(MailGateway.Mail.class);
		verify(mail).send(captor.capture());
		MailGateway.Mail sent = captor.getValue();
		assertThat(sent.to()).isEqualTo(TestData.OPERATOR_EMAIL);
		assertThat(sent.subject()).isEqualTo("[Tetra] 운영 콘솔 리포트 — 2026년 9월 운영 리포트");
		assertThat(sent.textBody()).contains("홍길동님", "- 리포트명: 2026년 9월 운영 리포트", "- 기간: 2026.09.01 ~ 2026.09.30",
				"- 생성일: 2026.10.01", "운영 콘솔 > 로그 > 리포트")
			.doesNotContain("reports/1.pdf", "X-Amz", "https://");
		verify(urlSigner, never()).presignGet(any(), any(), any(), any());
	}

	@Test
	void emailFailureIs503() throws Exception {
		long id = september();
		when(mail.send(any())).thenReturn(false);
		mvc.perform(post("/api/ops/reports/{id}/email", id).cookie(operatorCookie()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("MAIL_SEND_FAILED"));

		when(mail.send(any())).thenThrow(new IllegalStateException("ses down"));
		mvc.perform(post("/api/ops/reports/{id}/email", id).cookie(operatorCookie()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("MAIL_SEND_FAILED"));
	}

	@Test
	void unknownOrMalformedReportId() throws Exception {
		for (var request : new org.springframework.test.web.servlet.RequestBuilder[] {
				get("/api/ops/reports/{id}/download", 999999).cookie(operatorCookie()),
				post("/api/ops/reports/{id}/email", 999999).cookie(operatorCookie()) }) {
			mvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
		}
		mvc.perform(get("/api/ops/reports/{id}/download", "abc").cookie(operatorCookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_INPUT"));
		verify(mail, never()).send(any());
	}

	@Test
	void requiresLogin() throws Exception {
		mvc.perform(get("/api/ops/reports")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ops/reports/{id}/download", 1)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/ops/reports/{id}/email", 1)).andExpect(status().isUnauthorized());
	}

}

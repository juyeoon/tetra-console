package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import com.tetra.ops.listener.LifecycleResultHandler;
import com.tetra.ops.support.IntegrationTest;

/**
 * 발급 이력 CSV가 공통 CsvWriter로 옮긴 뒤에도 바이트 단위로 같은지(2026-11-03). GOLDEN은 옮기기 전 코드(IssuanceHistoryStore 안의
 * BOM · CRLF · csvField · kst)로 같은 행을 만들어 뽑은 값이다 — 따옴표 · 쉼표 · 줄바꿈(LF · CR · CRLF) · 앞뒤 공백 · 빈 값 · 한글 · 이모지 ·
 * 수식처럼 보이는 값 · 큰 번호 · 밀리초 경계.
 */
class IssuanceHistoryCsvBytesTest extends IntegrationTest {

	static final long EVENT_ID = 4402;

	static final String GOLDEN_SHA256 = "3fc32d9483b4cbc8050f88c4d0007bc82d6149152d9136ffc0d9068ad2942ae5";

	static final String GOLDEN_BASE64 = "77u/aXNzdWFuY2VfaWQsdXNlcl9pZCx0aWNrZXRfbnVtYmVyLHJlc3VsdCxzZXNzaW9uX2lzc3VlZF9hdCxxdWV1ZV9lbnRl"
			+ "cmVkX2F0LGlzc3VlZF9hdA0KMSx1c2VyLXBsYWluLDEsU1VDQ0VTUywyMDI2LTExLTAxVDAwOjAwOjAwLjAwMCswOTowMCwy"
			+ "MDI2LTExLTAxVDAwOjAwOjAwLjAwMCswOTowMCwyMDI2LTExLTAxVDAwOjAwOjAwLjAwMCswOTowMA0KMiwi7L2k66eILOye"
			+ "iOydjCIsMixGQUlMRURfU09MRE9VVCwyMDI2LTExLTAxVDIzOjU5OjU5Ljk5OSswOTowMCwyMDI2LTExLTAxVDIzOjU5OjU5"
			+ "Ljk5OSswOTowMCwyMDI2LTExLTAxVDIzOjU5OjU5Ljk5OSswOTowMA0KMywi65Sw7Ji07ZGcIiLsnojsnYwiLDMsU1VDQ0VT"
			+ "UywyMDI2LTAyLTI4VDEyOjM0OjU2LjUwMCswOTowMCwyMDI2LTAyLTI4VDEyOjM0OjU2LjUwMCswOTowMCwyMDI2LTAyLTI4"
			+ "VDEyOjM0OjU2LjUwMCswOTowMA0KNCwi7KSECuuwlOq/iCIsNCxTVUNDRVNTLDIwMjYtMTEtMDFUMTA6MDA6MDAuMDAxKzA5"
			+ "OjAwLDIwMjYtMTEtMDFUMTA6MDA6MDAuMDAxKzA5OjAwLDIwMjYtMTEtMDFUMTA6MDA6MDAuMDAxKzA5OjAwDQo1LCLsupDr"
			+ "pqzsp4AN66as7YS0Iiw1LEZBSUxFRF9TT0xET1VULDIwMjYtMTEtMDFUMTA6MDA6MDAuMDEwKzA5OjAwLDIwMjYtMTEtMDFU"
			+ "MTA6MDA6MDAuMDEwKzA5OjAwLDIwMjYtMTEtMDFUMTA6MDA6MDAuMDEwKzA5OjAwDQo2LCJjcmxmDQplbmQiLDYsU1VDQ0VT"
			+ "UywyMDI2LTExLTAxVDEwOjAwOjAwLjEwMCswOTowMCwyMDI2LTExLTAxVDEwOjAwOjAwLjEwMCswOTowMCwyMDI2LTExLTAx"
			+ "VDEwOjAwOjAwLjEwMCswOTowMA0KNywg7JWe65KkIOqzteuwsSAsNyxTVUNDRVNTLDIwMjYtMTItMzFUMjM6NTk6NTkuMDAw"
			+ "KzA5OjAwLDIwMjYtMTItMzFUMjM6NTk6NTkuMDAwKzA5OjAwLDIwMjYtMTItMzFUMjM6NTk6NTkuMDAwKzA5OjAwDQo4LOyd"
			+ "tOuqqOyngPCfmIAsOCxTVUNDRVNTLDIwMjYtMDEtMDFUMDA6MDA6MDAuMDAwKzA5OjAwLDIwMjYtMDEtMDFUMDA6MDA6MDAu"
			+ "MDAwKzA5OjAwLDIwMjYtMDEtMDFUMDA6MDA6MDAuMDAwKzA5OjAwDQo5LD0xKzIsOSxGQUlMRURfU09MRE9VVCwyMDI2LTEx"
			+ "LTAxVDEwOjAwOjAxLjAwMCswOTowMCwyMDI2LTExLTAxVDEwOjAwOjAxLjAwMCswOTowMCwyMDI2LTExLTAxVDEwOjAwOjAx"
			+ "LjAwMCswOTowMA0KMTAsLDEwLFNVQ0NFU1MsMjAyNi0xMS0wMVQxMDowMDowMi4wMDArMDk6MDAsMjAyNi0xMS0wMVQxMDow"
			+ "MDowMi4wMDArMDk6MDAsMjAyNi0xMS0wMVQxMDowMDowMi4wMDArMDk6MDANCjExLCIiIiIiIiwxMSxTVUNDRVNTLDIwMjYt"
			+ "MTEtMDFUMTA6MDA6MDMuMDAwKzA5OjAwLDIwMjYtMTEtMDFUMTA6MDA6MDMuMDAwKzA5OjAwLDIwMjYtMTEtMDFUMTA6MDA6"
			+ "MDMuMDAwKzA5OjAwDQo5MDA3MTk5MjU0NzQwOTkzLGJpZy1pZCwyMTQ3NDgzNjQ3LFNVQ0NFU1MsMjAyNi0xMS0wMVQxMDow"
			+ "MDowNC45OTkrMDk6MDAsMjAyNi0xMS0wMVQxMDowMDowNC45OTkrMDk6MDAsMjAyNi0xMS0wMVQxMDowMDowNC45OTkrMDk6"
			+ "MDANCg==";

	@Autowired
	LifecycleResultHandler handler;

	@BeforeEach
	void setUp() {
		data.tenant(A, "A사", "ACTIVE", "READY");
		data.event(EVENT_ID, A).approved("HOSTING", "OPEN", "ih000002").insert();
	}

	private void issuance(long issuanceId, String userId, long ticket, String result, String at) {
		data.jdbc().update("""
				INSERT INTO t_%s.issuance_history (issuance_id, event_id, tenant_id, user_id, ticket_number,
				    session_issued_at, queue_entered_at, issued_at, result) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"""
			.formatted(A), issuanceId, EVENT_ID, A, userId, ticket, at, at, at, result);
	}

	@Test
	void csvBytesAreUnchanged() throws Exception {
		issuance(1, "user-plain", 1, "SUCCESS", "2026-11-01 00:00:00");
		issuance(2, "콤마,있음", 2, "FAILED_SOLDOUT", "2026-11-01 23:59:59.999");
		issuance(3, "따옴표\"있음", 3, "SUCCESS", "2026-02-28 12:34:56.5");
		issuance(4, "줄\n바꿈", 4, "SUCCESS", "2026-11-01 10:00:00.001");
		issuance(5, "캐리지\r리턴", 5, "FAILED_SOLDOUT", "2026-11-01 10:00:00.010");
		issuance(6, "crlf\r\nend", 6, "SUCCESS", "2026-11-01 10:00:00.100");
		issuance(7, " 앞뒤 공백 ", 7, "SUCCESS", "2026-12-31 23:59:59");
		issuance(8, "이모지😀", 8, "SUCCESS", "2026-01-01 00:00:00.000");
		issuance(9, "=1+2", 9, "FAILED_SOLDOUT", "2026-11-01 10:00:01");
		issuance(10, "", 10, "SUCCESS", "2026-11-01 10:00:02");
		issuance(11, "\"\"", 11, "SUCCESS", "2026-11-01 10:00:03");
		issuance(9007199254740993L, "big-id", 2147483647L, "SUCCESS", "2026-11-01 10:00:04.999");

		handler.handle(LifecycleResultHandlerTest.message(LifecycleResultHandlerTest.EVENT_END, "SUCCEEDED", EVENT_ID, A,
				null, null));

		ArgumentCaptor<byte[]> csv = ArgumentCaptor.forClass(byte[].class);
		verify(storage).put(eq("tetra-audit-logs-test-" + A), eq("issuance-history/4402.csv"), csv.capture(),
				eq("text/csv; charset=utf-8"));
		byte[] bytes = csv.getValue();
		String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		assertThat(Base64.getEncoder().encodeToString(bytes)).as("sha256=" + sha256).isEqualTo(GOLDEN_BASE64);
		assertThat(sha256).isEqualTo(GOLDEN_SHA256);
	}

}

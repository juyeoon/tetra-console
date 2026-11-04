package com.tetra.ops.common.audit;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Console 감사 행위 표(2026-11-05 결정) — 허용 목록이 아니라 /api/ops/** 요청을 <b>전부</b> 남긴다(조회 GET 포함).
 * 행위는 MVC가 고른 경로 패턴(BEST_MATCHING_PATTERN_ATTRIBUTE) + 메서드로 정한다. 표에 없는 엔드포인트, 패턴이 없는 요청
 * (보안 체인에서 끝난 401 · 403, 405, 없는 경로 404 — 화면 파일 처리기 "/**"로 떨어짐)은 {@link #API_REQUEST} + 경로.
 * 경로 · 메서드를 바꾸거나 엔드포인트를 더하면 이 표도 같이 바꾼다(AuditLogApiTest가 컨트롤러 매핑과 표를 맞춰 본다).
 */
public final class AuditActions {

	/** 표에 없는 요청의 행위 코드 — 감사 줄에 path(쿼리 뺀 경로)를 함께 남긴다. */
	public static final String API_REQUEST = "API_REQUEST";

	/** idFrom 값 — 대상 id = 로그인한 운영자(JWT sub). */
	public static final String PRINCIPAL = "@principal";

	/**
	 * 표 한 줄. targetType = 대상 종류(없으면 null).
	 * idFrom = 대상 id를 어디서: 경로 변수 이름 · {@link #PRINCIPAL} · null(행위별 보강 값만 — 로그인 · 테넌트 등록 · 리포트 수동 생성).
	 */
	public record Entry(HttpMethod method, String pattern, String action, String targetType, String idFrom) {
	}

	/** 표에서 찾은 줄(없으면 null = API_REQUEST) + 경로 변수 값. */
	public record Match(Entry entry, Map<String, String> variables) {

		/** idFrom이 경로 변수일 때 그 값. */
		String pathVariable() {
			String from = entry == null ? null : entry.idFrom();
			return from == null || from.equals(PRINCIPAL) ? null : variables.get(from);
		}

	}

	private static final String OPS = "/api/ops";

	public static final List<Entry> ENTRIES = List.of(
			// 인증 · 계정 (OPS-01 · 10)
			new Entry(HttpMethod.POST, OPS + "/auth/login", "AUTH_LOGIN", AuditContext.OPERATOR, null),
			new Entry(HttpMethod.POST, OPS + "/auth/logout", "AUTH_LOGOUT", AuditContext.OPERATOR, PRINCIPAL),
			new Entry(HttpMethod.GET, OPS + "/me", "ACCOUNT_VIEW", AuditContext.OPERATOR, PRINCIPAL),
			// 홈 · 목록 (OPS-02 · 03 · 04)
			new Entry(HttpMethod.GET, OPS + "/dashboard", "DASHBOARD_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/applications", "APPLICATION_LIST_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/live-events", "LIVE_EVENT_LIST_VIEW", null, null),
			// 이벤트 (OPS-03-1 · M1 · M2 · 2, 04-1 · 2 · 3)
			new Entry(HttpMethod.GET, OPS + "/events/{eventId}", "EVENT_VIEW", AuditContext.EVENT, "eventId"),
			new Entry(HttpMethod.POST, OPS + "/events/{eventId}/approve", "EVENT_APPROVE", AuditContext.EVENT, "eventId"),
			new Entry(HttpMethod.POST, OPS + "/events/{eventId}/reject", "EVENT_REJECT", AuditContext.EVENT, "eventId"),
			new Entry(HttpMethod.GET, OPS + "/events/{eventId}/result", "EVENT_RESULT_VIEW", AuditContext.EVENT,
					"eventId"),
			new Entry(HttpMethod.GET, OPS + "/events/{eventId}/result/history-csv", "ISSUANCE_CSV_DOWNLOAD",
					AuditContext.EVENT, "eventId"),
			new Entry(HttpMethod.GET, OPS + "/events/{eventId}/application-summary", "APPLICATION_SUMMARY_VIEW",
					AuditContext.EVENT, "eventId"),
			// 모니터링 (OPS-02 카드 · OPS-05)
			new Entry(HttpMethod.GET, OPS + "/monitoring/overview", "MONITORING_OVERVIEW_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/monitoring/platform", "TRAFFIC_PLATFORM_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/monitoring/events", "TRAFFIC_EVENT_LIST_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/monitoring/events/{eventId}", "TRAFFIC_EVENT_VIEW", AuditContext.EVENT,
					"eventId"),
			new Entry(HttpMethod.GET, OPS + "/monitoring/events/{eventId}/executions", "EXECUTION_VIEW",
					AuditContext.EVENT, "eventId"),
			// 알림 (OPS-08 · 사이드바) — 검색어 q는 남기지 않는다(쿼리는 어디에도 넣지 않음)
			new Entry(HttpMethod.GET, OPS + "/notifications", "NOTIFICATION_LIST_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/notifications/unread-count", "NOTIFICATION_COUNT_VIEW", null, null),
			new Entry(HttpMethod.PATCH, OPS + "/notifications/{notificationId}/read", "NOTIFICATION_READ",
					AuditContext.NOTIFICATION, "notificationId"),
			new Entry(HttpMethod.PATCH, OPS + "/notifications/read-all", "NOTIFICATION_READ_ALL", null, null),
			// 리포트 (OPS-09 · 수동 생성)
			new Entry(HttpMethod.GET, OPS + "/reports", "REPORT_LIST_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/reports/{reportId}/download", "REPORT_DOWNLOAD", AuditContext.REPORT,
					"reportId"),
			new Entry(HttpMethod.POST, OPS + "/reports/{reportId}/email", "REPORT_MAIL", AuditContext.REPORT,
					"reportId"),
			new Entry(HttpMethod.POST, OPS + "/reports/generate", "REPORT_GENERATE", AuditContext.EVENT, null),
			// 테넌트 (OPS-06 · 07) — 이메일 중복 확인의 이메일(쿼리)은 남기지 않는다
			new Entry(HttpMethod.GET, OPS + "/tenants", "TENANT_LIST_VIEW", null, null),
			new Entry(HttpMethod.GET, OPS + "/tenants/email-availability", "TENANT_EMAIL_CHECK", null, null),
			new Entry(HttpMethod.POST, OPS + "/tenants", "TENANT_REGISTER", AuditContext.TENANT, null),
			new Entry(HttpMethod.GET, OPS + "/tenants/{tenantId}", "TENANT_VIEW", AuditContext.TENANT, "tenantId"),
			new Entry(HttpMethod.PATCH, OPS + "/tenants/{tenantId}", "TENANT_UPDATE", AuditContext.TENANT, "tenantId"),
			new Entry(HttpMethod.GET, OPS + "/tenants/{tenantId}/events", "TENANT_EVENTS_VIEW", AuditContext.TENANT,
					"tenantId"),
			new Entry(HttpMethod.GET, OPS + "/tenants/{tenantId}/resources", "TENANT_RESOURCES_VIEW",
					AuditContext.TENANT, "tenantId"));

	private AuditActions() {
	}

	/** 표의 줄(못 찾으면 entry null = API_REQUEST) + 경로 변수. 패턴이 없으면(MVC까지 오지 않음) 경로 변수도 없다. */
	public static Match match(HttpServletRequest request) {
		Object best = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (best == null) {
			return new Match(null, Map.of());
		}
		String pattern = best.toString();
		String method = request.getMethod();
		for (Entry entry : ENTRIES) {
			if (entry.method().matches(method) && entry.pattern().equals(pattern)) {
				return new Match(entry, uriVariables(request));
			}
		}
		return new Match(null, Map.of());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> uriVariables(HttpServletRequest request) {
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
	}

}

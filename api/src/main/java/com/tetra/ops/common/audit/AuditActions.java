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
			// 대상은 분류별 보강 값만: EVENT_RESULT → EVENT(본문 eventId), OPERATOR_AUDIT → REPORT(만든 메타 id)
			new Entry(HttpMethod.POST, OPS + "/reports/generate", "REPORT_GENERATE", null, null),
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

	/**
	 * 행위 코드의 짧은 한글 이름(운영자 접속기록 리포트 CSV '행위' 열). 위 표의 코드 전부 + {@link #API_REQUEST}.
	 * 표에 코드를 더하면 여기에도 더한다(AuditActionNamesTest가 빠진 코드 · 남는 이름을 잡는다).
	 */
	public static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry("AUTH_LOGIN", "로그인"),
			Map.entry("AUTH_LOGOUT", "로그아웃"),
			Map.entry("ACCOUNT_VIEW", "내 정보 조회"),
			Map.entry("DASHBOARD_VIEW", "대시보드 조회"),
			Map.entry("APPLICATION_LIST_VIEW", "신청 목록 조회"),
			Map.entry("LIVE_EVENT_LIST_VIEW", "진행 이벤트 목록 조회"),
			Map.entry("EVENT_VIEW", "이벤트 상세 조회"),
			Map.entry("EVENT_APPROVE", "이벤트 승인"),
			Map.entry("EVENT_REJECT", "이벤트 반려"),
			Map.entry("EVENT_RESULT_VIEW", "이벤트 결과 조회"),
			Map.entry("ISSUANCE_CSV_DOWNLOAD", "발급 이력 CSV 다운로드"),
			Map.entry("APPLICATION_SUMMARY_VIEW", "신청 내용 조회"),
			Map.entry("MONITORING_OVERVIEW_VIEW", "홈 모니터링 조회"),
			Map.entry("TRAFFIC_PLATFORM_VIEW", "플랫폼 트래픽 조회"),
			Map.entry("TRAFFIC_EVENT_LIST_VIEW", "트래픽 이벤트 목록 조회"),
			Map.entry("TRAFFIC_EVENT_VIEW", "이벤트 트래픽 조회"),
			Map.entry("EXECUTION_VIEW", "자동화 실행 조회"),
			Map.entry("NOTIFICATION_LIST_VIEW", "알림 목록 조회"),
			Map.entry("NOTIFICATION_COUNT_VIEW", "안 읽은 알림 수 조회"),
			Map.entry("NOTIFICATION_READ", "알림 읽음"),
			Map.entry("NOTIFICATION_READ_ALL", "알림 모두 읽음"),
			Map.entry("REPORT_LIST_VIEW", "리포트 목록 조회"),
			Map.entry("REPORT_DOWNLOAD", "리포트 다운로드"),
			Map.entry("REPORT_MAIL", "리포트 메일 전송"),
			Map.entry("REPORT_GENERATE", "리포트 생성"),
			Map.entry("TENANT_LIST_VIEW", "테넌트 목록 조회"),
			Map.entry("TENANT_EMAIL_CHECK", "테넌트 이메일 중복 확인"),
			Map.entry("TENANT_REGISTER", "테넌트 등록"),
			Map.entry("TENANT_VIEW", "테넌트 상세 조회"),
			Map.entry("TENANT_UPDATE", "테넌트 정보 수정"),
			Map.entry("TENANT_EVENTS_VIEW", "테넌트 이벤트 조회"),
			Map.entry("TENANT_RESOURCES_VIEW", "테넌트 리소스 조회"),
			Map.entry(API_REQUEST, "기타 요청"));

	private AuditActions() {
	}

	/** 행위 코드의 한글 이름. 표에 없는 코드(다른 버전이 남긴 줄 등)는 코드 그대로, null이면 null */
	public static String name(String action) {
		return action == null ? null : NAMES.getOrDefault(action, action);
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

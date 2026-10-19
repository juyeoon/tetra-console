package com.tetra.ops.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 테스트 데이터 준비·확인. 앱 계정이 아니라 root로 직접 넣고 읽는다(앱 코드를 거치지 않음).
 * 기본 seed(운영자 1, 플랜 S/M/L/XL, 템플릿 1)는 지우지 않는다.
 */
public final class TestData {

	public static final long OPERATOR_ID = 1L;

	public static final String OPERATOR_EMAIL = "operator@tetra-operation.com";

	public static final String OPERATOR_PASSWORD = "ops-password-1";

	/** 신청 시각 기준. 이벤트 기간은 지금보다 뒤(FUTURE)로 둔다 (chk_auth_key_expires: hosting_end_at > 승인 시각). */
	public static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 10, 0);

	/** event.layout_config 기본값(chk_event_layout_config 형식). */
	public static final String LAYOUT_CONFIG = """
			{"title":{"h":"CENTER","v":"TOP"},"banner":{"h":"CENTER","v":"TOP"},
			 "couponList":{"h":"CENTER","v":"MIDDLE"},"issueButton":{"h":"CENTER","v":"BOTTOM"},"theme":"WHITE"}""";

	public static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(30).withHour(10).withMinute(0).withSecond(0).withNano(0);

	private static final List<String> TENANT_TABLES = List.of("event_status_log", "event_auth_key",
			"issuance_history", "event_result_summary", "coupon", "event_billing", "tenant_notification",
			"tenant_report_meta", "tenant_slack_config", "event");

	private static final List<String> PLATFORM_TABLES = List.of("tenant_status_log", "operator_notification",
			"operator_report_meta", "event_registry", "tenant");

	private final JdbcTemplate jdbc;

	private TestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	private static TestData shared;

	/** root 커넥션 1개를 모든 테스트가 같이 쓴다. */
	public static synchronized TestData shared() {
		if (shared == null) {
			shared = connect();
		}
		return shared;
	}

	private static TestData connect() {
		try {
			Connection connection = MySqlTestDatabase.rootConnection();
			return new TestData(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public JdbcTemplate jdbc() {
		return jdbc;
	}

	public void reset() {
		jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
		try {
			for (String tenantId : new String[] { MySqlTestDatabase.TENANT_A, MySqlTestDatabase.TENANT_B }) {
				for (String table : TENANT_TABLES) {
					jdbc.update("DELETE FROM t_" + tenantId + "." + table);
				}
			}
			for (String table : PLATFORM_TABLES) {
				jdbc.update("DELETE FROM tetra_platform." + table);
			}
		}
		finally {
			jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
		}
	}

	// ---------------------------------------------------------------- tenant

	public void tenant(String tenantId, String name, String accountStatus, String provisioningStatus) {
		tenant(tenantId, name, name.toLowerCase() + "@" + tenantId + ".example.com", accountStatus, provisioningStatus,
				BASE);
	}

	public void tenant(String tenantId, String name, String email, String accountStatus, String provisioningStatus,
			LocalDateTime createdAt) {
		jdbc.update("""
				INSERT INTO tetra_platform.tenant (tenant_id, name, root_domain, created_at, email, password, contact_name,
				                                   account_status, provisioning_status)
				VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)""", tenantId, name, name.toLowerCase() + ".example.com",
				createdAt, email, name + " 담당자", accountStatus, provisioningStatus);
	}

	public void tenantStatusLog(String tenantId, LocalDateTime changedAt, String prev, String next, String reason,
			Long relatedEventId) {
		jdbc.update("""
				INSERT INTO tetra_platform.tenant_status_log (tenant_id, changed_at, prev_status, new_status, reason, related_event_id)
				VALUES (?, ?, ?, ?, ?, ?)""", tenantId, changedAt, prev, next, reason, relatedEventId);
	}

	// ---------------------------------------------------------------- event

	/** registry + 테넌트 스키마 event를 같은 상태로 넣는다. APPROVED면 hosting/event 상태가 필요하다. */
	public EventBuilder event(long eventId, String tenantId) {
		return new EventBuilder(eventId, tenantId);
	}

	public final class EventBuilder {

		private final long eventId;

		private final String tenantId;

		private String schemaTenantId;

		private String rowTenantId;

		private String name;

		private String applicationStatus = "APPLIED";

		private String hostingStatus;

		private String eventStatus;

		private String slug;

		private LocalDateTime appliedAt = BASE;

		private LocalDateTime processedAt;

		private LocalDateTime startAt = FUTURE;

		private boolean registryOnly;

		private EventBuilder(long eventId, String tenantId) {
			this.eventId = eventId;
			this.tenantId = tenantId;
			this.schemaTenantId = tenantId;
			this.rowTenantId = tenantId;
			this.name = "이벤트 " + eventId;
		}

		public EventBuilder name(String name) {
			this.name = name;
			return this;
		}

		public EventBuilder applied() {
			this.applicationStatus = "APPLIED";
			return this;
		}

		public EventBuilder status(String applicationStatus) {
			this.applicationStatus = applicationStatus;
			return this;
		}

		public EventBuilder approved(String hostingStatus, String eventStatus, String slug) {
			this.applicationStatus = "APPROVED";
			this.hostingStatus = hostingStatus;
			this.eventStatus = eventStatus;
			this.slug = slug;
			this.processedAt = appliedAt.plusHours(1);
			return this;
		}

		public EventBuilder appliedAt(LocalDateTime appliedAt) {
			this.appliedAt = appliedAt;
			return this;
		}

		public EventBuilder processedAt(LocalDateTime processedAt) {
			this.processedAt = processedAt;
			return this;
		}

		public EventBuilder startAt(LocalDateTime startAt) {
			this.startAt = startAt;
			return this;
		}

		/** event 행을 다른 테넌트 스키마에 넣는다 (교차 접근 테스트). */
		public EventBuilder inSchemaOf(String schemaTenantId) {
			this.schemaTenantId = schemaTenantId;
			return this;
		}

		/** event 행의 tenant_id를 다르게 넣는다 (이중 확인 테스트). */
		public EventBuilder rowTenantId(String rowTenantId) {
			this.rowTenantId = rowTenantId;
			return this;
		}

		public EventBuilder registryOnly() {
			this.registryOnly = true;
			return this;
		}

		public void insert() {
			String rejectReason = "REJECTED".equals(applicationStatus) ? "기존 반려" : null;
			String banner = List.of("APPLYING", "APPLY_FAILED").contains(applicationStatus) ? null
					: "assets/banners/" + eventId + "/banner.png";
			LocalDateTime hostingStartAt = startAt.minusHours(2);
			LocalDateTime endAt = startAt.plusHours(3);
			LocalDateTime hostingEndAt = endAt.plusHours(2);
			jdbc.update("""
					INSERT INTO tetra_platform.event_registry (event_id, tenant_id, name, event_type, traffic_plan_code,
					    hosting_start_at, start_at, applied_at, processed_at, application_status, event_status, hosting_status)
					VALUES (?, ?, ?, 'COUPON', 'M', ?, ?, ?, ?, ?, ?, ?)""", eventId, tenantId, name, hostingStartAt,
					startAt, appliedAt, processedAt, applicationStatus, eventStatus, hostingStatus);
			if (registryOnly) {
				return;
			}
			jdbc.update("""
					INSERT INTO t_%s.event (event_id, tenant_id, name, event_type, template_id, layout_config, banner_image_path,
					    start_at, end_at, hosting_start_at, hosting_end_at, endpoint_url, traffic_plan_code, stock_count,
					    slug, applied_at, processed_by, processed_at, reject_reason, application_status, hosting_status, event_status)
					VALUES (?, ?, ?, 'COUPON', 1, ?, ?, ?, ?, ?, ?, 'https://shop.example.com/event', 'M', 10000,
					    ?, ?, ?, ?, ?, ?, ?, ?)""".formatted(schemaTenantId), eventId, rowTenantId, name, LAYOUT_CONFIG, banner, startAt,
					endAt, hostingStartAt, hostingEndAt, slug, appliedAt,
					processedAt == null ? null : OPERATOR_ID, processedAt, rejectReason, applicationStatus,
					hostingStatus, eventStatus);
		}

	}

	public void activeKey(String tenantId, long eventId, int version, String pem) {
		jdbc.update("""
				INSERT INTO t_%s.event_auth_key (event_id, tenant_id, public_key, key_version, status, issued_at, expires_at)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)""".formatted(tenantId), eventId, tenantId, pem, version, BASE,
				BASE.plusDays(30));
	}

	public void statusLog(String tenantId, long eventId, String type, String prev, String next,
			LocalDateTime changedAt, String detail) {
		jdbc.update("""
				INSERT INTO t_%s.event_status_log (event_id, tenant_id, status_type, prev_status, new_status, changed_at, detail)
				VALUES (?, ?, ?, ?, ?, ?, ?)""".formatted(tenantId), eventId, tenantId, type, prev, next, changedAt,
				detail);
	}

	// ---------------------------------------------------------------- read

	public Map<String, Object> registry(long eventId) {
		return jdbc.queryForMap("SELECT * FROM tetra_platform.event_registry WHERE event_id = ?", eventId);
	}

	public Map<String, Object> eventRow(String tenantId, long eventId) {
		return jdbc.queryForMap("SELECT * FROM t_" + tenantId + ".event WHERE event_id = ?", eventId);
	}

	public Map<String, Object> tenantRow(String tenantId) {
		return jdbc.queryForMap("SELECT * FROM tetra_platform.tenant WHERE tenant_id = ?", tenantId);
	}

	public List<Map<String, Object>> statusLogs(String tenantId, long eventId) {
		return jdbc.queryForList(
				"SELECT * FROM t_" + tenantId + ".event_status_log WHERE event_id = ? ORDER BY log_id", eventId);
	}

	public List<Map<String, Object>> authKeys(String tenantId, long eventId) {
		return jdbc.queryForList("SELECT * FROM t_" + tenantId + ".event_auth_key WHERE event_id = ?", eventId);
	}

	public List<Map<String, Object>> tenantStatusLogs(String tenantId) {
		return jdbc.queryForList(
				"SELECT * FROM tetra_platform.tenant_status_log WHERE tenant_id = ? ORDER BY log_id", tenantId);
	}

	public long count(String sql, Object... args) {
		Long value = jdbc.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

}

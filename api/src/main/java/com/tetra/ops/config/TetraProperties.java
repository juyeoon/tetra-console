package com.tetra.ops.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 모든 값은 환경 변수(ConfigMap·Secret)로 들어온다 (CLAUDE.md §10).
 * 비밀 값(auth.jwtSecret, passwordLink.hmacSecret, dbMigration.password)은 기본값이 없고, 비어 있으면 기동이 실패한다.
 * slack.botToken만 예외 — Slack 발송이 켜져 있을 때만 필수다(Slack).
 * toString에 비밀 값이 나오지 않도록 비밀을 담은 record는 toString을 덮어쓴다.
 */
@Validated
@ConfigurationProperties(prefix = "tetra")
public record TetraProperties(
		@NotBlank String env,
		@Valid @NotNull Aws aws,
		@Valid @NotNull Sfn sfn,
		@Valid @NotNull Sqs sqs,
		@Valid @NotNull Mail mail,
		@Valid @NotNull Portal portal,
		@Valid @NotNull @DefaultValue Console console,
		@Valid @NotNull @DefaultValue Csv csv,
		@Valid @NotNull @DefaultValue Approve approve,
		@Valid @NotNull @DefaultValue Monitoring monitoring,
		@Valid @NotNull @DefaultValue Alerts alerts,
		@Valid @NotNull @DefaultValue Slack slack,
		@Valid @NotNull @DefaultValue Report report,
		@Valid @NotNull Auth auth,
		@Valid @NotNull PasswordLink passwordLink,
		@Valid @NotNull DbMigration dbMigration,
		/** 이벤트 도메인 루트. 이벤트 주소는 {slug}.{tenant_id}.{platformDomain} (ops-workflows 10-17 결정). 기본값은 application.yml */
		@NotBlank String platformDomain) {

	/** Slack 채널 ID 형식(비어 있으면 Slack 발송이 꺼져 있을 때만 통과 — Slack.isCompleteWhenEnabled) */
	static final String SLACK_CHANNEL_ID = "[A-Z0-9]{0,20}";

	static final String SLACK_CHANNEL_ID_MESSAGE = "Slack 채널 ID(대문자 · 숫자 20자 이내, 예: C0123456789)여야 합니다.";

	public record Aws(@NotBlank String region) {
	}

	/**
	 * lifecycle: 수명 주기 결과(WF2-1 · WF2-2 · WF4)를 나누는 상태 머신 이름. 실행 이름은 스케줄러가 정하므로
	 * 결과 메시지의 detail.stateMachineArn 이름으로 흐름을 고른다. 기본값은 application.yml(tetra-{env}-…).
	 */
	public record Sfn(@NotBlank String approveStateMachineArn, @NotBlank String tenantStateMachineArn,
			@Valid @NotNull Lifecycle lifecycle) {
	}

	public record Lifecycle(@NotBlank String hostingStartName, @NotBlank String eventOpenName,
			@NotBlank String eventEndName) {
	}

	public record Sqs(@NotBlank String approveResultQueue, @NotBlank String tenantResultQueue,
			@NotBlank String eventLifecycleResultQueue) {
	}

	public record Mail(@NotBlank String from) {
	}

	public record Portal(@NotBlank String baseUrl) {
	}

	/** 운영 콘솔 주소(선택, 예: https://console.example.com). 리포트 메일 · Slack 알림의 화면 링크에 쓴다. 비우면 링크 없이 */
	public record Console(@DefaultValue("") String baseUrl) {
	}

	/**
	 * 승인 전 검사(3-13). maxConcurrentEvents = Redis 노드 최대 수(인프라 10-17 결정, dev 3).
	 * lockTimeoutSeconds = 동시 승인 전역 잠금(GET_LOCK) 대기 시간
	 */
	public record Approve(@Min(1) @DefaultValue("3") int maxConcurrentEvents,
			@Min(1) @DefaultValue("5") int lockTimeoutSeconds) {
	}

	/**
	 * 홈 모니터링 카드 · OPS-05 트래픽 모니터링(Prometheus HTTP API, 인증 없음). prometheusUrl이 비어 있어도 기동한다 —
	 * 모니터링만 unavailable(stale). refreshTimeout = 갱신 1회(쿼리 전부) 전체 상한. 넘으면 실패로 보고 마지막 값을 stale로 준다.
	 * cacheTtl은 Prometheus · DB 공통. AWS는 aws.cacheTtl.
	 * grafanaUrl = OPS-05 'Grafana에서 자세히' 링크의 Grafana 주소(화면이 /d/<uid>를 붙인다). 기본은 application.yml
	 * (pf-grafana 포트 포워딩 주소), 비우면 화면이 링크를 숨긴다.
	 */
	public record Monitoring(@DefaultValue("") String prometheusUrl,
			@NotNull @DefaultValue("PT2S") Duration connectTimeout,
			@NotNull @DefaultValue("PT3S") Duration readTimeout,
			@NotNull @DefaultValue("PT15S") Duration cacheTtl,
			@NotNull @DefaultValue("PT8S") Duration refreshTimeout,
			@Valid @NotNull @DefaultValue MonitoringAws aws,
			@DefaultValue("") String grafanaUrl) {

		public boolean configured() {
			return prometheusUrl != null && !prometheusUrl.isBlank();
		}

	}

	/**
	 * OPS-05 AWS 지표(CloudWatch GetMetricData · ELB · RDS · ECS, 읽기 전용). 모두 선택 — 비어 있는 값의 항목만 null로 응답한다.
	 * ALB · 대상 그룹 값은 CloudWatch 차원용 arn_suffix(app/… · targetgroup/…), *TgArn은 DescribeTargetHealth용 ARN.
	 * issuanceAlbName은 이름 — 실행 중 DescribeLoadBalancers로 ARN을 얻어 캐시한다(ALB가 이벤트 때 다시 만들어질 수 있어서).
	 * ecsServices · dlqNames는 쉼표 구분.
	 * sfnListTtl: 자동화 실행 상세의 ListExecutions(상태 머신별, 모든 이벤트가 같이 씀) 캐시. 상태 머신은 tetra.sfn 값으로 정해 따로 설정하지 않는다.
	 */
	public record MonitoringAws(@NotNull @DefaultValue("PT60S") Duration cacheTtl,
			@NotNull @DefaultValue("PT10S") Duration refreshTimeout,
			@DefaultValue("") String issuanceAlbName,
			@DefaultValue("") String consoleAlb, @DefaultValue("") String portalAlb,
			@DefaultValue("") String consoleTg, @DefaultValue("") String portalTg,
			@DefaultValue("") String consoleTgArn, @DefaultValue("") String portalTgArn,
			@DefaultValue("") String rdsId,
			@DefaultValue("") String ecsCluster, @DefaultValue("") String ecsServices,
			@DefaultValue("") String dlqNames,
			@NotNull @DefaultValue("PT30S") Duration sfnListTtl) {

		/** 하나라도 값이 있으면 AWS를 조회한다(전부 비면 aws.stale = true, 수치 null) */
		public boolean configured() {
			return Stream
				.of(issuanceAlbName, consoleAlb, portalAlb, consoleTg, portalTg, consoleTgArn, portalTgArn, rdsId,
						ecsCluster, dlqNames)
				.anyMatch(v -> v != null && !v.isBlank());
		}

		public List<String> ecsServiceNames() {
			return split(ecsServices);
		}

		public List<String> dlqNameList() {
			return split(dlqNames);
		}

		private static List<String> split(String value) {
			if (value == null || value.isBlank()) {
				return List.of();
			}
			return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
		}

	}

	/**
	 * 1분 주기 자동화 확인(DLQ 메시지 · 단계 지연 운영자 알림, AutomationAlertJob). 여러 태스크면 GET_LOCK을 잡은 하나만 돈다.
	 * enabled = 스케줄 켜기(테스트 프로필은 끔 — 작업은 run()을 직접 부른다). interval = 회차 간격(앞 회차가 끝난 뒤부터).
	 * delayWindow = 단계 지연을 보는 기간(예정 시각부터). 첫 배포 때 옛 이벤트 알림이 몰리지 않게 하고, OPS-05 자동화 칸도 같은 값을 쓴다.
	 * DLQ 이름은 monitoring.aws.dlqNames(MONITORING_DLQ_NAMES)를 같이 쓴다.
	 */
	public record Alerts(@DefaultValue("true") boolean enabled, @NotNull @DefaultValue("PT1M") Duration interval,
			@NotNull @DefaultValue("PT24H") Duration delayWindow) {
	}

	/**
	 * 운영자 알림 Slack 발송(SlackNotifyJob, chat.postMessage). 기본 꺼짐 — 꺼져 있으면 작업 빈이 없어 DB · HTTP를 건드리지 않고
	 * 토큰 · 채널이 비어 있어도 기동한다. 켜져 있으면 봇 토큰과 우선순위별 채널 3개가 모두 있어야 기동한다(설정 오류를 바로 드러냄).
	 * 비밀(botToken)에 기본값("")을 두는 것은 꺼져 있을 때 기동하려고 — 켜져 있으면 비어 있을 수 없다.
	 * interval = 회차 간격(앞 회차가 끝난 뒤부터). maxAge = 켠 뒤 한 번도 시도하지 않은 알림을 건너뛸(SKIPPED · STALE) 나이 —
	 * 꺼 둔 동안 · 멈춘 동안 쌓인 알림이 한꺼번에 가지 않게. apiUrl은 테스트(FakeSlack)만 바꾼다.
	 */
	public record Slack(@DefaultValue("false") boolean enabled, @DefaultValue("") String botToken,
			@Valid @NotNull @DefaultValue SlackChannels channels,
			@NotNull @DefaultValue("PT10S") Duration interval,
			@NotNull @DefaultValue("PT10M") Duration maxAge,
			@NotNull @DefaultValue("PT2S") Duration connectTimeout,
			@NotNull @DefaultValue("PT5S") Duration readTimeout,
			@NotBlank @DefaultValue("https://slack.com/api") String apiUrl) {

		/** 켜져 있으면 토큰 · 채널 3개가 모두 있어야 한다. 메시지에 값은 넣지 않는다 */
		@AssertTrue(message = "SLACK_NOTIFY_ENABLED=true면 SLACK_BOT_TOKEN · SLACK_CHANNEL_URGENT · SLACK_CHANNEL_WARNING · "
				+ "SLACK_CHANNEL_INFO가 모두 있어야 합니다.")
		public boolean isCompleteWhenEnabled() {
			return !enabled || (hasText(botToken) && channels != null && hasText(channels.urgent())
					&& hasText(channels.warning()) && hasText(channels.info()));
		}

		private static boolean hasText(String value) {
			return value != null && !value.isBlank();
		}

		@Override
		public String toString() {
			return "Slack[enabled=" + enabled + ", botToken=****, channels=" + channels + ", interval=" + interval
					+ ", maxAge=" + maxAge + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
					+ ", apiUrl=" + apiUrl + "]";
		}

	}

	/**
	 * 우선순위별 Slack 채널 ID(채널 이름 아님 — 보낸 채널을 operator_notification.slack_channel 20자에 남긴다).
	 * 운영자 알림 priority(URGENT · WARNING · INFO) 그대로 고른다 — Portal 신청 접수(APPLICATION/WARNING)는 warning 채널로 간다.
	 */
	public record SlackChannels(
			@Pattern(regexp = SLACK_CHANNEL_ID, message = SLACK_CHANNEL_ID_MESSAGE) @DefaultValue("") String urgent,
			@Pattern(regexp = SLACK_CHANNEL_ID, message = SLACK_CHANNEL_ID_MESSAGE) @DefaultValue("") String warning,
			@Pattern(regexp = SLACK_CHANNEL_ID, message = SLACK_CHANNEL_ID_MESSAGE) @DefaultValue("") String info) {

		/** 모르는 우선순위면 null */
		public String forPriority(String priority) {
			if (priority == null) {
				return null;
			}
			return switch (priority) {
				case "URGENT" -> urgent;
				case "WARNING" -> warning;
				case "INFO" -> info;
				default -> null;
			};
		}

	}

	/**
	 * 리포트. operatorBucket = 운영자 리포트 버킷(OPS-09 다운로드 · 운영자 감사 리포트 저장, ReportService · ReportStore).
	 * 기본값은 application.yml(이름 규칙 tetra-operator-logs-{env}) — 실제 버킷 이름이 규칙과 다르면 REPORT_OPERATOR_BUCKET으로 넣는다.
	 * metaCleanup = 2년(731일) 지난 리포트 메타 행 지우기(ReportMetaCleanupJob, 하루 1번 새벽). S3 객체는 버킷 수명 주기가 지운다.
	 */
	public record Report(@NotBlank String operatorBucket, @Valid @NotNull @DefaultValue MetaCleanup metaCleanup) {
	}

	/** enabled = 스케줄 켜기(테스트 프로필은 끔 — 작업은 run()을 직접 부른다) */
	public record MetaCleanup(@DefaultValue("true") boolean enabled) {
	}

	// TODO(미정): CSV presigned URL 만료. 임시 5분
	public record Csv(@NotNull @DefaultValue("PT5M") Duration presignTtl) {
	}

	public record Auth(@NotBlank @Size(min = 32, message = "32바이트 이상이어야 합니다.") String jwtSecret) {
		@Override
		public String toString() {
			return "Auth[jwtSecret=****]";
		}
	}

	public record PasswordLink(@NotBlank String hmacSecret) {
		@Override
		public String toString() {
			return "PasswordLink[hmacSecret=****]";
		}
	}

	/**
	 * 테넌트 스키마 생성(Flyway)용 DDL 계정 tetra_migrator. 앱 런타임 계정(DB_USERNAME)은 DML만 있다.
	 * URL은 spring.datasource.url을 같이 쓴다. locations는 테스트에서만 바꾼다.
	 */
	public record DbMigration(@NotBlank String username, @NotBlank String password,
			@NotBlank @DefaultValue("classpath:db/migration/tenant") String locations) {
		@Override
		public String toString() {
			return "DbMigration[username=" + username + ", password=****, locations=" + locations + "]";
		}
	}

}

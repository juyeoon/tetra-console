package com.tetra.ops.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 모든 값은 환경 변수(ConfigMap·Secret)로 들어온다 (CLAUDE.md §10).
 * 비밀 값(auth.jwtSecret, passwordLink.hmacSecret, dbMigration.password)은 기본값이 없고, 비어 있으면 기동이 실패한다.
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
		@Valid @NotNull @DefaultValue Csv csv,
		@Valid @NotNull Auth auth,
		@Valid @NotNull PasswordLink passwordLink,
		@Valid @NotNull DbMigration dbMigration,
		/** 이벤트 도메인 루트. 이벤트 주소는 {slug}.{tenant_id}.{platformDomain} (ops-workflows 10-17 결정). 기본값은 application.yml */
		@NotBlank String platformDomain) {

	public record Aws(@NotBlank String region) {
	}

	public record Sfn(@NotBlank String approveStateMachineArn, @NotBlank String tenantStateMachineArn) {
	}

	public record Sqs(@NotBlank String approveResultQueue, @NotBlank String tenantResultQueue) {
	}

	public record Mail(@NotBlank String from) {
	}

	public record Portal(@NotBlank String baseUrl) {
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

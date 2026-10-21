package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 스키마 사본이 db-schema 원본(README에 적은 커밋 · SHA-256)과 같은지 확인한다. 여기서 고치면 실패한다.
 * Windows 체크아웃은 CRLF라 CR을 빼고 계산한다(Flyway 체크섬도 줄바꿈과 무관).
 */
class TenantMigrationScriptTest {

	private static final Path README = Path.of("src", "main", "resources", "db", "README.md");

	@ParameterizedTest
	@CsvSource({
			"src/main/resources/db/migration/tenant/V1__init_tenant_schema.sql, 86435fdd49d1adb919c3d8e2eb465e0c52ddff48c48075f038a7b7cd529e67d8",
			"src/test/resources/db/migration/platform/V1__init_platform_schema.sql, 7c28cd3e2d8a8057e81989a143f6fc4a558dedf80e1ba4f918868da4464c8ba4" })
	void copyMatchesDbSchemaHash(String file, String sha256) throws Exception {
		String content = Files.readString(Path.of(file), StandardCharsets.UTF_8).replace("\r", "");
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));

		assertThat(HexFormat.of().formatHex(digest)).as(file).isEqualTo(sha256);
		// README 표와 이 테스트의 해시가 따로 놀지 않게
		assertThat(Files.readString(README, StandardCharsets.UTF_8)).contains("`" + file + "`").contains(sha256);
	}

}

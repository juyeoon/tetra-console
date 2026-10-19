package com.tetra.ops.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** CSV 링크는 파일로 저장되게 Content-Disposition: attachment를 서명에 넣는다. 서명만 하므로 네트워크 호출 없음. */
class S3DownloadUrlSignerTest {

	@Test
	void presignedUrlForcesDownloadWithFileName() {
		try (S3Presigner presigner = S3Presigner.builder()
			.region(Region.AP_NORTHEAST_2)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build()) {
			var signed = new S3DownloadUrlSigner(presigner).presignGet("tetra-audit-logs-dev-k7f2m9qx4b1t",
					"history/1043.csv", Duration.ofMinutes(5), "issuance-history-1043.csv");

			String url = URLDecoder.decode(signed.url(), StandardCharsets.UTF_8);
			assertThat(url).contains("tetra-audit-logs-dev-k7f2m9qx4b1t", "history/1043.csv",
					"response-content-disposition=attachment; filename=\"issuance-history-1043.csv\"");
			assertThat(signed.expiresAt()).isNotNull();
		}
	}

}

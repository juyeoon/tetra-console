package com.tetra.ops.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.tetra.ops.config.TetraProperties;
import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.external.MailGateway.Mail;

/** 개인키 안내 메일 제목 · 본문 (OPS-03-M1). 승인 흐름 전체는 ApproveApiTest. */
class PrivateKeyMailComposerTest {

	private static final String PEM = "-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY-----\n";

	private final PrivateKeyMailComposer composer = new PrivateKeyMailComposer(new EventDomains(properties()));

	private static TetraProperties properties() {
		TetraProperties properties = mock(TetraProperties.class);
		when(properties.platformDomain()).thenReturn("tetraops.cloud");
		return properties;
	}

	private static Event event(String name) {
		Event event = mock(Event.class);
		when(event.getEventId()).thenReturn(3L);
		when(event.getName()).thenReturn(name);
		when(event.getSlug()).thenReturn("abcd1234");
		when(event.getTenantId()).thenReturn("wf5test00001");
		return event;
	}

	private Mail compose(String name) {
		return composer.compose("t@e-corp.com", event(name), PEM, 1L, LocalDateTime.of(2026, 11, 1, 3, 5, 59));
	}

	@Test
	void subjectHasEventNameWithoutParenthesizedId() {
		Mail mail = compose("워크플로우 확인 이벤트");

		assertThat(mail.subject()).isEqualTo("[Tetra] 이벤트 발급 API 인증용 개인키 안내 — 워크플로우 확인 이벤트")
			.doesNotContainPattern("\\(\\d+\\)");
	}

	@Test
	void bodyHasFiveLabelsAndPem() {
		Mail mail = compose("워크플로우 확인 이벤트");

		assertThat(mail.textBody()).contains("- 이벤트명: 워크플로우 확인 이벤트", "- 이벤트 ID: 3",
				"- 이벤트 주소: https://abcd1234.wf5test00001.tetraops.cloud", "- 키 버전(kid): 1",
				"- 만료 시각: 2026.11.01 03:05 (KST, 호스팅 종료 시각)", PEM,
				"개인키는 Tetra에 저장하지 않아 다시 보내 드릴 수 없습니다.")
			.doesNotContain("워크플로우 확인 이벤트 (3)");
		assertThat(mail.toString()).doesNotContain("PRIVATE KEY");
	}

	@Test
	void lineBreaksAndControlCharsAreRemovedFromSubject() {
		Mail mail = compose("  가을\r\n특가\t쿠폰  \n");

		assertThat(mail.subject()).isEqualTo("[Tetra] 이벤트 발급 API 인증용 개인키 안내 — 가을 특가 쿠폰")
			.doesNotContain("\r")
			.doesNotContain("\n")
			.doesNotContain("\t");
		// 본문의 이벤트명도 한 줄로(라벨 줄이 깨지지 않게)
		assertThat(mail.textBody()).contains("- 이벤트명: 가을 특가 쿠폰\n");
	}

	@Test
	void longNameWithSpecialCharactersIsKeptAsIs() {
		String name = "🎉 [단독] 50% 할인 & \"한정\" 쿠폰 <빠른 선착순> — ".repeat(3).strip();

		Mail mail = compose(name);

		assertThat(mail.subject()).isEqualTo(PrivateKeyMailComposer.SUBJECT_PREFIX + name);
	}

}

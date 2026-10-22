package com.tetra.ops.event;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.external.MailGateway.Mail;

/**
 * 승인 시 테넌트에게 보내는 개인키 메일 (OPS-03-M1, §7.3).
 * 제목에는 이벤트명만 둔다(ID를 괄호로 붙이면 스레드 순번처럼 보인다). 본문 정보는 라벨로 나눈다.
 * 개인키(PKCS#8 PEM)는 본문에만 넣고 로그 · 예외 메시지에 남기지 않는다.
 * TODO(미정): 메일 문구 확정(화면설계서에 없음).
 */
@Component
public class PrivateKeyMailComposer {

	static final String SUBJECT_PREFIX = "[Tetra] 이벤트 발급 API 인증용 개인키 안내 — ";

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

	private final EventDomains domains;

	public PrivateKeyMailComposer(EventDomains domains) {
		this.domains = domains;
	}

	/** @param expiresAt 키 만료 시각(호스팅 종료 시각, KST 벽시계) */
	public Mail compose(String to, Event event, String privateKeyPem, Long keyVersion, LocalDateTime expiresAt) {
		String name = singleLine(event.getName());
		String subject = SUBJECT_PREFIX + name;
		String text = """
				안녕하세요.

				Tetra 이벤트 신청이 승인되었습니다.
				발급 API 요청 JWT(RS256) 서명에 쓰는 개인키를 보내 드립니다.

				- 이벤트명: %s
				- 이벤트 ID: %d
				- 이벤트 주소: https://%s
				- 키 버전(kid): %d
				- 만료 시각: %s (KST, 호스팅 종료 시각)

				개인키는 Tetra에 저장하지 않아 다시 보내 드릴 수 없습니다. 안전한 곳에 보관해 주세요.

				%s
				본 메일은 발신 전용입니다.
				""".formatted(name, event.getEventId(), domains.fullDomain(event.getSlug(), event.getTenantId()),
				keyVersion, DATE_TIME.format(expiresAt), privateKeyPem);
		return new Mail(to, subject, text, null);
	}

	/** 줄바꿈 · 탭 등 제어 문자를 공백으로 바꾸고 연속 공백을 하나로 — 제목 헤더가 깨지지 않게(인코딩은 SES가 UTF-8로) */
	static String singleLine(String value) {
		return value == null ? "" : value.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").strip();
	}

}

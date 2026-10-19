package com.tetra.ops.event;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.tetra.ops.domain.tenant.Event;
import com.tetra.ops.external.MailGateway.Mail;

/**
 * 승인 시 테넌트에게 보내는 개인키 메일.
 * TODO(미정): 메일 제목·본문. 화면설계서에 없어 임시 문구. 개인키(PKCS#8 PEM), 키 버전(kid), 만료 시각을 적는다.
 */
@Component
public class PrivateKeyMailComposer {

	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

	public Mail compose(String to, Event event, String privateKeyPem, Long keyVersion, LocalDateTime expiresAt) {
		String subject = "[Tetra] 이벤트 발급 API 인증용 개인키 안내 (" + event.getEventId() + ")";
		String text = """
				안녕하세요.

				Tetra 이벤트 신청이 승인되었습니다.
				발급 API 요청 JWT(RS256) 서명에 쓰는 개인키를 보내 드립니다.

				- 이벤트: %s (%d)
				- 키 버전(kid): %d
				- 만료 시각: %s (KST)

				개인키는 Tetra에 저장하지 않아 다시 보내 드릴 수 없습니다. 안전한 곳에 보관해 주세요.

				%s
				본 메일은 발신 전용입니다.
				""".formatted(event.getName(), event.getEventId(), keyVersion, DATE_TIME.format(expiresAt),
				privateKeyPem);
		return new Mail(to, subject, text, null);
	}

}

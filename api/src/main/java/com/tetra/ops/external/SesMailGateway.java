package com.tetra.ops.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Message;

/** 실제 SES 발송. local 프로필에서는 LocalMailGateway가 대신한다. */
@Component
@Profile("!local")
class SesMailGateway implements MailGateway {

	private static final Logger log = LoggerFactory.getLogger(SesMailGateway.class);

	private final SesV2Client ses;

	private final String from;

	SesMailGateway(SesV2Client ses, TetraProperties properties) {
		this.ses = ses;
		this.from = properties.mail().from();
	}

	@Override
	public boolean send(Mail mail) {
		Body.Builder body = Body.builder().text(utf8(mail.textBody()));
		if (mail.htmlBody() != null) {
			body.html(utf8(mail.htmlBody()));
		}
		Message message = Message.builder().subject(utf8(mail.subject())).body(body.build()).build();
		try {
			ses.sendEmail(b -> b.fromEmailAddress(from)
				.destination(d -> d.toAddresses(mail.to()))
				.content(c -> c.simple(message)));
			return true;
		}
		catch (SdkException ex) {
			// 예외 메시지에 본문이 들어가지 않는다. 그래도 스택은 남기지 않고 종류만 적는다.
			log.error("메일 발송 실패: subject={}, error={}", mail.subject(), ex.getClass().getSimpleName());
			return false;
		}
	}

	private static Content utf8(String data) {
		return Content.builder().data(data).charset("UTF-8").build();
	}

}

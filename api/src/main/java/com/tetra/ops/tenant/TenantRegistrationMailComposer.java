package com.tetra.ops.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import com.tetra.ops.external.MailGateway.Mail;

/** OPS-07-1 테넌트 등록 안내 이메일 (화면설계서 v0.5 문구). */
@Component
public class TenantRegistrationMailComposer {

	static final String SUBJECT = "[Tetra] 테넌트 계정이 등록되었습니다";

	public Mail compose(String to, String contactName, String tenantName, String link) {
		String text = """
				안녕하세요, %s님.
				Tetra에 %s 테넌트 계정이 등록되었습니다.
				아래 링크를 눌러 비밀번호를 설정해 주세요.

				비밀번호 설정하기: %s

				이 링크는 발송 후 24시간 동안 유효하며, 비밀번호를 설정하면 다시 사용할 수 없습니다.

				링크가 만료되었다면 Tetra 운영자에게 재발송을 요청해 주세요.
				본 메일은 발신 전용입니다.
				""".formatted(contactName, tenantName, link);
		String html = """
				<!doctype html>
				<html lang="ko"><body style="font-family:sans-serif;color:#222;">
				<p>안녕하세요, %s님.</p>
				<p>Tetra에 %s 테넌트 계정이 등록되었습니다.<br>아래 버튼을 눌러 비밀번호를 설정해 주세요.</p>
				<p><a href="%s" style="display:inline-block;padding:12px 24px;background:#2563eb;color:#fff;text-decoration:none;border-radius:6px;">비밀번호 설정하기</a></p>
				<p style="color:#555;font-size:13px;">이 링크는 발송 후 24시간 동안 유효하며, 비밀번호를 설정하면 다시 사용할 수 없습니다.</p>
				<hr>
				<p style="color:#777;font-size:12px;">링크가 만료되었다면 Tetra 운영자에게 재발송을 요청해 주세요.<br>본 메일은 발신 전용입니다.</p>
				</body></html>
				""".formatted(HtmlUtils.htmlEscape(contactName), HtmlUtils.htmlEscape(tenantName),
				HtmlUtils.htmlEscape(link));
		return new Mail(to, SUBJECT, text, html);
	}

}

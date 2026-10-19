package com.tetra.ops.external;

/** SES SendEmail. 트랜잭션 밖(커밋 후)에서만 부른다. 본문(개인키·링크)은 로그에 남기지 않는다. */
public interface MailGateway {

	/** @return SES가 발송 요청을 접수했으면 true */
	boolean send(Mail mail);

	record Mail(String to, String subject, String textBody, String htmlBody) {

		/** 본문에 개인키·비밀번호 설정 링크가 들어가므로 toString에 내보내지 않는다. */
		@Override
		public String toString() {
			return "Mail[to=" + to + ", subject=" + subject + ", body=****]";
		}

	}

}

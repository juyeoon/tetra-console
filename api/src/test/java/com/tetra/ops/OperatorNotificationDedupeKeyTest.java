package com.tetra.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.tetra.ops.common.DbErrors;
import com.tetra.ops.domain.platform.OperatorNotification;
import com.tetra.ops.domain.platform.OperatorNotificationRepository;
import com.tetra.ops.support.IntegrationTest;
import com.tetra.ops.support.TestData;

/**
 * operator_notification.dedupe_key(공용 V4, uk_operator_notif_dedupe). 같은 키는 1행만 — 두 번째는 1062.
 * NULL(상태 전이 알림)은 제한 없음. 1분 주기 작업은 이 1062만 잡아 건너뛴다(D4).
 */
class OperatorNotificationDedupeKeyTest extends IntegrationTest {

	static final String KEY = "DLQ:tetra-test-approve-result-dlq:2026-10-30";

	@Autowired
	OperatorNotificationRepository notifications;

	private static OperatorNotification system(String content, String dedupeKey) {
		return OperatorNotification.unread(null, null, "SYSTEM", "URGENT", TestData.BASE, content, dedupeKey);
	}

	private long rows() {
		return data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification");
	}

	@Test
	void sameKeyTwiceIsDuplicateKey() {
		notifications.save(system("첫 알림", KEY));

		assertThatThrownBy(() -> notifications.save(system("같은 키 다시", KEY)))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(DbErrors.isDuplicateKey(ex, "uk_operator_notif_dedupe")).isTrue());
		assertThat(rows()).isEqualTo(1);
		assertThat(data.jdbc().queryForObject("SELECT dedupe_key FROM tetra_platform.operator_notification", String.class))
			.isEqualTo(KEY);

		// 다른 키(다음 날)는 새 행
		notifications.save(system("다음 날", "DLQ:tetra-test-approve-result-dlq:2026-10-31"));
		assertThat(rows()).isEqualTo(2);
	}

	@Test
	void nullKeysAreNotLimited() {
		notifications.save(OperatorNotification.unread(null, null, "SYSTEM", "INFO", TestData.BASE, "첫 알림"));
		notifications.save(OperatorNotification.unread(null, null, "SYSTEM", "INFO", TestData.BASE, "두 번째 알림"));

		assertThat(data.count("SELECT COUNT(*) FROM tetra_platform.operator_notification WHERE dedupe_key IS NULL"))
			.isEqualTo(2);
	}

}

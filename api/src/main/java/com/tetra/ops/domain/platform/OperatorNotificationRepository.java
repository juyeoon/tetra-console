package com.tetra.ops.domain.platform;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OperatorNotificationRepository extends JpaRepository<OperatorNotification, Long> {

	/** OPS-02 최근 알림 5건 (idx_operator_notif_occurred). */
	List<OperatorNotification> findTop5ByOrderByOccurredAtDescNotificationIdDesc();

}

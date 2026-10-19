package com.tetra.ops.domain.tenant;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tetra.ops.domain.StatusType;

public interface EventStatusLogRepository extends JpaRepository<EventStatusLog, Long> {

	/** OPS-03-1 신청 상태 로그. changed_at 오름차순, 같은 시각이면 기록 순서. */
	List<EventStatusLog> findByEventIdAndStatusTypeOrderByChangedAtAscLogIdAsc(Long eventId, StatusType statusType);

	/** OPS-04-3 종료 처리 실패의 원인·발생 시각. */
	Optional<EventStatusLog> findFirstByEventIdAndStatusTypeAndNewStatusOrderByChangedAtDescLogIdDesc(Long eventId,
			StatusType statusType, String newStatus);

	List<EventStatusLog> findByEventIdOrderByLogIdAsc(Long eventId);

}

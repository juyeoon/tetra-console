package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tetra.ops.domain.StatusType;

public interface EventStatusLogRepository extends JpaRepository<EventStatusLog, Long> {

	/** OPS-03-1 · 04-1 상태 처리 기록(신청 · 호스팅 · 이벤트 전체). changed_at 오름차순, 같은 시각이면 기록 순서. */
	List<EventStatusLog> findByEventIdOrderByChangedAtAscLogIdAsc(Long eventId);

	/** OPS-04-3 종료 처리 실패의 원인·발생 시각. */
	Optional<EventStatusLog> findFirstByEventIdAndStatusTypeAndNewStatusOrderByChangedAtDescLogIdDesc(Long eventId,
			StatusType statusType, String newStatus);

	List<EventStatusLog> findByEventIdOrderByLogIdAsc(Long eventId);

	/** OPS-05 최근 자동화 실패: 이벤트들의 *_FAILED 로그 (idx_status_log_event). */
	List<EventStatusLog> findByEventIdInAndNewStatusInAndChangedAtGreaterThanEqual(Collection<Long> eventIds,
			Collection<String> newStatuses, LocalDateTime since);

}

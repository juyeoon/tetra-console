package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventResultSummaryRepository extends JpaRepository<EventResultSummary, Long> {

	/** 이벤트 결과 리포트 보충 대상 후보: 발급 이력 저장 시각이 since 이후(같으면 포함)인 행, event_id 순. 보조 인덱스 없음(테넌트별 몇 행) */
	List<EventResultSummary> findByHistoryGeneratedAtGreaterThanEqualOrderByEventIdAsc(LocalDateTime since);

}

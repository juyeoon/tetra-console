package com.tetra.ops.domain.platform;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantStatusLogRepository extends JpaRepository<TenantStatusLog, Long> {

	/** OPS-06-1 계정 상태 로그. 최신순, 같은 시각이면 나중 기록이 먼저. */
	List<TenantStatusLog> findByTenantIdOrderByChangedAtDescLogIdDesc(String tenantId);

}

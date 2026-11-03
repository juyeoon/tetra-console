package com.tetra.ops.domain.tenant;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 테넌트 스키마 tenant_report_meta. 테넌트 스키마 라우팅(TenantRouter.inTenant) 안에서만 부른다. */
public interface TenantReportMetaRepository extends JpaRepository<TenantReportMeta, Long> {

	/** 같은 리포트(같은 키)가 이미 있는지 — 다시 만들 때 메타 · 알림을 한 번만 남기려고. file_path 인덱스는 없다(테넌트별 몇 행) */
	boolean existsByTenantIdAndFilePath(String tenantId, String filePath);

	/** 보관 2년: created_at이 cutoff보다 이른 행을 지운다(idx_tenant_report_created). 라우팅한 tenant_id 행만(§4-6). 지운 행 수 */
	@Transactional
	@Modifying
	@Query("delete from TenantReportMeta r where r.tenantId = :tenantId and r.createdAt < :cutoff")
	int deleteCreatedBefore(@Param("tenantId") String tenantId, @Param("cutoff") LocalDateTime cutoff);

}

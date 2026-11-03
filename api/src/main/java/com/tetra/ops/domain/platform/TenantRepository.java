package com.tetra.ops.domain.platform;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.tetra.ops.domain.AccountStatus;
import com.tetra.ops.domain.ProvisioningStatus;

import jakarta.persistence.LockModeType;

public interface TenantRepository extends JpaRepository<Tenant, String> {

	boolean existsByEmail(String email);

	boolean existsByEmailAndTenantIdNot(String email, String tenantId);

	List<Tenant> findAllByOrderByCreatedAtDescTenantIdAsc();

	long countByAccountStatus(AccountStatus accountStatus);

	long countByCreatedAtGreaterThanEqual(LocalDateTime from);

	/** 테넌트 스키마를 도는 작업(리포트 메타 정리 등)의 대상 — READY만 라우팅할 수 있다 */
	List<Tenant> findAllByProvisioningStatusOrderByTenantId(ProvisioningStatus provisioningStatus);

	/** 테넌트 자동 비활성 판정용 잠금 (SELECT … FOR UPDATE). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Tenant t where t.tenantId = :tenantId")
	Optional<Tenant> findByIdForUpdate(@Param("tenantId") String tenantId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Tenant t set t.accountStatus = :to
			 where t.tenantId = :tenantId and t.accountStatus = :from""")
	int updateAccountStatus(@Param("tenantId") String tenantId, @Param("from") AccountStatus from,
			@Param("to") AccountStatus to);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Tenant t set t.provisioningStatus = :to
			 where t.tenantId = :tenantId and t.provisioningStatus = :from""")
	int updateProvisioningStatus(@Param("tenantId") String tenantId, @Param("from") ProvisioningStatus from,
			@Param("to") ProvisioningStatus to);

}

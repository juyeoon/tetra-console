package com.tetra.ops.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantNotificationRepository extends JpaRepository<TenantNotification, Long> {

}

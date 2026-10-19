package com.tetra.ops.domain.tenant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tetra.ops.domain.AuthKeyStatus;

public interface EventAuthKeyRepository extends JpaRepository<EventAuthKey, Long> {

	/** ACTIVE 중 최신 버전. */
	Optional<EventAuthKey> findFirstByEventIdAndStatusOrderByKeyVersionDesc(Long eventId, AuthKeyStatus status);

	List<EventAuthKey> findByEventIdInAndStatus(Collection<Long> eventIds, AuthKeyStatus status);

}

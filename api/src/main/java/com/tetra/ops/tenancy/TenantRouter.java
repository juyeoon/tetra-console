package com.tetra.ops.tenancy;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.tetra.ops.common.error.ApiException;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;
import com.tetra.ops.domain.platform.Tenant;
import com.tetra.ops.domain.platform.TenantRepository;

/**
 * 요청에서 테넌트를 정하는 출처를 모은 곳 (§4-2, §4-3).
 * - eventId → tetra_platform.event_registry의 tenant_id (없으면 404)
 * - tenantId → 그대로 (형식이 틀리거나 없으면 404)
 * - provisioning_status가 READY가 아니면 테넌트 스키마로 라우팅하지 않는다 (409)
 * 스키마 이름 자체는 SchemaTenantIdentifierResolver가 정한다. 이 클래스는 트랜잭션 밖에서 불러야 한다.
 */
@Component
public class TenantRouter {

	private final EventRegistryRepository registries;

	private final TenantRepository tenants;

	public TenantRouter(EventRegistryRepository registries, TenantRepository tenants) {
		this.registries = registries;
		this.tenants = tenants;
	}

	public record RoutedEvent(EventRegistry registry, Tenant tenant) {

		public Long eventId() {
			return registry.getEventId();
		}

		public String tenantId() {
			return tenant.getTenantId();
		}

	}

	/** eventId로 registry와 테넌트를 찾는다. 테넌트 스키마로 들어가지는 않는다. */
	public RoutedEvent findEvent(Long eventId) {
		EventRegistry registry = registries.findById(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.EVENT_NOT_FOUND));
		if (!TenantIds.isValid(registry.getTenantId())) {
			throw new IllegalStateException("event_registry.tenant_id 형식 오류: eventId=" + eventId);
		}
		Tenant tenant = tenants.findById(registry.getTenantId())
			.orElseThrow(() -> new IllegalStateException("registry가 가리키는 테넌트가 없습니다: eventId=" + eventId));
		return new RoutedEvent(registry, tenant);
	}

	/** tenantId로 테넌트를 찾는다. 형식이 틀리면 없는 것으로 본다. */
	public Tenant findTenant(String tenantId) {
		if (!TenantIds.isValid(tenantId)) {
			throw new ApiException(ErrorCode.TENANT_NOT_FOUND);
		}
		return tenants.findById(tenantId).orElseThrow(() -> new ApiException(ErrorCode.TENANT_NOT_FOUND));
	}

	/** 이벤트의 테넌트 스키마에서 work를 실행한다. */
	public <T> T inTenant(RoutedEvent event, Supplier<T> work) {
		return inTenant(event.tenant(), work);
	}

	/** 테넌트 스키마에서 work를 실행한다. READY가 아니면 409. */
	public <T> T inTenant(Tenant tenant, Supplier<T> work) {
		if (!tenant.isReady()) {
			// TODO(미정): READY가 아닌 테넌트의 이벤트를 요청한 경우의 응답 코드. 임시로 409 TENANT_NOT_READY
			throw new ApiException(ErrorCode.TENANT_NOT_READY);
		}
		return TenantContext.callAs(tenant.getTenantId(), work);
	}

	public void inTenant(RoutedEvent event, Runnable work) {
		inTenant(event, () -> {
			work.run();
			return null;
		});
	}

}

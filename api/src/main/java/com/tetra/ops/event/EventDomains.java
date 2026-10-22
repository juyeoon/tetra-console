package com.tetra.ops.event;

import org.springframework.stereotype.Component;

import com.tetra.ops.config.TetraProperties;

/**
 * 이벤트 주소 규칙 (ops-workflows 10-17 결정).
 * domain = {slug}.{tenant_id}, fullDomain = {slug}.{tenant_id}.{tetra.platform-domain}.
 * 이벤트 상세(resources)와 개인키 안내 메일이 같은 규칙을 쓴다.
 */
@Component
public class EventDomains {

	private final String platformDomain;

	public EventDomains(TetraProperties properties) {
		this.platformDomain = properties.platformDomain();
	}

	/** 할당 서브 도메인 {slug}.{tenant_id} */
	public String domain(String slug, String tenantId) {
		return slug + "." + tenantId;
	}

	/** 이벤트 주소의 호스트 {slug}.{tenant_id}.{플랫폼 도메인} */
	public String fullDomain(String slug, String tenantId) {
		return domain(slug, tenantId) + "." + platformDomain;
	}

}

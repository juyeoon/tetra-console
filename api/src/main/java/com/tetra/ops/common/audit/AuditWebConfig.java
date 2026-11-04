package com.tetra.ops.common.audit;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.tetra.ops.domain.platform.EventRegistry;
import com.tetra.ops.domain.platform.EventRegistryRepository;

/** 감사 로그 등록 — 바깥 필터(보안 체인 -100보다 앞) + sub 복사 인터셉터(Portal 복사본). */
@Configuration(proxyBeanMethods = false)
public class AuditWebConfig implements WebMvcConfigurer {

	/** 문자 인코딩 필터(HIGHEST_PRECEDENCE) 바로 뒤, 보안 체인(-100) · RequestContextFilter(-105)보다 앞. */
	static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

	/**
	 * 리포지토리는 처음 쓸 때 꺼낸다(ObjectProvider) — 필터 등록은 웹 서버 시작 때라 JPA를 앞당겨 만들지 않게.
	 * 조회는 요청이 끝난 뒤 트랜잭션 밖에서 PK 한 번(event_registry는 공용 스키마 catalog 지정).
	 */
	@Bean
	FilterRegistrationBean<AuditFilter> auditFilter(Clock clock, ObjectProvider<EventRegistryRepository> registries) {
		FilterRegistrationBean<AuditFilter> registration = new FilterRegistrationBean<>(new AuditFilter(clock,
				eventId -> registries.getObject().findById(eventId).map(EventRegistry::getTenantId).orElse(null)));
		registration.setOrder(ORDER);
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AuditPrincipalInterceptor()).addPathPatterns("/api/ops/**");
	}

}

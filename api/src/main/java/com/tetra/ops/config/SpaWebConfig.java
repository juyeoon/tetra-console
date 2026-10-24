package com.tetra.ops.config;

import java.io.IOException;
import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 화면(web/)과 API를 한 이미지로 서빙한다. 화면 빌드 결과(web/dist)는 api 빌드가 classpath:static/에 넣는다.
 * - 있는 파일(index.html, assets/*.js 등)은 그대로 돌려준다.
 * - /api/** 와 확장자가 있는 경로는 넘기지 않는다(없으면 404).
 * - 그 밖의 경로(/events/1043 같은 React 라우터 주소)는 index.html을 돌려줘 새로고침·직접 진입을 처리한다.
 * index.html이 없으면(API만 띄운 로컬 개발) 아무것도 넘기지 않는다.
 * <p>
 * 브라우저 캐시(3-23): 배포 뒤 강력 새로고침 없이 새 화면이 보이게
 * - assets/ (Vite가 이름에 내용 해시를 붙인 JS · CSS · 이미지): 1년 + immutable — 내용이 바뀌면 이름이 바뀐다
 * - 그 밖(index.html, SPA 라우트로 내려가는 index.html, favicon 등 해시 없는 파일): no-cache — 매번 Last-Modified로 재검증(304)
 */
@Configuration(proxyBeanMethods = false)
public class SpaWebConfig implements WebMvcConfigurer {

	static final String STATIC_LOCATION = "classpath:/static/";

	static final CacheControl HASHED_ASSETS = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

	static final CacheControl REVALIDATE = CacheControl.noCache();

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// 더 구체적인 패턴이 먼저 맞는다. 없는 assets 파일은 index로 넘기지 않고 404
		registry.addResourceHandler("/assets/**")
			.addResourceLocations(STATIC_LOCATION + "assets/")
			.setCacheControl(HASHED_ASSETS)
			.resourceChain(true)
			.addResolver(new PathResourceResolver());
		registry.addResourceHandler("/**")
			.addResourceLocations(STATIC_LOCATION)
			.setCacheControl(REVALIDATE)
			.resourceChain(true)
			.addResolver(new SpaFallbackResolver());
	}

	static class SpaFallbackResolver extends PathResourceResolver {

		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			Resource requested = location.createRelative(resourcePath);
			if (requested.exists() && requested.isReadable()) {
				return requested;
			}
			if (!isSpaRoute(resourcePath)) {
				return null;
			}
			Resource index = location.createRelative("index.html");
			return index.exists() && index.isReadable() ? index : null;
		}

		/** API 경로가 아니고, 마지막 경로 조각에 확장자(.)가 없으면 화면 라우터 주소로 본다. */
		static boolean isSpaRoute(String resourcePath) {
			if (resourcePath.startsWith("api/") || resourcePath.equals("api") || resourcePath.startsWith("actuator")) {
				return false;
			}
			String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
			return !lastSegment.contains(".");
		}

	}

}

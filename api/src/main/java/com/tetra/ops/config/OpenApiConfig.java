package com.tetra.ops.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.tetra.ops.auth.JwtTokenService;
import com.tetra.ops.common.error.ApiErrorCodes;
import com.tetra.ops.common.error.ErrorCode;
import com.tetra.ops.common.error.ErrorResponse;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * 프런트용 API 문서(springdoc). /v3/api-docs(JSON), /swagger-ui.html(화면).
 * API_DOCS_ENABLED=true일 때만 열린다(기본 꺼짐, application.yml). 문서화 대상은 /api/**.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	static final String COOKIE_AUTH = "cookieAuth";

	static final String ERROR_SCHEMA = "ErrorResponse";

	private static final String DESCRIPTION = """
			Tetra Operations Console(운영자용, 내부망) API.

			**인증**: `POST /api/ops/auth/login`에 성공하면 HttpOnly 쿠키 `%s`(8시간)가 내려옵니다.
			이후 같은 도메인의 `/api/ops/**` 호출에는 브라우저가 쿠키를 자동으로 붙입니다. 화면은 상대 경로 `/api`로 호출합니다.
			로그인·헬스 체크를 뺀 모든 API는 쿠키가 없거나 만료되면 401 `UNAUTHENTICATED`입니다.

			**공통 형식**
			- 일시: `+09:00`이 붙은 ISO-8601 문자열 (예: `2026-10-14T14:02:00+09:00`)
			- 상태 값: DB 값(영문 UPPER_SNAKE_CASE) 그대로. 한글 표기는 화면설계서 v0.5대로 화면에서 바꿉니다
			- 값이 없으면 `null`로 내려갑니다(필드는 빠지지 않음)
			- 목록은 아직 페이지네이션 없이 전체를 돌려줍니다

			**오류 응답**: 모든 오류는 `ErrorResponse` 형식입니다. `code`로 분기하고 `message`는 그대로 보여 줘도 됩니다.
			400이면 `fields`에 항목별 사유가 들어갑니다.
			""".formatted(JwtTokenService.COOKIE_NAME);

	@Bean
	OpenAPI tetraOpenApi() {
		return new OpenAPI()
			.info(new Info().title("Tetra Operations Console API").version("v1").description(DESCRIPTION))
			// 상대 주소 — forward-headers-strategy: none이라 요청으로 만들면 ALB 뒤에서 http://…가 되어 https 화면의 호출이 막힌다
			.addServersItem(new Server().url("/"))
			.components(new Components().addSecuritySchemes(COOKIE_AUTH,
					new SecurityScheme().type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.COOKIE)
						.name(JwtTokenService.COOKIE_NAME)
						.description("로그인 API가 내려주는 HttpOnly 쿠키")))
			.addSecurityItem(new SecurityRequirement().addList(COOKIE_AUTH));
	}

	/** ErrorResponse 스키마를 components에 등록한다. */
	@Bean
	OpenApiCustomizer errorSchemaCustomizer() {
		return openApi -> {
			@SuppressWarnings("rawtypes")
			Map<String, Schema> schemas = ModelConverters.getInstance().read(ErrorResponse.class);
			schemas.forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
		};
	}

	/**
	 * 오류 응답을 상태 코드별로 붙인다. @ApiErrorCodes에 적은 code + 인증이 필요한 API는 401 + 모든 API에 500.
	 * 설명에 code와 기본 message를 나열해 프런트가 분기할 값을 바로 볼 수 있게 한다.
	 */
	@Bean
	OperationCustomizer errorCodeCustomizer() {
		return (operation, handlerMethod) -> {
			List<ErrorCode> codes = new ArrayList<>();
			ApiErrorCodes declared = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
			if (declared != null) {
				codes.addAll(List.of(declared.value()));
			}
			boolean publicApi = operation.getSecurity() != null && operation.getSecurity().isEmpty();
			if (!publicApi && !codes.contains(ErrorCode.UNAUTHENTICATED)) {
				codes.add(ErrorCode.UNAUTHENTICATED);
			}
			codes.add(ErrorCode.INTERNAL_ERROR);

			Map<Integer, List<ErrorCode>> byStatus = codes.stream()
				.distinct()
				.collect(Collectors.groupingBy(c -> c.status().value(), TreeMap::new, Collectors.toList()));
			Map<String, ApiResponse> added = new LinkedHashMap<>();
			byStatus.forEach((status, list) -> {
				String description = list.stream()
					.map(c -> "`" + c.name() + "` — " + c.message())
					.collect(Collectors.joining("<br>"));
				added.put(String.valueOf(status), new ApiResponse().description(description)
					.content(new Content().addMediaType("application/json",
							new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA)))));
			});
			added.forEach(operation.getResponses()::addApiResponse);
			return operation;
		};
	}

}

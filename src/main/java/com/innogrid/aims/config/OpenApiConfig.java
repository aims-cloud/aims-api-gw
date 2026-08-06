package com.innogrid.aims.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aimsOpenAPI() {
        String schemeName = "Bearer Token";

        return new OpenAPI()
                .info(new Info()
                        .title("AIMS Cloud Backend API")
                        .description("마이크로 데이터센터(MDC) 및 퍼블릭 클라우드 통합 관리 포탈 API\n\n"
                                + "인증: 로그인 후 발급받은 accessToken을 Authorize 버튼에 입력하세요.")
                        .version("v0.1.0")
                        .contact(new Contact()
                                .name("이노그리드")
                                .email("ksh1006@innogrid.com")))
                .addSecurityItem(new SecurityRequirement().addList(schemeName))
                .components(new Components()
                        .addSecuritySchemes(schemeName, new SecurityScheme()
                                .name(schemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("POST /api/v1/auth/login으로 발급받은 accessToken을 입력")));
    }
}

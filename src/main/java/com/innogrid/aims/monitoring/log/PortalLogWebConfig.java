package com.innogrid.aims.monitoring.log;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 포털 로그 보조 인터셉터를 MVC 에 등록한다 */
@Configuration
public class PortalLogWebConfig implements WebMvcConfigurer {

    private final PortalLogFallbackInterceptor fallbackInterceptor;

    public PortalLogWebConfig(PortalLogFallbackInterceptor fallbackInterceptor) {
        this.fallbackInterceptor = fallbackInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(fallbackInterceptor).addPathPatterns("/api/**");
    }
}

package com.sdp.ci.p02ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig implements WebMvcConfigurer {
    private final RateLimitInterceptor interceptor;

    public RateLimitConfig(RateLimitInterceptor interceptor) { this.interceptor = interceptor; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/quotes/**");   // only the protected API
    }
}

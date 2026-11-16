package com.salian.shortener.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public FilterRegistrationBean<CreateRateLimitFilter> createRateLimitFilter(RateLimitProperties properties) {
        FilterRegistrationBean<CreateRateLimitFilter> registration =
                new FilterRegistrationBean<>(new CreateRateLimitFilter(properties));
        registration.addUrlPatterns("/api/v1/links");
        return registration;
    }
}

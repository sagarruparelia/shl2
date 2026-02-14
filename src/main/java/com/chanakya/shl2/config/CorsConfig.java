package com.chanakya.shl2.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${shl.cors.allowed-origins:*}")
    private List<String> allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        // Public protocol endpoints — wildcard per SHL spec
        CorsConfiguration publicConfig = new CorsConfiguration();
        publicConfig.setAllowedOrigins(List.of("*"));
        publicConfig.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        publicConfig.setAllowedHeaders(List.of("*"));
        source.registerCorsConfiguration("/api/shl/manifest/**", publicConfig);
        source.registerCorsConfiguration("/api/shl/direct/**", publicConfig);
        source.registerCorsConfiguration("/.well-known/**", publicConfig);

        // Management and member endpoints — configurable origins
        CorsConfiguration restrictedConfig = new CorsConfiguration();
        restrictedConfig.setAllowedOrigins(allowedOrigins);
        restrictedConfig.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        restrictedConfig.setAllowedHeaders(List.of("*"));
        restrictedConfig.setAllowCredentials(!allowedOrigins.contains("*"));
        source.registerCorsConfiguration("/api/shl/**", restrictedConfig);
        source.registerCorsConfiguration("/api/member/**", restrictedConfig);
        source.registerCorsConfiguration("/api/healthlake/**", restrictedConfig);

        return new CorsWebFilter(source);
    }
}

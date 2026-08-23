package com.example.skladdo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * Origins allowed to call the API. Defaults to the local Vite dev servers; override in
     * deployed environments via the {@code APP_CORS_ALLOWED_ORIGINS} env var (comma-separated).
     */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
    private List<String> allowedOrigins;

    /**
     * Shared CORS configuration. Spring Security's filter chain picks this bean up
     * via {@code http.cors(withDefaults())}, so the rules apply to authenticated
     * (JWT) requests as well as preflight requests.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}

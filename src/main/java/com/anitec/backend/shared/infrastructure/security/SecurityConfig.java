package com.anitec.backend.shared.infrastructure.security;

import com.anitec.backend.shared.interfaceapi.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 6 configuration (spec section 10.1): stateless JWT, RBAC
 * route rules, CORS and JSON error responses using the standard envelope.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/actuator/health", "/actuator/info",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // GANADERO write operations
                        .requestMatchers(HttpMethod.POST, "/api/v1/farms/**").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.POST, "/api/v1/animals").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/animals/**").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/animals/**").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/animals/**").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.POST, "/api/v1/animals/*/observations").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.GET, "/api/v1/animals/capacity").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.POST, "/api/v1/invitations").hasRole("GANADERO")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/vet-links/**").hasRole("GANADERO")
                        // VETERINARIO write operations
                        .requestMatchers(HttpMethod.POST, "/api/v1/visits/**").hasRole("VETERINARIO")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/visits/**").hasRole("VETERINARIO")
                        .requestMatchers(HttpMethod.POST, "/api/v1/attentions/**").hasRole("VETERINARIO")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/attentions/**").hasRole("VETERINARIO")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, 401, "No autenticado o sesión expirada", "INVALID_CREDENTIALS"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, 403, "No tiene permisos para realizar esta operación", "FORBIDDEN")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String message, String code)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message, code, null));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins:*}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

package com.anitec.backend.identity.application;

/**
 * Small abstraction over BCrypt so the application services do not depend on
 * Spring Security types directly (implemented in infrastructure by
 * SecurityConfig's PasswordEncoder bean).
 */
public interface PasswordEncoderPort {

    String encode(String rawPassword);

    boolean matches(String rawPassword, String encodedPassword);
}

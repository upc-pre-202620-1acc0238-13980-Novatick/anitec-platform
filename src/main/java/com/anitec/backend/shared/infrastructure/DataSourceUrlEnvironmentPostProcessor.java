package com.anitec.backend.shared.infrastructure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Render exposes the managed PostgreSQL connection as {@code DATABASE_URL}
 * ({@code postgres://user:pass@host:5432/dbname?sslmode=require}). Spring
 * Boot does not understand that scheme, so this processor converts it to a
 * JDBC URL (and derives username/password from the userinfo) before the
 * DataSource is created (spec section 13.2).
 *
 * <p>{@code DATABASE_URL} is only a fallback: an explicitly configured JDBC
 * URL ({@code SPRING_DATASOURCE_URL} or {@code --spring.datasource.url=...})
 * always wins. The {@code spring.datasource.url} default coming from
 * {@code application.yml} must NOT count as "explicitly configured",
 * otherwise {@code DATABASE_URL} would never be applied.</p>
 *
 * <p>The {@code user:pass@} userinfo is stripped from the resulting JDBC URL
 * (the JDBC driver cannot resolve a host like {@code user:pass@host}); the
 * credentials are passed separately via username/password properties.</p>
 */
public class DataSourceUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (hasExplicitJdbcUrl(environment)) {
            return;
        }
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }

        Map<String, Object> properties = new HashMap<>();
        String userInfo = null;

        try {
            URI uri = URI.create(databaseUrl.replaceFirst("^(postgres|postgresql)://", "postgresql://"));
            StringBuilder jdbc = new StringBuilder("jdbc:postgresql://");
            if (uri.getHost() != null) {
                jdbc.append(uri.getHost());
            }
            if (uri.getPort() >= 0) {
                jdbc.append(':').append(uri.getPort());
            }
            if (uri.getPath() != null && !uri.getPath().isEmpty()) {
                jdbc.append(uri.getPath());
            }
            if (uri.getRawQuery() != null) {
                jdbc.append('?').append(uri.getRawQuery());
            }
            properties.put("spring.datasource.url", jdbc.toString());
            userInfo = uri.getUserInfo();
        } catch (IllegalArgumentException ex) {
            // Not parseable as a URI: fall back to a plain scheme swap.
            properties.put("spring.datasource.url",
                    databaseUrl.replaceFirst("^(postgres|postgresql)://", "jdbc:postgresql://"));
        }

        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            if (!hasExplicit(environment, "SPRING_DATASOURCE_USERNAME", "spring.datasource.username")) {
                properties.put("spring.datasource.username", decode(parts[0]));
            }
            if (parts.length > 1 && !hasExplicit(environment, "SPRING_DATASOURCE_PASSWORD", "spring.datasource.password")) {
                properties.put("spring.datasource.password", decode(parts[1]));
            }
        }

        environment.getPropertySources().addFirst(new MapPropertySource("anitecDatabaseUrl", properties));
    }

    /**
     * True when the caller configured a JDBC URL on purpose (env var, JVM
     * system property or command line). The {@code spring.datasource.url}
     * default from application.yml is not treated as explicit: only these
     * dedicated sources are, so {@code DATABASE_URL} is still applied.
     */
    private static boolean hasExplicitJdbcUrl(ConfigurableEnvironment environment) {
        if (!isBlank(environment.getProperty("SPRING_DATASOURCE_URL"))) {
            return true;
        }
        return containsProperty(environment, "systemProperties", "spring.datasource.url")
                || containsProperty(environment, "commandLineArgs", "spring.datasource.url");
    }

    private static boolean containsProperty(ConfigurableEnvironment environment, String source, String name) {
        PropertySource<?> propertySource = environment.getPropertySources().get(source);
        return propertySource != null && propertySource.containsProperty(name);
    }

    /** True when a credential was provided directly (env var, system property or CLI). */
    private static boolean hasExplicit(ConfigurableEnvironment environment, String envName, String propName) {
        if (!isBlank(environment.getProperty(envName))) {
            return true;
        }
        return containsProperty(environment, "systemProperties", propName)
                || containsProperty(environment, "commandLineArgs", propName);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}

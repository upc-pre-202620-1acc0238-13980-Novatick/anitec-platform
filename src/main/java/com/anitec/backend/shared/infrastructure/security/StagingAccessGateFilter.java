package com.anitec.backend.shared.infrastructure.security;

import com.anitec.backend.shared.interfaceapi.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Enumeration;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Created only inside SecurityFilterChain: no servlet auto-registration or second login. */
public final class StagingAccessGateFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-ANITEC-Staging-Key";
    private final byte[] key;
    private final ObjectMapper mapper;

    public StagingAccessGateFilter(Environment environment, ObjectMapper mapper) {
        this.key = StagingAccessKey.active(environment) ? StagingAccessKey.validated(environment) : null;
        this.mapper = mapper;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (key == null || ("GET".equals(request.getMethod())
                && "/actuator/health".equals(request.getRequestURI()))) {
            chain.doFilter(request, response);
            return;
        }
        Enumeration<String> headers = request.getHeaders(HEADER);
        String supplied = headers != null && headers.hasMoreElements() ? headers.nextElement() : null;
        boolean duplicate = headers != null && headers.hasMoreElements();
        byte[] candidate = StagingAccessKey.decode(supplied);
        if (!duplicate && candidate != null && MessageDigest.isEqual(key, candidate)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(),
                ApiResponse.error("Acceso no permitido", "STAGING_ACCESS_DENIED", null));
    }
}

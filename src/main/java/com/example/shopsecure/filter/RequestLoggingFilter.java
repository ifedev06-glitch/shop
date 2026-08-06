package com.example.shopsecure.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String method = request.getMethod();
            String path = sanitizePath(request.getRequestURI());
            int status = response.getStatus();
            String userId = (String) request.getAttribute("authenticatedUserId");

            if (status >= 500) {
                if (userId != null) {
                    log.error("Request failed: method={}, path={}, status={}, duration={}ms, userId={}",
                            method, path, status, duration, userId);
                } else {
                    log.error("Request failed: method={}, path={}, status={}, duration={}ms",
                            method, path, status, duration);
                }
            } else if (status >= 400) {
                if (userId != null) {
                    log.warn("Request completed with client error: method={}, path={}, status={}, duration={}ms, userId={}",
                            method, path, status, duration, userId);
                } else {
                    log.warn("Request completed with client error: method={}, path={}, status={}, duration={}ms",
                            method, path, status, duration);
                }
            } else {
                if (userId != null) {
                    log.info("Request completed: method={}, path={}, status={}, duration={}ms, userId={}",
                            method, path, status, duration, userId);
                } else {
                    log.info("Request completed: method={}, path={}, status={}, duration={}ms",
                            method, path, status, duration);
                }
            }
        }
    }

    private String sanitizePath(String path) {
        return path.replaceAll("(/orders/token/)[^/]+", "$1[redacted]");
    }
}

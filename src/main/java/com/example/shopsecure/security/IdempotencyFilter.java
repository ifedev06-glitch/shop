package com.example.shopsecure.security;

import com.example.shopsecure.entity.IdempotencyRecord;
import com.example.shopsecure.repository.IdempotencyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyRepository idempotencyRepository;

    private static final List<String> IDEMPOTENT_PATHS = List.of(
            "/api/payments/initialize",
            "/api/checkout",
            "/api/vendor/withdrawals"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isIdempotentPath = IDEMPOTENT_PATHS.stream()
                .anyMatch(path::contains);

        // only apply to POST requests on payment paths
        if (!isIdempotentPath || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");

        // reject if no key provided
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\": \"Idempotency-Key header is required\"}"
            );
            return;
        }

        // check if we've seen this key before
        Optional<IdempotencyRecord> existing = idempotencyRepository
                .findByIdempotencyKeyAndRequestPath(idempotencyKey, path);

        if (existing.isPresent()) {
            // replay the stored response
            IdempotencyRecord record = existing.get();
            response.setStatus(record.getStatusCode());
            response.setContentType("application/json");
            response.getWriter().write(record.getResponseBody());
            return;
        }

        // capture the response
        ContentCachingResponseWrapper responseWrapper =
                new ContentCachingResponseWrapper(response);

        chain.doFilter(request, responseWrapper);

        String responseBody = new String(
                responseWrapper.getContentAsByteArray()
        );

        // save idempotency record
        try {
            IdempotencyRecord record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .requestPath(path)
                    .responseBody(responseBody)
                    .statusCode(responseWrapper.getStatus())
                    .createdAt(LocalDateTime.now())
                    .build();

            idempotencyRepository.save(record);

        } catch (DataIntegrityViolationException e) {
            // concurrent request with same key already saved
            // just replay whatever was saved
            idempotencyRepository
                    .findByIdempotencyKeyAndRequestPath(idempotencyKey, path)
                    .ifPresent(record -> {
                        try {
                            response.setStatus(record.getStatusCode());
                        } catch (Exception ignored) {}
                    });
        }

        responseWrapper.copyBodyToResponse();
    }
}
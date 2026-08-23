package com.example.shopsecure.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicWaybillResponseHeadersFilter extends OncePerRequestFilter {

    private static final String PUBLIC_INVITATION_PREFIX = "/waybills/invitations/";
    private static final String PUBLIC_ORDER_PREFIX = "/orders/token/";
    private static final String PUBLIC_TRACKING_PREFIX = "/orders/tracking/";
    private static final String CREATOR_ROOT = "/waybills";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(PUBLIC_INVITATION_PREFIX)
                || path.startsWith(PUBLIC_ORDER_PREFIX)
                || path.startsWith(PUBLIC_TRACKING_PREFIX)) {
            return false;
        }
        if ("POST".equals(request.getMethod()) && path.matches("/orders/[1-9][0-9]*/disputes")) {
            return false;
        }
        return !("POST".equals(request.getMethod()) && path.equals(CREATOR_ROOT))
                && !("POST".equals(request.getMethod())
                && path.matches("/waybills/[^/]+/(invitations/reissue|payments/checkout)"))
                && !("PUT".equals(request.getMethod())
                && path.matches("/(waybills/[^/]+|orders/[^/]+)/shipping/selection"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(PUBLIC_TRACKING_PREFIX)) {
            response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
        }
        filterChain.doFilter(request, response);
    }
}

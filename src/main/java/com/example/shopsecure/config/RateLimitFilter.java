//package com.example.shopsecure.config;
//
//import io.github.bucket4j.Bandwidth;
//import io.github.bucket4j.Bucket;
//import io.github.bucket4j.Refill;
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import org.springframework.stereotype.Component;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//import java.time.Duration;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//@Component
//public class RateLimitFilter extends OncePerRequestFilter {
//
//    // one bucket per IP address
//    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
//
//    private Bucket createBucket() {
//        return Bucket.builder()
//                .addLimit(Bandwidth.classic(
//                        20,                                    // 20 requests
//                        Refill.intervally(20, Duration.ofMinutes(1)) // per minute
//                ))
//                .build();
//    }
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request,
//                                    HttpServletResponse response,
//                                    FilterChain chain)
//            throws ServletException, IOException {
//
//        String ip = request.getRemoteAddr();
//
//        Bucket bucket = buckets.computeIfAbsent(ip, k -> createBucket());
//
//        if (bucket.tryConsume(1)) {
//            chain.doFilter(request, response);
//        } else {
//            response.setStatus(429);
//            response.setContentType("application/json");
//            response.getWriter().write(
//                    "{\"error\": \"Too many requests. Please slow down.\"}"
//            );
//        }
//    }
//}
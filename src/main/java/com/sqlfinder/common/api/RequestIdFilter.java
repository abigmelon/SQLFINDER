package com.sqlfinder.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** 请求标识过滤 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "sqlfinder.requestId";
    public static final String HEADER = "X-Request-Id";
    public static UUID identify(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof UUID id) return id;
        UUID id;
        try {
            String supplied = request.getHeader(HEADER);
            id = UUID.fromString(supplied);
            if (!id.toString().equalsIgnoreCase(supplied)) throw new IllegalArgumentException();
        } catch (RuntimeException ex) { id = UUID.randomUUID(); }
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        response.setHeader(HEADER, identify(request).toString());
        chain.doFilter(request, response);
    }
}

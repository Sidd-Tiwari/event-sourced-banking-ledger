package com.example.ledger.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Standard stateless-JWT filter: extract Bearer token, validate it, and if
 * valid populate the SecurityContext for this request only (no session).
 * An invalid/missing/expired token simply results in an unauthenticated
 * request - Spring Security's authorization rules (SecurityConfig) then
 * decide whether that's acceptable for the requested endpoint, producing a
 * 401/403 as appropriate rather than this filter making that call itself.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            jwtService.validate(token).ifPresent(principal -> {
                var authority = new SimpleGrantedAuthority("ROLE_" + principal.role().name());
                var authToken = new UsernamePasswordAuthenticationToken(principal, null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            });
        }
        filterChain.doFilter(request, response);
    }
}

package com.example.ledger.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * URL-level rules here are coarse (role-only). Fine-grained, per-resource
 * checks (e.g. "this CUSTOMER may only see THIS accountId") are enforced at
 * the method level via @PreAuthorize + AccountAccessGuard on the controller,
 * because the URL layer has no way to know an account's owning customerId
 * without a DB lookup - that's exactly what object-level authorization means
 * in section 14/15 of the design brief, and why it can't live here alone.
 *
 * CSRF is disabled because this is a stateless, token-authenticated API with
 * no browser cookie session - CSRF protects session-cookie-based auth flows,
 * which don't apply here. If this API were ever also used from a browser
 * with cookie-based auth, CSRF protection would need to be re-enabled for
 * that path specifically.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.ADMIN.name())
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

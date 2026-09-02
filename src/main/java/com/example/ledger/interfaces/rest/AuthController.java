package com.example.ledger.interfaces.rest;

import com.example.ledger.infrastructure.security.JwtService;
import com.example.ledger.infrastructure.security.Role;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * HONEST SCOPE NOTE (see JwtService javadoc for the fuller version): this
 * endpoint issues a valid, signed JWT for ANY username/role/customerId
 * triple with no password check whatsoever. It exists purely to demonstrate
 * JWT issuance + validation + RBAC + object-level authorization end-to-end
 * without building a full user/credential management system, which is a
 * separate (large) concern from event sourcing/CQRS.
 *
 * In a real system this would be replaced entirely by a proper IdP
 * (Cognito/Auth0/Keycloak) or an internal auth service with actual password
 * hashing, MFA, and account lockout policies - say this explicitly in an
 * interview rather than letting it look like more than it is.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public record DemoLoginRequest(
            @NotBlank String username,
            @NotBlank String role,
            UUID customerId // required only when role == CUSTOMER
    ) {}

    @PostMapping("/demo-login")
    public ResponseEntity<Map<String, String>> demoLogin(@RequestBody DemoLoginRequest req) {
        Role role = Role.valueOf(req.role().toUpperCase());
        if (role == Role.CUSTOMER && req.customerId() == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "customerId is required when role=CUSTOMER, so object-level checks have something to compare against"));
        }
        String token = jwtService.issueToken(req.username(), role, req.customerId());
        return ResponseEntity.ok(Map.of("accessToken", token, "tokenType", "Bearer"));
    }
}

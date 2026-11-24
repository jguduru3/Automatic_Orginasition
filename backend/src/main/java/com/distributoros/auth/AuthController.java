package com.distributoros.auth;

import com.distributoros.common.ApiException;
import com.distributoros.config.AppProperties;
import com.distributoros.security.AuthUser;
import com.distributoros.security.JwtService;
import com.distributoros.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    // Constant hash used when the user does not exist, so response time does not reveal valid emails.
    private static final String DUMMY_HASH = "$2a$10$AvMnoxKfPR2jDjbJI9jDp..Wsy3x6jDRlstmEM0a9ILGvL1zKaiMO";

    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginRateLimiter limiter;
    private final AppProperties props;

    public AuthController(JdbcClient db, PasswordEncoder encoder, JwtService jwt, LoginRateLimiter limiter, AppProperties props) {
        this.db = db; this.encoder = encoder; this.jwt = jwt; this.limiter = limiter; this.props = props;
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 200) String email,
                               @NotBlank @Size(max = 200) String password) {}

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String email = req.email().trim().toLowerCase();
        String key = http.getRemoteAddr() + "|" + email;
        if (!limiter.allow(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many login attempts. Try again in 5 minutes.");
        }
        var row = db.sql("""
                SELECT u.id, u.tenant_id, u.password_hash, u.name, u.role, t.name AS tenant_name
                FROM users u JOIN tenants t ON t.id = u.tenant_id
                WHERE lower(u.email) = :e AND u.active
                """).param("e", email).query().listOfRows();
        String hash = row.isEmpty() ? DUMMY_HASH : (String) row.get(0).get("password_hash");
        boolean ok = encoder.matches(req.password(), hash);
        if (row.isEmpty() || !ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email or password is wrong");
        }
        limiter.reset(key);
        Map<String, Object> r = row.get(0);
        AuthUser u = new AuthUser(((Number) r.get("id")).longValue(), ((Number) r.get("tenant_id")).longValue(),
                (String) r.get("role"), (String) r.get("name"));
        return Map.of("token", jwt.issue(u),
                "user", Map.of("id", u.userId(), "name", u.name(), "role", u.role(),
                        "tenantName", r.get("tenant_name"), "simulateEnabled", props.simulateEnabled()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal AuthUser u) {
        String tenant = db.sql("SELECT name FROM tenants WHERE id = :t").param("t", u.tenantId()).query(String.class).single();
        return Map.of("id", u.userId(), "name", u.name(), "role", u.role(), "tenantName", tenant,
                "simulateEnabled", props.simulateEnabled());
    }
}

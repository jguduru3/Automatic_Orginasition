package com.distributoros.auth;

import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import com.distributoros.security.JwtService;
import com.distributoros.security.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Self-serve signup: creates a business (tenant) with its owner, default price tiers, a main godown and counters. */
@RestController
public class SignupController {
    private final JdbcClient db;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginRateLimiter limiter;
    private final boolean enabled;

    public SignupController(JdbcClient db, PasswordEncoder encoder, JwtService jwt, LoginRateLimiter limiter,
                            @Value("${app.signup-enabled:false}") boolean enabled) {
        this.db = db; this.encoder = encoder; this.jwt = jwt; this.limiter = limiter; this.enabled = enabled;
    }

    @GetMapping("/api/signup")
    public Map<String, Object> status() { return Map.of("enabled", enabled); }

    public record Signup(@NotBlank @Size(max = 200) String businessName,
                         @Size(max = 15) @Pattern(regexp = "^$|\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z\\d]Z[A-Z\\d]") String gstin,
                         @NotBlank @Size(max = 100) String ownerName,
                         @NotBlank @Email @Size(max = 200) String email,
                         @NotBlank @Size(min = 8, max = 100) String password) {}

    @PostMapping("/api/signup")
    @Transactional
    public Map<String, Object> signup(@Valid @RequestBody Signup in, HttpServletRequest http) {
        if (!enabled) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Signup is not open");
        if (!limiter.allow("signup|" + http.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many signups from this network. Try later.");
        }
        String email = in.email().trim().toLowerCase();
        Integer taken = db.sql("SELECT count(*) FROM users WHERE lower(email) = :e").param("e", email).query(Integer.class).single();
        if (taken > 0) throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists. Sign in instead.");
        long tenantId = db.sql("INSERT INTO tenants (name, gstin) VALUES (:n, :g) RETURNING id")
                .param("n", in.businessName().trim()).param("g", in.gstin() == null || in.gstin().isBlank() ? null : in.gstin())
                .query(Long.class).single();
        long userId = db.sql("""
                INSERT INTO users (tenant_id, email, password_hash, name, role) VALUES (:t, :e, :h, :n, 'OWNER') RETURNING id
                """).param("t", tenantId).param("e", email).param("h", encoder.encode(in.password())).param("n", in.ownerName().trim())
                .query(Long.class).single();
        db.sql("""
                INSERT INTO price_tiers (tenant_id, code, name, discount_pct)
                VALUES (:t, 'A', 'Tier A', 5), (:t, 'B', 'Tier B', 3), (:t, 'C', 'Tier C', 0)
                """).param("t", tenantId).update();
        db.sql("INSERT INTO godowns (tenant_id, code, name, is_default) VALUES (:t, 'MAIN', 'Main godown', TRUE)").param("t", tenantId).update();
        db.sql("""
                INSERT INTO doc_counters (tenant_id, kind, next_value)
                VALUES (:t, 'SO', 1), (:t, 'INV', 1), (:t, 'RCPT', 1), (:t, 'VCH', 1), (:t, 'PO', 1), (:t, 'GRN', 1), (:t, 'TRF', 1)
                """).param("t", tenantId).update();
        db.sql("INSERT INTO activity_log (tenant_id, user_id, action, message) VALUES (:t, :u, 'SIGNUP', 'Business created')")
                .param("t", tenantId).param("u", userId).update();
        AuthUser u = new AuthUser(userId, tenantId, "OWNER", in.ownerName().trim());
        return Map.of("token", jwt.issue(u),
                "user", Map.of("id", userId, "name", u.name(), "role", "OWNER", "tenantName", in.businessName().trim()));
    }
}

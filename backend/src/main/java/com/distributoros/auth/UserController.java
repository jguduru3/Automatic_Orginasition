package com.distributoros.auth;

import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('OWNER')")
public class UserController {
    private final JdbcClient db;
    private final PasswordEncoder encoder;

    public UserController(JdbcClient db, PasswordEncoder encoder) { this.db = db; this.encoder = encoder; }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser u) {
        return db.sql("""
                SELECT id, email, name, role, active, monthly_target AS "monthlyTarget", beat
                FROM users WHERE tenant_id = :t ORDER BY role, name
                """).param("t", u.tenantId()).query().listOfRows();
    }

    public record NewUser(@NotBlank @Email @Size(max = 200) String email,
                          @NotBlank @Size(min = 8, max = 100) String password,
                          @NotBlank @Size(max = 100) String name,
                          @NotBlank @Pattern(regexp = "OWNER|ACCOUNTANT|SALES|DELIVERY") String role,
                          @PositiveOrZero BigDecimal monthlyTarget,
                          @Size(max = 200) String beat) {}

    @PostMapping
    @Transactional
    public Map<String, Object> create(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody NewUser n) {
        String email = n.email().trim().toLowerCase();
        Integer exists = db.sql("SELECT count(*) FROM users WHERE lower(email) = :e").param("e", email).query(Integer.class).single();
        if (exists > 0) throw ApiException.conflict("EMAIL_TAKEN", "A user with this email already exists");
        Long id = db.sql("""
                INSERT INTO users (tenant_id, email, password_hash, name, role, monthly_target, beat)
                VALUES (:t, :e, :h, :n, :r, :mt, :b) RETURNING id
                """).param("t", u.tenantId()).param("e", email).param("h", encoder.encode(n.password()))
                .param("n", n.name().trim()).param("r", n.role())
                .param("mt", n.monthlyTarget() == null ? BigDecimal.ZERO : n.monthlyTarget()).param("b", n.beat())
                .query(Long.class).single();
        return Map.of("id", id);
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivate(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        if (id == u.userId()) throw ApiException.badRequest("SELF", "You cannot deactivate yourself");
        int n = db.sql("UPDATE users SET active = false WHERE id = :id AND tenant_id = :t")
                .param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.notFound("User");
        return Map.of("ok", true);
    }
}

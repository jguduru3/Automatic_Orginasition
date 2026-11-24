package com.distributoros.inbox;

import com.distributoros.common.ApiException;
import com.distributoros.config.AppProperties;
import com.distributoros.orders.OrderService;
import com.distributoros.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inbox")
@PreAuthorize("hasAnyRole('OWNER','ACCOUNTANT','SALES')")
public class InboxController {
    private final InboxService inbox;
    private final OrderService orders;
    private final JdbcClient db;
    private final AppProperties props;

    private final com.distributoros.integrations.MediaReader reader;
    private final com.distributoros.storage.BlobStorage blobs;

    public InboxController(InboxService inbox, OrderService orders, JdbcClient db, AppProperties props,
                           com.distributoros.integrations.MediaReader reader, com.distributoros.storage.BlobStorage blobs) {
        this.inbox = inbox; this.orders = orders; this.db = db; this.props = props; this.reader = reader; this.blobs = blobs;
    }

    /** Re-runs transcription / photo reading on a stored voice note or image (e.g. after enabling Azure AI). */
    @PostMapping("/messages/{messageId}/read")
    public Map<String, Object> readMedia(@AuthenticationPrincipal AuthUser u, @PathVariable long messageId) {
        var rows = db.sql("SELECT kind, media_blob FROM messages WHERE id = :m AND tenant_id = :t AND direction = 'IN'")
                .param("m", messageId).param("t", u.tenantId()).query().listOfRows();
        if (rows.isEmpty() || rows.get(0).get("media_blob") == null) throw ApiException.notFound("Voice note or photo");
        String kind = (String) rows.get(0).get("kind");
        if ("VOICE".equals(kind) ? !reader.canTranscribe() : !reader.canRead()) {
            throw ApiException.conflict("AI_OFF", "VOICE".equals(kind)
                    ? "Voice transcription is not set up (AZURE_SPEECH_KEY)" : "Photo reading is not set up (AZURE_VISION_KEY)");
        }
        byte[] data = blobs.get((String) rows.get(0).get("media_blob"));
        if (data == null) throw ApiException.notFound("Stored file");
        String text;
        try {
            text = "VOICE".equals(kind) ? reader.transcribe(data) : reader.readImage(data);
        } catch (Exception e) {
            db.sql("UPDATE messages SET media_status = 'FAILED' WHERE id = :m").param("m", messageId).update();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "The reading service did not respond. Try again.");
        }
        db.sql("UPDATE messages SET body = :b, media_text = :mt, media_status = :s WHERE id = :m")
                .param("b", InboxService.mediaBody(kind, text, null)).param("mt", text).param("s", text == null ? "FAILED" : "DONE")
                .param("m", messageId).update();
        return Map.of("text", text == null ? "" : text, "ok", text != null);
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser u) { return inbox.list(u); }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AuthUser u, @PathVariable long id) { return inbox.get(u, id); }

    @GetMapping("/{id}/draft")
    public Map<String, Object> draft(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                     @RequestParam(required = false) Long godownId) {
        return inbox.draft(u, id, null, godownId);
    }

    public record Line(@NotNull Long productId, @NotNull @Min(1) @Max(100000) Integer qty) {}
    public record Confirm(@NotEmpty @Size(max = 100) List<@Valid Line> lines, boolean overrideCredit, Long godownId) {}

    @PostMapping("/{id}/confirm")
    public OrderService.CreatedOrder confirm(@AuthenticationPrincipal AuthUser u, @PathVariable long id,
                                             @Valid @RequestBody Confirm in) {
        return orders.confirmFromConversation(u, id,
                in.lines().stream().map(l -> new OrderService.LineRequest(l.productId(), l.qty())).toList(),
                in.overrideCredit(), in.godownId());
    }

    @PostMapping("/{id}/ignore")
    public Map<String, Object> ignore(@AuthenticationPrincipal AuthUser u, @PathVariable long id) {
        int n = db.sql("UPDATE conversations SET status = 'IGNORED' WHERE id = :id AND tenant_id = :t AND status = 'NEW'")
                .param("id", id).param("t", u.tenantId()).update();
        if (n == 0) throw ApiException.conflict("ALREADY_HANDLED", "This message was already handled");
        return Map.of("ok", true);
    }

    public record Reply(@NotBlank @Size(max = 4000) String body) {}

    @PostMapping("/{id}/reply")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> reply(@AuthenticationPrincipal AuthUser u, @PathVariable long id, @Valid @RequestBody Reply in) {
        Map<String, Object> c = inbox.get(u, id);
        orders.sendMessage(u.tenantId(), id, (String) c.get("phone"), in.body());
        return Map.of("ok", true);
    }

    public record Simulate(@NotBlank @Pattern(regexp = "\\+?\\d{10,15}") String phone, @NotBlank @Size(max = 4000) String text) {}

    /** Test helper: inject a message as if it arrived on WhatsApp. Disable in production with SIMULATE_ENABLED=false. */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@AuthenticationPrincipal AuthUser u, @Valid @RequestBody Simulate in) {
        if (!props.simulateEnabled()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");
        boolean ok = inbox.receive(u.tenantId(), new InboxService.Inbound(null, in.phone(), in.text(), "TEXT", null));
        return Map.of("ok", ok);
    }
}

package com.distributoros.storage;

import com.distributoros.common.ApiException;
import com.distributoros.security.AuthUser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileController {
    private final BlobStorage blobs;

    public FileController(BlobStorage blobs) { this.blobs = blobs; }

    /** Streams a tenant's own file (proof-of-delivery photos, voice notes). */
    @GetMapping("/api/files")
    public ResponseEntity<byte[]> get(@AuthenticationPrincipal AuthUser u, @RequestParam String path) {
        String prefix = "tenants/" + u.tenantId() + "/";
        if (!path.startsWith(prefix) || path.contains("..")) throw ApiException.notFound("File");
        byte[] data = blobs.get(path);
        if (data == null) throw ApiException.notFound("File");
        MediaType type = path.endsWith(".png") ? MediaType.IMAGE_PNG
                : path.endsWith(".jpg") || path.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : path.endsWith(".ogg") ? MediaType.parseMediaType("audio/ogg")
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(data);
    }
}

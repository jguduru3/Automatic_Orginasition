package com.distributoros.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Filesystem storage for local development and tests. */
public class LocalBlobStorage implements BlobStorage {
    private final Path root;

    public LocalBlobStorage(String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    private Path resolve(String path) {
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Bad path");
        return p;
    }

    @Override
    public void put(String path, byte[] data, String contentType) {
        try {
            Path p = resolve(path);
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String path) {
        try {
            Path p = resolve(path);
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

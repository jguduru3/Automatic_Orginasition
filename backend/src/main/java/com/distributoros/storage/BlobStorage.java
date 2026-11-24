package com.distributoros.storage;

public interface BlobStorage {
    void put(String path, byte[] data, String contentType);
    byte[] get(String path);
}

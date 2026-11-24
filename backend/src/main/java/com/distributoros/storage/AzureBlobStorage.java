package com.distributoros.storage;

import com.azure.core.util.BinaryData;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.distributoros.config.AppProperties;

/**
 * Azure Blob Storage. Uses a connection string when given (Azurite, local), otherwise the account URL with
 * DefaultAzureCredential (managed identity in Container Apps, needs "Storage Blob Data Contributor").
 */
public class AzureBlobStorage implements BlobStorage {
    private final BlobContainerClient container;

    public AzureBlobStorage(AppProperties.Blob cfg) {
        BlobServiceClient svc;
        if (cfg.connectionString() != null && !cfg.connectionString().isBlank()) {
            svc = new BlobServiceClientBuilder().connectionString(cfg.connectionString()).buildClient();
        } else if (cfg.accountUrl() != null && !cfg.accountUrl().isBlank()) {
            svc = new BlobServiceClientBuilder().endpoint(cfg.accountUrl())
                    .credential(new DefaultAzureCredentialBuilder().build()).buildClient();
        } else {
            throw new IllegalStateException("BLOB_MODE=azure needs AZURE_STORAGE_CONNECTION_STRING or AZURE_STORAGE_ACCOUNT_URL");
        }
        this.container = svc.getBlobContainerClient(cfg.container());
        if (!container.exists()) container.create();
    }

    @Override
    public void put(String path, byte[] data, String contentType) {
        BlobClient b = container.getBlobClient(path);
        b.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(data))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType)), null, null);
    }

    @Override
    public byte[] get(String path) {
        BlobClient b = container.getBlobClient(path);
        if (!b.exists()) return null;
        return b.downloadContent().toBytes();
    }
}

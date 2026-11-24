package com.distributoros.storage;

import com.distributoros.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {
    @Bean
    BlobStorage blobStorage(AppProperties props) {
        return props.blob().isAzure() ? new AzureBlobStorage(props.blob()) : new LocalBlobStorage(props.blob().localDir());
    }
}

package com.example.skladdo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * S3 storage configuration ({@code app.storage.s3.*}).
 *
 * In prod, only {@code bucket}/{@code region} are set — credentials come from the AWS SDK's
 * default credential provider chain (an IAM role), so {@code access-key}/{@code secret-key}
 * stay unset. In dev/test, {@code endpoint-override} points at LocalStack, which requires
 * {@code path-style-access=true} and accepts any non-blank static credentials.
 */
@Component
@ConfigurationProperties(prefix = "app.storage.s3")
public class StorageProperties {

    private String bucket;
    private String region = "us-east-1";
    private String endpointOverride;
    private boolean pathStyleAccess = false;
    private String accessKey;
    private String secretKey;

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpointOverride() {
        return endpointOverride;
    }

    public void setEndpointOverride(String endpointOverride) {
        this.endpointOverride = endpointOverride;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}

package com.example.skladdo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public S3Client s3Client(StorageProperties props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.getRegion()))
                .forcePathStyle(props.isPathStyleAccess());
        applyLocalOverrides(builder, props);
        S3Client client = builder.build();

        if (StringUtils.hasText(props.getEndpointOverride())) {
            bootstrapLocalBucket(client, props.getBucket());
        }
        return client;
    }

    @Bean
    public S3Presigner s3Presigner(StorageProperties props) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(props.getRegion()));
        applyLocalOverrides(builder, props);
        return builder.build();
    }

    private void applyLocalOverrides(S3ClientBuilder builder, StorageProperties props) {
        if (StringUtils.hasText(props.getEndpointOverride())) {
            builder.endpointOverride(URI.create(props.getEndpointOverride()));
        }
        if (StringUtils.hasText(props.getAccessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())));
        }
    }

    private void applyLocalOverrides(S3Presigner.Builder builder, StorageProperties props) {
        if (StringUtils.hasText(props.getEndpointOverride())) {
            builder.endpointOverride(URI.create(props.getEndpointOverride()));
        }
        if (StringUtils.hasText(props.getAccessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())));
        }
        // Unlike S3ClientBuilder, S3Presigner.Builder has no forcePathStyle() shortcut - without this,
        // a presigned URL comes out addressed as https://<bucket>.<endpoint>/..., which LocalStack's
        // virtual-hosted-style subdomain doesn't resolve to anything the browser can reach.
        if (props.isPathStyleAccess()) {
            builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
    }

    /**
     * LocalStack starts every run with no buckets, unlike a real AWS account where the bucket is
     * provisioned ahead of time (see the AWS setup steps in the storage migration plan). Only runs
     * when {@code endpoint-override} is set, i.e. never against real AWS.
     */
    private void bootstrapLocalBucket(S3Client client, String bucket) {
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created local S3 bucket '{}' (LocalStack).", bucket);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            // Already provisioned by a previous run.
        }
        client.putBucketCors(PutBucketCorsRequest.builder()
                .bucket(bucket)
                .corsConfiguration(CORSConfiguration.builder()
                        .corsRules(CORSRule.builder()
                                .allowedMethods("GET")
                                .allowedOrigins("*")
                                .allowedHeaders("*")
                                .build())
                        .build())
                .build());
    }
}

package com.example.skladdo.service;

import com.example.skladdo.config.StorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.UUID;

@Service
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public S3StorageService(S3Client s3Client, S3Presigner s3Presigner, StorageProperties props) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = props.getBucket();
    }

    @Override
    public void delete(String key) {
        if (!StringUtils.hasText(key)) return;
        // S3 answers 204 whether or not the key was there, so this needs no existence check.
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public String store(MultipartFile file, String category) {
        String original = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file");
        int dot = original.lastIndexOf('.');
        String ext = (dot >= 0) ? original.substring(dot).toLowerCase() : "";
        String key = category + "/" + UUID.randomUUID() + ext;

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    @Override
    public String store(byte[] bytes, String contentType, String extension, String category) {
        String key = category + "/" + UUID.randomUUID() + (extension == null ? "" : extension);
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
        return key;
    }

    @Override
    public String presign(String key, Duration ttl) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(b -> b.bucket(bucket).key(key))
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    @Override
    public StoredObject open(String key) {
        ResponseInputStream<GetObjectResponse> object =
                s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new StoredObject(object, object.response().contentType());
    }
}

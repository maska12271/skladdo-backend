package com.example.skladdo.controller;

import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.StoredFile;
import com.example.skladdo.repository.StoredFileRepository;
import com.example.skladdo.service.StorageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/upload")
@Tag(name = "Upload")
public class FileUploadController {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final StorageService storageService;
    private final StoredFileRepository storedFiles;

    public FileUploadController(StorageService storageService, StoredFileRepository storedFiles) {
        this.storageService = storageService;
        this.storedFiles = storedFiles;
    }

    /**
     * Records that an object now exists, and whose it is.
     *
     * <p>The bucket cannot answer either question on its own - the keys carry no tenant - so without this
     * row an upload is invisible to both the usage figures and any future cleanup. Written after the
     * store succeeds: a failed upload should leave nothing behind, in the bucket or here.</p>
     */
    private void record(String key, MultipartFile file, String category, String originalName) {
        StoredFile stored = new StoredFile();
        stored.setStorageKey(key);
        stored.setSizeBytes(file.getSize());
        stored.setContentType(file.getContentType());
        stored.setCategory(category);
        stored.setOriginalName(originalName);
        storedFiles.save(stored);
    }

    @PostMapping("/image")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("error.upload.noFile");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BadRequestException("error.upload.imageOnly");
        }

        String key = storageService.store(file, "images");
        record(key, file, "images", StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "image"));
        return ResponseEntity.ok(Map.of("key", key));
    }

    /** Document content types accepted by {@link #uploadDocument} (in addition to any {@code image/*}). */
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/csv"
    );

    /**
     * Stores an arbitrary document (e.g. a supplier invoice PDF or a scan) under a random key and
     * returns it plus the original filename. Accepts PDFs, images, common office documents and
     * plain text; rejects anything else.
     */
    @PostMapping("/document")
    public ResponseEntity<Map<String, String>> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BadRequestException("error.upload.noFile");
        }

        String contentType = file.getContentType();
        boolean allowed = contentType != null
                && (contentType.startsWith("image/") || ALLOWED_DOCUMENT_TYPES.contains(contentType));
        if (!allowed) {
            throw new BadRequestException("error.upload.unsupportedType");
        }

        String original = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "document");
        String key = storageService.store(file, "documents");
        record(key, file, "documents", original);
        return ResponseEntity.ok(Map.of("key", key, "name", original));
    }

    /**
     * Mints a short-lived URL for a previously stored key. Called on demand at render time — the
     * result is never persisted, since it expires (see {@link StorageService}).
     */
    @GetMapping("/presign")
    public ResponseEntity<Map<String, String>> presign(@RequestParam("key") String key) {
        return ResponseEntity.ok(Map.of("url", storageService.presign(key, PRESIGN_TTL)));
    }
}

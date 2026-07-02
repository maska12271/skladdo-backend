package com.example.kladdo.controller;

import com.example.kladdo.exception.BadRequestException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
@Tag(name = "Upload")
public class FileUploadController {

    private final Path uploadDir;

    public FileUploadController(@Value("${app.upload.dir:./uploads}") String uploadDirPath) {
        this.uploadDir = Paths.get(uploadDirPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(uploadDir);
    }

    @PostMapping("/image")
    public ResponseEntity<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("error.upload.noFile");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BadRequestException("error.upload.imageOnly");
        }

        String original = StringUtils.cleanPath(file.getOriginalFilename() != null ? file.getOriginalFilename() : "image");
        int dot = original.lastIndexOf('.');
        String ext = (dot >= 0) ? original.substring(dot).toLowerCase() : "";
        String filename = UUID.randomUUID() + ext;

        Files.copy(file.getInputStream(), uploadDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);

        return ResponseEntity.ok(Map.of("url", "/uploads/" + filename));
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
     * Stores an arbitrary document (e.g. a supplier invoice PDF or a scan) under a random filename and
     * returns its {@code /uploads/...} url plus the original filename. Accepts PDFs, images, common
     * office documents and plain text; rejects anything else.
     */
    @PostMapping("/document")
    public ResponseEntity<Map<String, String>> uploadDocument(@RequestParam("file") MultipartFile file) throws IOException {
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
        int dot = original.lastIndexOf('.');
        String ext = (dot >= 0) ? original.substring(dot).toLowerCase() : "";
        String filename = UUID.randomUUID() + ext;

        Files.copy(file.getInputStream(), uploadDir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);

        return ResponseEntity.ok(Map.of("url", "/uploads/" + filename, "name", original));
    }
}

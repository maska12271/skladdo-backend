package com.example.skladdo.controller;

import com.example.skladdo.service.TrackingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * Public (unauthenticated) tracking-pixel endpoint. A recipient's mail client loading the 1x1 image
 * signals that the email was opened. Always returns a valid image regardless of whether the token is
 * known - a broken image would be conspicuous to the recipient.
 */
@RestController
@RequestMapping("/api/public/email-tracking")
@Tag(name = "Email Tracking")
public class EmailTrackingController {

    /** A 1x1 fully transparent PNG, decoded once at class load - no file I/O per request. */
    private static final byte[] PIXEL = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private final TrackingService trackingService;

    public EmailTrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @GetMapping(value = "/{token}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> pixel(@PathVariable String token) {
        try {
            trackingService.recordView(token);
        } catch (Exception ignored) {
            // Never let a tracking hiccup break the returned image.
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(PIXEL);
    }
}

package com.example.skladdo.dto;

import java.util.List;

/** The complete image-key list a product should have after the edit — the gallery sends the whole list, not a delta. */
public record UpdateProductImagesRequest(List<String> imageKeys) {
}

package com.example.kladdo.dto;

/**
 * A lightweight tender entry for the "part of a tender" picker on the order forms - just enough to label
 * and select it, without the rollup/parts computation of {@link TenderResponseDto}.
 */
public record TenderOptionDto(
        Long id,
        String title,
        String tenderNumber
) {
}

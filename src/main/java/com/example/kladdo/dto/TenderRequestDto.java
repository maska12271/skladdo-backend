package com.example.kladdo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TenderRequestDto {

    @NotBlank
    private String title;

    private String tenderNumber;

    @NotNull
    private Long clientId;

    private String status;
    private String publishedAt;
    private String deadline;
    private String description;
    private Double estimatedValue;

    // ISO 4217 currency for the estimated value / bids; null/blank = the company base currency.
    private String currency;

    // Snapshotted rate: 1 base currency = exchangeRate units of `currency`. Null when in base currency.
    private BigDecimal exchangeRate;
}
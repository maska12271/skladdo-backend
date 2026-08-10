package com.example.kladdo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@AllArgsConstructor
public class TenderResponseDto {
    private Long id;
    private String title;
    private String tenderNumber;
    private Long clientId;
    private String clientName;
    private String status;
    private String publishedAt;
    private String deadline;
    private String description;
    private Double estimatedValue;
    private String currency;
    private BigDecimal exchangeRate;

    // Rollup across the tender's parts (computed), for the list/detail summary.
    private Integer partCount;
    private Integer participatingCount;   // parts our company takes part in
    private Integer wonCount;             // parts our company won
    private Integer lostCount;            // parts awarded to someone else (where we took part)
    private Integer pendingCount;         // parts we take part in with no winner decided yet
    private Double participatingValue;    // sum of estimated value over parts we take part in
}
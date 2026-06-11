package com.example.tenderapp.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class TenderResponseDto {
    private Long id;
    private String title;
    private String tenderNumber;
    private String customerName;
    private Long clientId;
    private String clientName;
    private String status;
    private String publishedAt;
    private String deadline;
    private String description;
    private Double estimatedValue;
}
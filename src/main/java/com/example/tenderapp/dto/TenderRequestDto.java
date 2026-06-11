package com.example.tenderapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderRequestDto {

    @NotBlank
    private String title;

    private String tenderNumber;
    private String customerName;

    @NotNull
    private Long clientId;

    private String status;
    private String publishedAt;
    private String deadline;
    private String description;
    private Double estimatedValue;
}
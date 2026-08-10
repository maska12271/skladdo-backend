package com.example.kladdo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderRequirementRequestDto {

    @NotBlank
    private String description;

    private Double quantity;
    private String unit;

    /** Number of physical samples required for this item (only when the part requires samples). */
    private Integer sampleQuantity;
}

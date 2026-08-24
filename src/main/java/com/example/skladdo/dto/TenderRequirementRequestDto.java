package com.example.skladdo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TenderRequirementRequestDto {

    @NotBlank
    private String description;

    /** Optional link to a catalogue service; null leaves the requirement as free text only. */
    private Long serviceId;

    private Double quantity;
    private String unit;

    /** Number of physical samples required for this item (only when the part requires samples). */
    private Integer sampleQuantity;
}

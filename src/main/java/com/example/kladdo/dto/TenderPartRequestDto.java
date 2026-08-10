package com.example.kladdo.dto;

import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class TenderPartRequestDto {
    private String partNumber;
    private String title;
    private String description;
    private Double estimatedValue;

    /** Whether physical samples are required for this part. */
    private Boolean samplesRequired;

    /** The items this part is looking for. Replace-all: the saved part matches this list exactly. */
    @Valid
    private List<TenderRequirementRequestDto> requirements = new ArrayList<>();
}

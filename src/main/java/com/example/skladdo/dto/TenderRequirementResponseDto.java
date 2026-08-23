package com.example.skladdo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class TenderRequirementResponseDto {
    private Long id;
    private String description;
    private Double quantity;
    private String unit;
    private Integer sampleQuantity;
    private Integer sortOrder;
}

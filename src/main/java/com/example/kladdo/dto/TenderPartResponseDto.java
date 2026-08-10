package com.example.kladdo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class TenderPartResponseDto {
    private Long id;
    private String partNumber;
    private String title;
    private String description;
    private Double estimatedValue;
    private Boolean samplesRequired;
    private Integer sortOrder;
    private List<TenderRequirementResponseDto> requirements;
    private List<TenderParticipantResponseDto> participants;
}

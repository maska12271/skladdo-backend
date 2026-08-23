package com.example.skladdo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class TenderParticipantResponseDto {
    private Long id;
    private String manufacturerName;
    private Double offeredPrice;
    private String notes;
    private Boolean winner;
    private Boolean ownCompany;
    private Boolean participating;
}
package com.example.tenderapp.controller;

import com.example.tenderapp.dto.DashboardStatsDto;
import com.example.tenderapp.service.DashboardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    // Any authenticated user may call this; the service gates each block by the caller's per-module
    // view permission, so the response only ever contains data they are allowed to see.
    @GetMapping("/stats")
    public DashboardStatsDto stats(Authentication authentication) {
        return dashboardService.getStats(authentication);
    }
}

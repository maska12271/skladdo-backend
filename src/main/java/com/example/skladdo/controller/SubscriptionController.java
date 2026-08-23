package com.example.skladdo.controller;

import com.example.skladdo.dto.ChangePlanRequest;
import com.example.skladdo.dto.SubscriptionViewDto;
import com.example.skladdo.exception.BadRequestException;
import com.example.skladdo.model.AddonType;
import com.example.skladdo.model.PlanType;
import com.example.skladdo.service.PlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The calling company's subscription: its current plan, usage and billing dates, plus switching plan,
 * cancelling, and toggling add-ons. Restricted to owners and administrators (billing is a management
 * concern); every operation is scoped to the caller's company. Every endpoint returns the full, updated
 * {@link SubscriptionViewDto} so the settings page always renders from a single source of truth.
 */
@RestController
@RequestMapping("/api/subscription")
@Tag(name = "Subscription")
@PreAuthorize("hasAnyRole('OWNER', 'ADMINISTRATOR')")
public class SubscriptionController {

    private final PlanService planService;

    public SubscriptionController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public SubscriptionViewDto get() {
        return planService.view();
    }

    @PutMapping("/plan")
    public SubscriptionViewDto changePlan(@Valid @RequestBody ChangePlanRequest request) {
        planService.changePlan(parsePlan(request.plan()));
        return planService.view();
    }

    @PostMapping("/cancel")
    public SubscriptionViewDto cancel() {
        planService.cancel();
        return planService.view();
    }

    @PostMapping("/resume")
    public SubscriptionViewDto resume() {
        planService.resume();
        return planService.view();
    }

    @PostMapping("/addons/{addon}")
    public SubscriptionViewDto activateAddon(@PathVariable String addon) {
        planService.activateAddon(parseAddon(addon));
        return planService.view();
    }

    @DeleteMapping("/addons/{addon}")
    public SubscriptionViewDto cancelAddon(@PathVariable String addon) {
        planService.cancelAddon(parseAddon(addon));
        return planService.view();
    }

    private static PlanType parsePlan(String value) {
        try {
            return PlanType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.plan.invalidPlan");
        }
    }

    private static AddonType parseAddon(String value) {
        try {
            return AddonType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.plan.invalidAddon");
        }
    }
}

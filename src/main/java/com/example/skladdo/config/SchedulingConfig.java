package com.example.skladdo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler, which drives the background jobs in
 * {@code com.example.skladdo.service.ScheduledMaintenanceService}.
 *
 * <p>Gated on {@code app.scheduling.enabled} (default on) so a test context or a one-off CLI run can start
 * the app without its jobs firing - notably the exchange-rate warm-up, which would otherwise reach out to
 * the ECB feed during a {@code @SpringBootTest}.</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}

package com.isums.contractservice.configurations;

import com.isums.contractservice.services.MaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class MaintenanceBootstrap {

    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final MaintenanceService maintenanceService;

    @EventListener(ApplicationReadyEvent.class)
    public void seedSingleton() {
        try {
            maintenanceService.ensureSingletonRow(SYSTEM_ACTOR);
        } catch (Exception ex) {
            log.warn("[MaintenanceBootstrap] seed failed (will retry next startup): {}", ex.getMessage());
        }
    }
}

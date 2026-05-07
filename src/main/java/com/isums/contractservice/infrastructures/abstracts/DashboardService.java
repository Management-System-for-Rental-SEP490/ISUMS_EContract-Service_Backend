package com.isums.contractservice.infrastructures.abstracts;

import com.isums.contractservice.domains.dtos.DashboardResponse;

import java.util.UUID;

public interface DashboardService {
    DashboardResponse getDashboard(UUID userId, String period, boolean isLandlord);
}

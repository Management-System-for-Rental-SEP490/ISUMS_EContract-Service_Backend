package com.isums.contractservice.domains.dtos;

import com.isums.contractservice.domains.enums.EContractStatus;

import java.util.List;

public record DashboardResponse(
        PropertyStats propertyStats,
        List<TimeSeriesItem> contractTimeSeries,
        List<StatusCount> contractStatusBreakdown
) {
    public record PropertyStats(
            long total,
            long rented,
            long available,
            long expiringSoon
    ) {}

    public record TimeSeriesItem(
            String month,
            long count
    ) {}

    public record StatusCount(
            EContractStatus status,
            long count
    ) {}
}

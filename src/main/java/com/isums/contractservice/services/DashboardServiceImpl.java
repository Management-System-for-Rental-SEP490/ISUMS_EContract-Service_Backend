package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.DashboardResponse;
import com.isums.contractservice.domains.dtos.DashboardResponse.PropertyStats;
import com.isums.contractservice.domains.dtos.DashboardResponse.StatusCount;
import com.isums.contractservice.domains.dtos.DashboardResponse.TimeSeriesItem;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.infrastructures.abstracts.DashboardService;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.houseservice.grpc.HouseStatus;
import com.isums.houseservice.grpc.ListHouseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final List<EContractStatus> TERMINATING_STATUSES = List.of(
            EContractStatus.PENDING_TERMINATION,
            EContractStatus.INSPECTION_DONE,
            EContractStatus.DEPOSIT_REFUND_PENDING
    );

    private final EContractRepository contractRepo;
    private final HouseGrpcClient houseGrpcClient;

    @Override
    public DashboardResponse getDashboard(UUID userId, String period, boolean isLandlord) {
        if (isLandlord) {
            return new DashboardResponse(
                    buildPropertyStatsAll(),
                    buildTimeSeriesAll(period),
                    buildStatusBreakdownAll()
            );
        }

        ListHouseResponse houses = houseGrpcClient.getHousesByManagerRegion(userId);
        List<UUID> houseIds = houses.getHouseList().stream()
                .map(h -> UUID.fromString(h.getId()))
                .toList();

        if (houseIds.isEmpty()) {
            return new DashboardResponse(
                    new PropertyStats(0, 0, 0, 0),
                    List.of(),
                    List.of()
            );
        }

        return new DashboardResponse(
                buildPropertyStatsByRegion(houses, houseIds),
                buildTimeSeriesByRegion(houseIds, period),
                buildStatusBreakdownByRegion(houseIds)
        );
    }

    // ── LANDLORD (admin): all data ──

    private PropertyStats buildPropertyStatsAll() {
        long total = contractRepo.countDistinctHouses();
        long rented = contractRepo.countDistinctHousesByStatus(EContractStatus.COMPLETED);

        Instant now = Instant.now();
        Instant deadline = now.plus(Duration.ofDays(30));
        long expiringSoon = contractRepo.countHousesExpiringSoonAll(
                EContractStatus.COMPLETED, now, deadline, TERMINATING_STATUSES);

        long available = total - rented - expiringSoon;
        if (available < 0) available = 0;

        return new PropertyStats(total, rented, available, expiringSoon);
    }

    private List<TimeSeriesItem> buildTimeSeriesAll(String period) {
        Instant now = Instant.now();
        Instant from = "1Y".equalsIgnoreCase(period)
                ? now.atZone(ZoneOffset.UTC).minusYears(1).toInstant()
                : now.atZone(ZoneOffset.UTC).minusMonths(6).toInstant();

        return contractRepo.countByMonthInRangeAll(from, now).stream()
                .map(row -> new TimeSeriesItem((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    private List<StatusCount> buildStatusBreakdownAll() {
        return contractRepo.countByStatusGroupedAll().stream()
                .map(row -> new StatusCount((EContractStatus) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    // ── MANAGER: region-scoped ──

    private PropertyStats buildPropertyStatsByRegion(ListHouseResponse houses, List<UUID> houseIds) {
        long total = houses.getHouseCount();
        long rented = houses.getHouseList().stream()
                .filter(h -> h.getStatus() == HouseStatus.HOUSE_STATUS_RENTED).count();
        long available = houses.getHouseList().stream()
                .filter(h -> h.getStatus() == HouseStatus.HOUSE_STATUS_AVAILABLE).count();

        Instant now = Instant.now();
        Instant deadline = now.plus(Duration.ofDays(30));
        long expiringSoon = contractRepo.countHousesExpiringSoonByHouseIds(
                houseIds, EContractStatus.COMPLETED, now, deadline, TERMINATING_STATUSES);

        return new PropertyStats(total, rented, available, expiringSoon);
    }

    private List<TimeSeriesItem> buildTimeSeriesByRegion(List<UUID> houseIds, String period) {
        Instant now = Instant.now();
        Instant from = "1Y".equalsIgnoreCase(period)
                ? now.atZone(ZoneOffset.UTC).minusYears(1).toInstant()
                : now.atZone(ZoneOffset.UTC).minusMonths(6).toInstant();

        return contractRepo.countByMonthInRangeByHouseIds(houseIds, from, now).stream()
                .map(row -> new TimeSeriesItem((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }

    private List<StatusCount> buildStatusBreakdownByRegion(List<UUID> houseIds) {
        return contractRepo.countByStatusGroupedByHouseIds(houseIds).stream()
                .map(row -> new StatusCount((EContractStatus) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}

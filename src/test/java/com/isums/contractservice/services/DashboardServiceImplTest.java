package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.DashboardResponse;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.infrastructures.grpcs.HouseGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.houseservice.grpc.ListHouseResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardServiceImpl")
class DashboardServiceImplTest {

    @Mock private EContractRepository contractRepo;
    @Mock private HouseGrpcClient houseGrpcClient;

    @Test
    @DisplayName("backfills missing current-year months with fake Jan/Feb counts for 6M chart")
    void backfillsMissingMonths() {
        DashboardServiceImpl service = spy(new DashboardServiceImpl(contractRepo, houseGrpcClient));
        doReturn(YearMonth.of(2026, 4)).when(service).currentMonthUtc();

        when(contractRepo.countDistinctHouses()).thenReturn(13L);
        when(contractRepo.countDistinctHousesByStatus(EContractStatus.COMPLETED)).thenReturn(10L);
        when(contractRepo.countHousesExpiringSoonAll(eq(EContractStatus.COMPLETED), any(Instant.class), any(Instant.class), any()))
                .thenReturn(9L);
        when(contractRepo.countByMonthInRangeAll(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        new Object[]{"2026-03", 127L},
                        new Object[]{"2026-04", 69L}
                ));
        when(contractRepo.countByStatusGroupedAll()).thenReturn(List.of());

        DashboardResponse response = service.getDashboard(UUID.randomUUID(), "6M", true);

        assertThat(response.contractTimeSeries())
                .extracting(DashboardResponse.TimeSeriesItem::month, DashboardResponse.TimeSeriesItem::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-01", 60L),
                        org.assertj.core.groups.Tuple.tuple("2026-02", 100L),
                        org.assertj.core.groups.Tuple.tuple("2026-03", 127L),
                        org.assertj.core.groups.Tuple.tuple("2026-04", 69L)
                );
    }

    @Test
    @DisplayName("falls back to global dashboard when manager has no house scope")
    void fallbackToGlobalWhenManagerHasNoHouseScope() {
        DashboardServiceImpl service = spy(new DashboardServiceImpl(contractRepo, houseGrpcClient));
        doReturn(YearMonth.of(2026, 4)).when(service).currentMonthUtc();

        when(houseGrpcClient.getHousesByManagerRegion(any(UUID.class)))
                .thenReturn(ListHouseResponse.newBuilder().build());

        when(contractRepo.countDistinctHouses()).thenReturn(13L);
        when(contractRepo.countDistinctHousesByStatus(EContractStatus.COMPLETED)).thenReturn(10L);
        when(contractRepo.countHousesExpiringSoonAll(eq(EContractStatus.COMPLETED), any(Instant.class), any(Instant.class), any()))
                .thenReturn(2L);
        when(contractRepo.countByMonthInRangeAll(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        new Object[]{"2026-03", 127L},
                        new Object[]{"2026-04", 69L}
                ));
        when(contractRepo.countByStatusGroupedAll())
                .thenReturn(List.<Object[]>of(new Object[]{EContractStatus.COMPLETED, 105L}));

        DashboardResponse response = service.getDashboard(UUID.randomUUID(), "6M", false);

        assertThat(response.propertyStats().total()).isEqualTo(13L);
        assertThat(response.propertyStats().rented()).isEqualTo(10L);
        assertThat(response.propertyStats().available()).isEqualTo(1L);
        assertThat(response.propertyStats().expiringSoon()).isEqualTo(2L);
        assertThat(response.contractTimeSeries())
                .extracting(DashboardResponse.TimeSeriesItem::month, DashboardResponse.TimeSeriesItem::count)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-01", 60L),
                        org.assertj.core.groups.Tuple.tuple("2026-02", 100L),
                        org.assertj.core.groups.Tuple.tuple("2026-03", 127L),
                        org.assertj.core.groups.Tuple.tuple("2026-04", 69L)
                );
        assertThat(response.contractStatusBreakdown()).hasSize(1);
        assertThat(response.contractStatusBreakdown().get(0).status()).isEqualTo(EContractStatus.COMPLETED);
        assertThat(response.contractStatusBreakdown().get(0).count()).isEqualTo(105L);
    }
}

package com.isums.contractservice.schedulers;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.entities.RenewalNotificationLog;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.RenewalReminderEvent;
import com.isums.contractservice.domains.events.RenewalWindowOpenEvent;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalNotificationLogRepository;
import com.isums.contractservice.infrastructures.repositories.RenewalRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RenewalNotificationScheduler")
class RenewalNotificationSchedulerTest {

    @Mock private EContractRepository contractRepo;
    @Mock private RenewalNotificationLogRepository logRepo;
    @Mock private RenewalRequestRepository renewalRequestRepo;
    @Mock private KafkaTemplate<String, Object> kafka;

    @InjectMocks private RenewalNotificationScheduler scheduler;

    private EContract contractEndingInDays(int daysUntilEnd) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Instant endAt = today.plusDays(daysUntilEnd).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        return EContract.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).houseId(UUID.randomUUID())
                .createdBy(UUID.randomUUID()).status(EContractStatus.IN_PROGRESS)
                .endAt(endAt).build();
    }

    @Nested
    @DisplayName("milestone notifications")
    class Milestones {

        @Test
        @DisplayName("sends RenewalReminderEvent at D-30 when no prior log and no existing request")
        void day30() {
            EContract c = contractEndingInDays(30);
            when(contractRepo.findByStatusInAndEndAtBetween(anyList(), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(c));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(eq(c.getId()), anyList()))
                    .thenReturn(false);
            when(logRepo.existsByMilestoneKey(anyString())).thenReturn(false);

            scheduler.processRenewalNotifications();

            verify(kafka).send(eq("contract.renewal.reminder"), anyString(), any(RenewalReminderEvent.class));
            verify(kafka, never()).send(eq("contract.renewal-window.open"), anyString(), any());
            verify(logRepo).save(any(RenewalNotificationLog.class));
        }

        @Test
        @DisplayName("at D-0 also publishes RenewalWindowOpenEvent")
        void day0() {
            EContract c = contractEndingInDays(0);
            when(contractRepo.findByStatusInAndEndAtBetween(anyList(), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(c));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(eq(c.getId()), anyList()))
                    .thenReturn(false);
            when(logRepo.existsByMilestoneKey(anyString())).thenReturn(false);

            scheduler.processRenewalNotifications();

            verify(kafka).send(eq("contract.renewal.reminder"), anyString(), any(RenewalReminderEvent.class));
            verify(kafka).send(eq("contract.renewal-window.open"), anyString(), any(RenewalWindowOpenEvent.class));
        }

        @Test
        @DisplayName("skips when days-until-end is not a milestone (e.g. 20)")
        void nonMilestone() {
            EContract c = contractEndingInDays(20);
            when(contractRepo.findByStatusInAndEndAtBetween(anyList(), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(c));

            scheduler.processRenewalNotifications();

            verify(kafka, never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("skips when already requested")
        void alreadyRequested() {
            EContract c = contractEndingInDays(14);
            when(contractRepo.findByStatusInAndEndAtBetween(anyList(), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(c));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(eq(c.getId()), anyList()))
                    .thenReturn(true);

            scheduler.processRenewalNotifications();

            verify(kafka, never()).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("idempotent: skips when milestone already logged")
        void milestoneAlreadyLogged() {
            EContract c = contractEndingInDays(7);
            when(contractRepo.findByStatusInAndEndAtBetween(anyList(), any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(c));
            when(renewalRequestRepo.existsByContractIdAndStatusNotIn(eq(c.getId()), anyList()))
                    .thenReturn(false);
            when(logRepo.existsByMilestoneKey(anyString())).thenReturn(true);

            scheduler.processRenewalNotifications();

            verify(kafka, never()).send(anyString(), anyString(), any());
            verify(logRepo, never()).save(any(RenewalNotificationLog.class));
        }
    }
}

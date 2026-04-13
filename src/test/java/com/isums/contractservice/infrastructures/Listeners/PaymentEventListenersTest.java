package com.isums.contractservice.infrastructures.Listeners;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.*;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.userservice.grpc.UserResponse;
import common.kafkas.IdempotencyService;
import common.kafkas.KafkaListenerHelper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentEventListeners (econtract-service)")
class PaymentEventListenersTest {

    @Mock private EContractRepository contractRepo;
    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private ObjectMapper objectMapper;
    @Mock private IdempotencyService idempotencyService;
    @Mock private KafkaListenerHelper kafkaHelper;
    @Mock private UserGrpcClient userGrpcClient;
    @Mock private Acknowledgment ack;

    @InjectMocks private PaymentEventListeners listener;

    private EContract contract(EContractStatus status) {
        return EContract.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .houseId(UUID.randomUUID()).createdBy(UUID.randomUUID())
                .tenantName("Alice").status(status).build();
    }

    @Nested
    @DisplayName("handle (deposit-refund-paid)")
    class HandleRefundPaid {

        private ConsumerRecord<String, String> rec =
                new ConsumerRecord<>("deposit-refund-paid-topic", 0, 0L, "k", "v");

        @Test
        @DisplayName("skips when duplicate")
        void duplicate() {
            when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
            when(idempotencyService.isDuplicate("m1")).thenReturn(true);

            listener.handle(rec, ack);

            verify(ack).acknowledge();
            verifyNoInteractions(contractRepo);
        }

        @Test
        @DisplayName("transitions to TERMINATED and publishes contract.terminated")
        void happy() throws Exception {
            UUID contractId = UUID.randomUUID();
            EContract c = contract(EContractStatus.DEPOSIT_REFUND_PENDING);
            c.setId(contractId);
            DepositRefundPaidEvent event = DepositRefundPaidEvent.builder()
                    .contractId(contractId).houseId(c.getHouseId()).tenantId(c.getUserId())
                    .refundAmount(1000L).messageId("m1").build();

            when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
            when(idempotencyService.isDuplicate("m1")).thenReturn(false);
            when(objectMapper.readValue("v", DepositRefundPaidEvent.class)).thenReturn(event);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            listener.handle(rec, ack);

            assertThat(c.getStatus()).isEqualTo(EContractStatus.TERMINATED);
            assertThat(c.getTerminatedAt()).isNotNull();
            verify(kafka).send(eq("contract.terminated"), anyString(), any(ContractTerminatedEvent.class));
            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("rethrows for retry when contract missing")
        void missing() throws Exception {
            DepositRefundPaidEvent event = DepositRefundPaidEvent.builder()
                    .contractId(UUID.randomUUID()).messageId("m1").build();
            when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
            when(idempotencyService.isDuplicate("m1")).thenReturn(false);
            when(objectMapper.readValue("v", DepositRefundPaidEvent.class)).thenReturn(event);
            when(contractRepo.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> listener.handle(rec, ack))
                    .isInstanceOf(RuntimeException.class);
            verify(ack, never()).acknowledge();
        }
    }

    @Nested
    @DisplayName("handleTerminationRequested")
    class TerminationRequested {

        private ConsumerRecord<String, String> rec =
                new ConsumerRecord<>("payment.termination-requested", 0, 0L, "k", "v");

        @Test
        @DisplayName("publishes email + OverdueTerminationRequested for active contract")
        void happy() throws Exception {
            UUID contractId = UUID.randomUUID();
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setId(contractId);
            TerminationRequestedEvent event = new TerminationRequestedEvent(
                    contractId, c.getHouseId(), c.getUserId(), UUID.randomUUID(), "OVERDUE", "m1");

            when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
            when(idempotencyService.isDuplicate("m1")).thenReturn(false);
            when(objectMapper.readValue("v", TerminationRequestedEvent.class)).thenReturn(event);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));
            when(userGrpcClient.getUserById(c.getCreatedBy().toString()))
                    .thenReturn(UserResponse.newBuilder().setEmail("m@x.com").setName("M").build());

            listener.handleTerminationRequested(rec, ack);

            verify(kafka).send(eq("notification-email"), any(SendEmailEvent.class));
            verify(kafka).send(eq("contract.termination-overdue-requested"), anyString(),
                    any(OverdueTerminationRequestedEvent.class));
            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("skips notifications when contract not in IN_PROGRESS/COMPLETED")
        void notActive() throws Exception {
            UUID contractId = UUID.randomUUID();
            EContract c = contract(EContractStatus.DRAFT);
            c.setId(contractId);
            TerminationRequestedEvent event = new TerminationRequestedEvent(
                    contractId, c.getHouseId(), c.getUserId(), UUID.randomUUID(), "X", "m1");

            when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
            when(idempotencyService.isDuplicate("m1")).thenReturn(false);
            when(objectMapper.readValue("v", TerminationRequestedEvent.class)).thenReturn(event);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            listener.handleTerminationRequested(rec, ack);

            verify(ack).acknowledge();
            verifyNoInteractions(userGrpcClient);
        }
    }

    @Nested
    @DisplayName("handlePowerCutRequest")
    class PowerCutRequest {

        private ConsumerRecord<String, String> rec =
                new ConsumerRecord<>("payment.power-cut-requested", 0, 0L, "k", "v");

        @Test
        @DisplayName("publishes PowerCutReviewRequested event")
        void happy() throws Exception {
            UUID contractId = UUID.randomUUID();
            EContract c = contract(EContractStatus.IN_PROGRESS);
            c.setId(contractId);
            PowerCutRequestEvent event = PowerCutRequestEvent.builder()
                    .contractId(contractId).houseId(c.getHouseId()).tenantId(c.getUserId())
                    .daysLate(15).totalAmount(5_000_000L).messageId("m1").build();

            when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
            when(idempotencyService.isDuplicate("m1")).thenReturn(false);
            when(objectMapper.readValue("v", PowerCutRequestEvent.class)).thenReturn(event);
            when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

            listener.handlePowerCutRequest(rec, ack);

            verify(kafka).send(eq("contract.power-cut-review-requested"), anyString(),
                    any(PowerCutReviewRequestedEvent.class));
            verify(ack).acknowledge();
        }
    }
}

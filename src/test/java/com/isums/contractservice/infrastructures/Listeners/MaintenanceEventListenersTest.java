package com.isums.contractservice.infrastructures.Listeners;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.InspectionDoneNotifyEvent;
import com.isums.contractservice.domains.events.JobCompletedEvent;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import common.kafkas.IdempotencyService;
import common.kafkas.KafkaListenerHelper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("MaintenanceEventListeners")
class MaintenanceEventListenersTest {

    @Mock private EContractRepository contractRepo;
    @Mock private KafkaTemplate<String, Object> kafka;
    @Mock private ObjectMapper objectMapper;
    @Mock private IdempotencyService idempotencyService;
    @Mock private KafkaListenerHelper kafkaHelper;
    @Mock private Acknowledgment ack;

    @InjectMocks private MaintenanceEventListeners listener;

    private ConsumerRecord<String, String> rec;

    @BeforeEach
    void setUp() {
        rec = new ConsumerRecord<>("job.inspection.done", 0, 0L, "k", "v");
    }

    private JobCompletedEvent event(String inspectionType, UUID contractId) {
        JobCompletedEvent e = new JobCompletedEvent();
        e.setInspectionType(inspectionType);
        e.setContractId(contractId);
        e.setReferenceId(UUID.randomUUID());
        return e;
    }

    @Test
    @DisplayName("skips and acks when message is duplicate")
    void duplicate() {
        when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
        when(idempotencyService.isDuplicate("m1")).thenReturn(true);

        listener.handleInspectionDone(rec, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(contractRepo, kafka);
    }

    @Test
    @DisplayName("skips non CHECK_OUT events")
    void nonCheckOut() throws Exception {
        when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
        when(idempotencyService.isDuplicate("m1")).thenReturn(false);
        when(objectMapper.readValue("v", JobCompletedEvent.class))
                .thenReturn(event("CHECK_IN", UUID.randomUUID()));

        listener.handleInspectionDone(rec, ack);

        verify(ack).acknowledge();
        verify(contractRepo, never()).findById(any());
    }

    @Test
    @DisplayName("skips when contractId null")
    void noContractId() throws Exception {
        when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
        when(idempotencyService.isDuplicate("m1")).thenReturn(false);
        when(objectMapper.readValue("v", JobCompletedEvent.class))
                .thenReturn(event("CHECK_OUT", null));

        listener.handleInspectionDone(rec, ack);

        verify(ack).acknowledge();
        verify(contractRepo, never()).findById(any());
    }

    @Test
    @DisplayName("transitions contract to INSPECTION_DONE and publishes event on happy path")
    void happyPath() throws Exception {
        UUID contractId = UUID.randomUUID();
        EContract c = EContract.builder()
                .id(contractId).userId(UUID.randomUUID()).houseId(UUID.randomUUID())
                .createdBy(UUID.randomUUID()).status(EContractStatus.PENDING_TERMINATION).build();
        when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
        when(idempotencyService.isDuplicate("m1")).thenReturn(false);
        when(objectMapper.readValue("v", JobCompletedEvent.class))
                .thenReturn(event("CHECK_OUT", contractId));
        when(contractRepo.findById(contractId)).thenReturn(Optional.of(c));

        listener.handleInspectionDone(rec, ack);

        assertThat(c.getStatus()).isEqualTo(EContractStatus.INSPECTION_DONE);
        verify(contractRepo).save(c);
        verify(kafka).send(eq("contract.inspection.done"), anyString(), any(InspectionDoneNotifyEvent.class));
        verify(idempotencyService).markProcessed("m1");
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("rethrows RuntimeException on failure (for Kafka retry)")
    void failure() throws Exception {
        UUID contractId = UUID.randomUUID();
        when(kafkaHelper.extractMessageId(rec)).thenReturn("m1");
        when(idempotencyService.isDuplicate("m1")).thenReturn(false);
        when(objectMapper.readValue("v", JobCompletedEvent.class))
                .thenReturn(event("CHECK_OUT", contractId));
        when(contractRepo.findById(contractId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listener.handleInspectionDone(rec, ack))
                .isInstanceOf(RuntimeException.class);
        verify(ack, never()).acknowledge();
    }
}

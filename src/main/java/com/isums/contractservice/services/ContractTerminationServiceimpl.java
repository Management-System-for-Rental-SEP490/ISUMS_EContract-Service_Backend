package com.isums.contractservice.services;

import com.isums.contractservice.domains.dtos.CompleteInspectionRequest;
import com.isums.contractservice.domains.entities.ContractInspection;
import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.ContractInspectionStatus;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.enums.JobAction;
import com.isums.contractservice.domains.events.InspectionDoneNotifyEvent;
import com.isums.contractservice.domains.events.InspectionScheduledEvent;
import com.isums.contractservice.domains.events.JobEvent;
import com.isums.contractservice.domains.events.SendEmailEvent;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.ContractInspectionRepository;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractTerminationServiceimpl implements ContractTerminationService {

    private final EContractRepository contractRepo;
    private final ContractInspectionRepository contractInspectionRepo;
    private final KafkaTemplate<String, Object> kafka;
    private final UserGrpcClient userGrpcClient;

    @Transactional
    public void handleExpiredContract(EContract contract) {
        contract.setStatus(EContractStatus.PENDING_TERMINATION);
        contractRepo.save(contract);

        ContractInspection inspection = ContractInspection.builder()
                .contractId(contract.getId())
                .houseId(contract.getHouseId())
                .status(ContractInspectionStatus.PENDING)
                .build();
        contractInspectionRepo.save(inspection);

        kafka.send("job.created", inspection.getId().toString(),
                JobEvent.builder()
                        .referenceId(inspection.getId())
                        .referenceType("INSPECTION")
                        .houseId(contract.getHouseId())
                        .action(JobAction.JOB_CREATED)
                        .build());

        notifyManagerContractExpired(contract, inspection.getId());

        kafka.send("contract.inspection.scheduled",
                inspection.getId().toString(),
                InspectionScheduledEvent.builder()
                        .contractId(contract.getId())
                        .inspectionId(inspection.getId())
                        .managerId(contract.getCreatedBy())
                        .tenantName(contract.getTenantName())
                        .messageId(UUID.randomUUID().toString())
                        .build());

        log.info("[Contract] PENDING_TERMINATION contractId={} inspectionId={}",
                contract.getId(), inspection.getId());
    }

    @Transactional
    public void completeInspection(UUID inspectionId, CompleteInspectionRequest req) {
        ContractInspection inspection = contractInspectionRepo.findById(inspectionId)
                .orElseThrow(() -> new RuntimeException("Inspection not found: " + inspectionId));

        inspection.setNotes(req.notes());
        inspection.setDeductionAmount(req.deductionAmount());
        inspection.setPhotoUrls(req.photoUrls());
        inspection.setStatus(ContractInspectionStatus.DONE);
        inspection.setCompletedAt(Instant.now());
        contractInspectionRepo.save(inspection);

        EContract contract = contractRepo.findById(inspection.getContractId()).orElseThrow();
        contract.getStatus().validateTransition(EContractStatus.INSPECTION_DONE);
        contract.setStatus(EContractStatus.INSPECTION_DONE);
        contractRepo.save(contract);

        kafka.send("job.completed", inspectionId.toString(),
                JobEvent.builder()
                        .referenceId(inspectionId)
                        .referenceType("INSPECTION")
                        .action(JobAction.JOB_COMPLETED)
                        .build());

        notifyManager(contract, inspection);

        kafka.send("contract.inspection.done",
                inspectionId.toString(),
                InspectionDoneNotifyEvent.builder()
                        .contractId(contract.getId())
                        .inspectionId(inspectionId)
                        .managerId(contract.getCreatedBy())
                        .deductionAmount(inspection.getDeductionAmount() != null
                                ? inspection.getDeductionAmount() : 0L)
                        .messageId(UUID.randomUUID().toString())
                        .build());

        log.info("[Contract] completeInspection inspectionId={} contractId={}",
                inspectionId, contract.getId());
    }

    private void notifyManager(EContract contract, ContractInspection inspection) {
        try {
            UserResponse manager = userGrpcClient.getUserById(contract.getCreatedBy().toString());

            kafka.send("notification-email",
                    SendEmailEvent.builder()
                            .to(manager.getEmail())
                            .templateCode("inspection_done_review")
                            .messageId(UUID.randomUUID().toString())
                            .params(Map.of(
                                    "managerName", manager.getName(),
                                    "contractId", contract.getId().toString().substring(0, 8).toUpperCase(),
                                    "inspectionId", inspection.getId().toString(),
                                    "houseId", contract.getHouseId().toString(),
                                    "deductionAmount", inspection.getDeductionAmount() != null
                                            ? formatVnd(inspection.getDeductionAmount()) : "0 ₫",
                                    "notes", inspection.getNotes() != null
                                            ? inspection.getNotes() : "Không có ghi chú"
                            ))
                            .build());

            log.info("[Contract] Notified manager email={} contractId={}",
                    manager.getEmail(), contract.getId());
        } catch (Exception e) {
            log.error("[Contract] Failed to notify manager contractId={}: {}",
                    contract.getId(), e.getMessage());
        }
    }

    private void notifyManagerContractExpired(EContract contract, UUID inspectionId) {
        try {
            UserResponse manager = userGrpcClient.getUserById(contract.getCreatedBy().toString());

            kafka.send("notification-email",
                    SendEmailEvent.builder()
                            .to(manager.getEmail())
                            .templateCode("contract_expired_inspection_scheduled")
                            .messageId(UUID.randomUUID().toString())
                            .params(Map.of(
                                    "managerName", manager.getName(),
                                    "contractId", contract.getId().toString().substring(0, 8).toUpperCase(),
                                    "tenantName", contract.getTenantName(),
                                    "houseId", contract.getHouseId().toString(),
                                    "inspectionId", inspectionId.toString()
                            ))
                            .build());

            log.info("[Contract] Notified manager on expiry email={} contractId={}",
                    manager.getEmail(), contract.getId());
        } catch (Exception e) {
            log.error("[Contract] Failed to notify manager on expiry contractId={}: {}",
                    contract.getId(), e.getMessage());
        }
    }

    private String formatVnd(Long amount) {
        return NumberFormat.getNumberInstance(Locale.of("vi", "VN")).format(amount) + " ₫";
    }
}

package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.JobCreatedEvent;
import com.isums.contractservice.domains.events.SendEmailEvent;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.ContractTerminationService;
import com.isums.contractservice.infrastructures.grpcs.UserGrpcClient;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import com.isums.userservice.grpc.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractTerminationServiceimpl implements ContractTerminationService {

    private final EContractRepository contractRepo;
    private final KafkaTemplate<String, Object> kafka;
    private final UserGrpcClient userGrpcClient;

    @Override
    @Transactional
    public void handleExpiredContract(EContract contract) {
        contract.getStatus().validateTransition(EContractStatus.PENDING_TERMINATION);
        contract.setStatus(EContractStatus.PENDING_TERMINATION);
        contractRepo.save(contract);

        kafka.send("job.created",
                contract.getId().toString(),
                JobCreatedEvent.builder()
                        .referenceId(contract.getId())
                        .houseId(contract.getHouseId())
                        .referenceType("INSPECTION")
                        .messageId(UUID.randomUUID().toString())
                        .build());

        notifyManagerContractExpired(contract);

        log.info("[Contract] PENDING_TERMINATION contractId={}", contract.getId());
    }

    @Override
    @Transactional
    public void confirmTerminationOverdue(UUID contractId, UUID actorId) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (contract.getStatus() != EContractStatus.IN_PROGRESS
                && contract.getStatus() != EContractStatus.COMPLETED) {
            throw new BusinessException("Hợp đồng không đang hoạt động");
        }

        handleExpiredContract(contract);

        log.info("[Contract] TerminationOverdue confirmed contractId={} by={}",
                contractId, actorId);
    }

    private void notifyManagerContractExpired(EContract contract) {
        try {
            UserResponse manager = userGrpcClient
                    .getUserById(contract.getCreatedBy().toString());

            kafka.send("notification-email",
                    SendEmailEvent.builder()
                            .to(manager.getEmail())
                            .templateCode("contract_expired_inspection_scheduled")
                            .messageId(UUID.randomUUID().toString())
                            .params(Map.of(
                                    "managerName", manager.getName(),
                                    "contractId", contract.getId().toString()
                                            .substring(0, 8).toUpperCase(),
                                    "tenantName", contract.getTenantName(),
                                    "houseId", contract.getHouseId().toString()
                            ))
                            .build());

            log.info("[Contract] Notified manager on expiry contractId={}",
                    contract.getId());
        } catch (Exception e) {
            log.error("[Contract] Failed to notify manager contractId={}: {}",
                    contract.getId(), e.getMessage());
        }
    }
}
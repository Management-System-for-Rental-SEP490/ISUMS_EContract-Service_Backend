package com.isums.contractservice.services;

import com.isums.contractservice.domains.entities.EContract;
import com.isums.contractservice.domains.enums.EContractStatus;
import com.isums.contractservice.domains.events.PowerCutConfirmedEvent;
import com.isums.contractservice.exceptions.BusinessException;
import com.isums.contractservice.exceptions.NotFoundException;
import com.isums.contractservice.infrastructures.abstracts.PowerCutService;
import com.isums.contractservice.infrastructures.repositories.EContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PowerCutServiceImpl implements PowerCutService {

    private final EContractRepository contractRepo;
    private final KafkaTemplate<String, Object> kafka;

    @Transactional
    public void confirmPowerCut(UUID contractId, UUID actorId) {
        EContract contract = contractRepo.findById(contractId)
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        if (contract.getStatus() != EContractStatus.IN_PROGRESS
                && contract.getStatus() != EContractStatus.COMPLETED) {
            throw new BusinessException("Hợp đồng không đang hoạt động");
        }

        if (!Boolean.TRUE.equals(contract.getHasPowerCutClause())) {
            throw new BusinessException("Hợp đồng này không có điều khoản cắt điện");
        }

        kafka.send("contract.power-cut-confirmed",
                contractId.toString(),
                PowerCutConfirmedEvent.builder()
                        .contractId(contractId)
                        .houseId(contract.getHouseId())
                        .tenantId(contract.getUserId())
                        .confirmedBy(actorId)
                        .executeAt(Instant.now().plus(24, ChronoUnit.HOURS))
                        .messageId(UUID.randomUUID().toString())
                        .build());

        log.info("[PowerCut] Confirmed contractId={} executeAt=+24h", contractId);
    }
}
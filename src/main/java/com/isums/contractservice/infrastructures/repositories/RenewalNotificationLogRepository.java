package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.RenewalNotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RenewalNotificationLogRepository extends JpaRepository<RenewalNotificationLog, UUID> {

    boolean existsByMilestoneKey(String milestoneKey);
}

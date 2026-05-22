package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.MaintenanceAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MaintenanceAuditLogRepository extends JpaRepository<MaintenanceAuditLog, Long> {
    List<MaintenanceAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

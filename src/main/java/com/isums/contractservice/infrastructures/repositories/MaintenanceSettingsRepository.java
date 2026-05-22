package com.isums.contractservice.infrastructures.repositories;

import com.isums.contractservice.domains.entities.MaintenanceSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaintenanceSettingsRepository extends JpaRepository<MaintenanceSettings, Integer> {
}

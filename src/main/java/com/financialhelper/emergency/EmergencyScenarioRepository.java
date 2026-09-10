package com.financialhelper.emergency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmergencyScenarioRepository
        extends JpaRepository<EmergencyScenario, UUID> {

    // Emergency Type 선택 화면용
    List<EmergencyScenario> findAllByOrderByDisplayOrderAsc();

    // 특정 type 하나 조회
    Optional<EmergencyScenario> findByEmergencyType(
            EmergencyType emergencyType
    );
}
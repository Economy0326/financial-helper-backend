package com.financialhelper.ai.information;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface InformationReportRepository extends JpaRepository<InformationReport, UUID> {
    Optional<InformationReport> findFirstByConsultation_IdOrderByGeneratedAtDesc(UUID consultationId);
}

package com.financialhelper.ai.information;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InformationReportRepository extends JpaRepository<InformationReport, UUID> {
    Optional<InformationReport> findFirstByConsultation_IdOrderByGeneratedAtDesc(UUID consultationId);
    Optional<InformationReport> findFirstByConsultation_IdOrderByGeneratedAtDescIdDesc(UUID consultationId);
    List<InformationReport> findByConsultation_IdInOrderByGeneratedAtDescIdDesc(Collection<UUID> consultationIds);
}

package com.financialhelper.procedure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProcedureVersionRepository extends JpaRepository<ProcedureVersion, UUID> {
    @Query("""
            select procedure
            from ProcedureVersion procedure
            where procedure.scenario = :scenario
              and procedure.institution = :institution
              and procedure.productType = :productType
              and procedure.status = com.financialhelper.procedure.ProcedureStatus.APPROVED
            order by procedure.version desc
            """)
    Optional<ProcedureVersion> findLatestApproved(
            @Param("scenario") String scenario,
            @Param("institution") String institution,
            @Param("productType") String productType
    );
}

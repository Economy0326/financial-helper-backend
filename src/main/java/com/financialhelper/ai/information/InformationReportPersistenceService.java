package com.financialhelper.ai.information;

import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.guest.GuestSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class InformationReportPersistenceService {
    private final GuestSessionService sessions;
    private final ConsultationRepository consultations;
    private final InformationReportRepository reports;
    private final InformationEvidenceSnapshotService assembler;
    private final JsonMapper mapper;

    public InformationReportPersistenceService(GuestSessionService sessions, ConsultationRepository consultations,
            InformationReportRepository reports, InformationEvidenceSnapshotService assembler, JsonMapper mapper) {
        this.sessions = sessions;
        this.consultations = consultations;
        this.reports = reports;
        this.assembler = assembler;
        this.mapper = mapper;
    }

    @Transactional
    public InformationReport save(UUID id, String token, InformationEvidenceSnapshot expected,
            com.financialhelper.retrieval.ConsultationOfficialInformationResponse candidates,
            com.financialhelper.retrieval.ConfirmedCaseSnapshotData confirmed,
            List<InformationEvidenceSnapshot.Evidence> selected,
            List<InformationExplanation> explanations) {
        var session = sessions.requireValidSession(token);
        var consultation = consultations.findForUpdateByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        var current = assembler.assemble(id, token, candidates, confirmed);
        if (!expected.equals(current) || !current.evidence().containsAll(selected)) {
            throw new IllegalStateException("information evidence changed before save");
        }
        try {
            return reports.saveAndFlush(new InformationReport(consultation, current,
                    mapper.writeValueAsString(candidates), mapper.writeValueAsString(current),
                    mapper.writeValueAsString(selected), mapper.writeValueAsString(explanations),
                    OffsetDateTime.now(ZoneOffset.UTC)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("information report could not be serialized");
        }
    }

    @Transactional(readOnly = true)
    public Optional<InformationReport> latest(UUID id, String token) {
        var session = sessions.requireValidSession(token);
        if (consultations.findByIdAndGuestSession_Id(id, session.getId()).isEmpty()) {
            throw new ConsultationNotFoundException();
        }
        return reports.findFirstByConsultation_IdOrderByGeneratedAtDesc(id);
    }
}

package com.financialhelper.consultation;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.InputLimitException;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.procedure.CardCaseFactExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class InformationIntakeService {
    private final GuestSessionService sessions;
    private final ConsultationRepository consultations;
    private final AccountProperties properties;

    public InformationIntakeService(GuestSessionService sessions, ConsultationRepository consultations,
            AccountProperties properties) {
        this.sessions = sessions; this.consultations = consultations; this.properties = properties;
    }

    @Transactional(readOnly = true)
    public State get(UUID id, String token) {
        var session = sessions.requireValidSession(token);
        var consultation = consultations.findByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        return state(consultation);
    }

    @Transactional
    public State update(UUID id, String token, String text, long expectedRevision) {
        var consultation = locked(id, token);
        if (consultation.getCaseInputRevision() != expectedRevision || consultation.getStatus() != ConsultationStatus.IN_PROGRESS
                || (!consultation.isInformationIntake() && (consultation.getCategory() != null
                || consultation.getCurrentStep() != ConsultationStep.CATEGORY))) throw new InvalidConsultationStateException();
        if (text.length() > properties.limits().maxSituationCharacters()) throw new InputLimitException("situation");
        ConsultationInputPrivacy.requireSafe(text);
        consultation.updateInformationIntake(text.trim(), OffsetDateTime.now(ZoneOffset.UTC));
        return state(consultation);
    }

    @Transactional
    public State confirm(UUID id, String token, ConsultationScenario scenario, long expectedRevision) {
        var consultation = locked(id, token);
        List<ConsultationScenario> candidates = InformationIntakeClassifier.candidates(consultation.getSituationText());
        if (!consultation.isInformationIntake() || consultation.getStatus() != ConsultationStatus.IN_PROGRESS
                || consultation.getCategory() != null || consultation.getCaseInputRevision() != expectedRevision
                || candidates.size() != 1 || candidates.getFirst() != scenario) throw new InvalidConsultationStateException();
        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                && CardCaseFactExtractor.hasExplicitUnsupportedProduct(consultation.getSituationText())) {
            throw new UnsupportedConsultationScopeException();
        }
        ConsultationCategory category = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? ConsultationCategory.CARD : ConsultationCategory.FINANCIAL_FRAUD;
        consultation.confirmInformationIntake(category, scenario, OffsetDateTime.now(ZoneOffset.UTC));
        return state(consultation);
    }

    private Consultation locked(UUID id, String token) {
        var session = sessions.requireValidSession(token);
        return consultations.findForUpdateByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
    }

    private static State state(Consultation c) {
        if (!c.isInformationIntake() && c.getCategory() != null) throw new InvalidConsultationStateException();
        List<ConsultationScenario> candidates = InformationIntakeClassifier.candidates(c.getSituationText());
        boolean unsupported = candidates.size() == 1 && candidates.getFirst() == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                && CardCaseFactExtractor.hasExplicitUnsupportedProduct(c.getSituationText());
        String status = c.isInformationIntake() && c.getScenario() != null ? "READY"
                : unsupported ? "UNSUPPORTED_SCOPE"
                : candidates.size() == 1 ? "SCENARIO_CONFIRMATION_REQUIRED" : "NEEDS_CLARIFICATION";
        return new State(c.getId(), c.getCaseInputRevision(), status, c.getSituationText(), candidates,
                c.isInformationIntake() ? c.getScenario() : null);
    }

    public record State(UUID consultationId, long caseInputRevision, String status, String situationText,
                        List<ConsultationScenario> scenarioCandidates, ConsultationScenario confirmedScenario) {}
}

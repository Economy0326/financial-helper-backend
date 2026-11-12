package com.financialhelper.consultation;

import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.InputLimitException;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.procedure.CardCaseFactExtractor;
import com.financialhelper.retrieval.GeneralTopicCoverageService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final GeneralTopicCoverageService topicCoverage;

    public InformationIntakeService(GuestSessionService sessions, ConsultationRepository consultations,
            AccountProperties properties) {
        this(sessions, consultations, properties, null);
    }

    @Autowired
    public InformationIntakeService(GuestSessionService sessions, ConsultationRepository consultations,
            AccountProperties properties, GeneralTopicCoverageService topicCoverage) {
        this.sessions = sessions; this.consultations = consultations; this.properties = properties;
        this.topicCoverage = topicCoverage;
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
        return update(id, token, text, null, expectedRevision);
    }

    @Transactional
    public State update(UUID id, String token, String text, FinancialTopic selectedTopic, long expectedRevision) {
        var consultation = locked(id, token);
        if (consultation.getCaseInputRevision() != expectedRevision || consultation.getStatus() != ConsultationStatus.IN_PROGRESS
                || (!consultation.isInformationIntake() && (consultation.getCategory() != null
                || consultation.getCurrentStep() != ConsultationStep.CATEGORY))) throw new InvalidConsultationStateException();
        if (text.length() > properties.limits().maxSituationCharacters()) throw new InputLimitException("situation");
        ConsultationInputPrivacy.requireSafe(text);
        consultation.updateInformationIntake(text.trim(), selectedTopic, OffsetDateTime.now(ZoneOffset.UTC));
        return state(consultation);
    }

    @Transactional
    public State confirm(UUID id, String token, ConsultationScenario scenario, long expectedRevision) {
        var consultation = locked(id, token);
        List<ConsultationScenario> candidates = InformationIntakeClassifier.candidates(consultation.getSituationText());
        var topics = FinancialTopicClassifier.classify(consultation.getSituationText()).topics();
        if (!consultation.isInformationIntake() || consultation.getStatus() != ConsultationStatus.IN_PROGRESS
                || consultation.getCategory() != null || consultation.getCaseInputRevision() != expectedRevision
                || candidates.size() != 1 || candidates.getFirst() != scenario
                || topics.size() > 1) throw new InvalidConsultationStateException();
        if (scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                && CardCaseFactExtractor.hasExplicitUnsupportedProduct(consultation.getSituationText())) {
            throw new UnsupportedConsultationScopeException();
        }
        ConsultationCategory category = scenario == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                ? ConsultationCategory.CARD : ConsultationCategory.FINANCIAL_FRAUD;
        consultation.confirmInformationIntake(category, scenario, OffsetDateTime.now(ZoneOffset.UTC));
        return state(consultation);
    }

    @Transactional
    public State confirmTopic(UUID id, String token, FinancialTopic topic, long expectedRevision) {
        var consultation = locked(id, token);
        var scenarioCandidates = InformationIntakeClassifier.candidates(consultation.getSituationText());
        var topics = FinancialTopicClassifier.classify(consultation.getSituationText()).topics();
        if (!consultation.isInformationIntake() || consultation.getStatus() != ConsultationStatus.IN_PROGRESS
                || consultation.getCategory() != null || consultation.getScenario() != null
                || consultation.getConfirmedInformationTopic() != null
                || consultation.getCaseInputRevision() != expectedRevision
                || !scenarioCandidates.isEmpty() || topics.size() != 1 || topics.getFirst() != topic) {
            throw new InvalidConsultationStateException();
        }
        consultation.confirmInformationTopic(topic, OffsetDateTime.now(ZoneOffset.UTC));
        return state(consultation);
    }

    private Consultation locked(UUID id, String token) {
        var session = sessions.requireValidSession(token);
        return consultations.findForUpdateByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
    }

    private State state(Consultation c) {
        if (!c.isInformationIntake() && c.getCategory() != null) throw new InvalidConsultationStateException();
        List<ConsultationScenario> candidates = InformationIntakeClassifier.candidates(c.getSituationText());
        var topicResult = FinancialTopicClassifier.classify(c.getSituationText());
        List<FinancialTopic> topics = topicResult.topics();
        FinancialTopic hint = c.getInformationTopicHint();
        boolean mismatch = hint != null && topics.size() == 1 && topics.getFirst() != hint;
        boolean unsupported = candidates.size() == 1 && candidates.getFirst() == ConsultationScenario.CARD_LOSS_UNAUTHORIZED_USE
                && CardCaseFactExtractor.hasExplicitUnsupportedProduct(c.getSituationText());
        boolean mixed = topics.size() > 1;
        boolean hasGeneralCorpus = c.getConfirmedInformationTopic() != null && topicCoverage != null
                && topicCoverage.hasCurrentCommonCorpus(c.getConfirmedInformationTopic());
        String status = c.getConfirmedInformationTopic() != null
                ? hasGeneralCorpus ? "TOPIC_CONFIRMED_SEARCH_AVAILABLE" : "TOPIC_CONFIRMED_NO_SEARCH"
                : c.isInformationIntake() && c.getScenario() != null ? "READY"
                : unsupported ? "UNSUPPORTED_SCOPE"
                : candidates.size() > 1 ? "NEEDS_CLARIFICATION"
                : mixed ? "MULTIPLE_TOPICS"
                : candidates.size() == 1 ? "SCENARIO_CONFIRMATION_REQUIRED"
                : topics.size() == 1 ? (mismatch ? "TOPIC_MISMATCH_CONFIRMATION_REQUIRED" : "TOPIC_CONFIRMATION_REQUIRED")
                : topicResult.unresolvedStatus();
        String routingStatus = topicResult.emergencyRecommended() ? "EMERGENCY_RECOMMENDED"
                : mismatch ? "TOPIC_MISMATCH_CONFIRMATION_REQUIRED"
                : mixed || candidates.size() > 1 ? "MULTIPLE_TOPICS" : status;
        String route = topicResult.emergencyRecommended() ? "EMERGENCY"
                : unsupported || mixed || candidates.size() > 1 ? "NONE"
                : c.getScenario() != null || candidates.size() == 1 ? "ACTION"
                : topics.size() == 1 ? "INFORMATION" : "NONE";
        return new State(c.getId(), c.getCaseInputRevision(), status, c.getSituationText(), candidates,
                c.isInformationIntake() ? c.getScenario() : null, topics, hint,
                c.getConfirmedInformationTopic(), routingStatus, route,
                c.getConfirmedInformationTopic() == null ? "NOT_EVALUATED"
                        : hasGeneralCorpus ? "REVIEWED_READY_CORPUS" : "NO_APPROVED_READY_EVIDENCE",
                hasGeneralCorpus);
    }

    public record State(UUID consultationId, long caseInputRevision, String status, String situationText,
                        List<ConsultationScenario> scenarioCandidates, ConsultationScenario confirmedScenario,
                        List<FinancialTopic> topicCandidates, FinancialTopic selectedTopic,
                        FinancialTopic confirmedTopic, String routingStatus, String suggestedRoute,
                        String evidenceAvailability, boolean generalInformationSearchAvailable) {}
}

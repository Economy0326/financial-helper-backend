package com.financialhelper.ai.information;

import com.financialhelper.ai.followup.FollowUpQuestionRepository;
import com.financialhelper.consultation.ConsultationNotFoundException;
import com.financialhelper.consultation.ConsultationRepository;
import com.financialhelper.consultation.ConsultationScenario;
import com.financialhelper.consultation.ConsultationScenarioResolver;
import com.financialhelper.guest.GuestSessionService;
import com.financialhelper.procedure.ScenarioRoutingFactContract;
import com.financialhelper.retrieval.ConfirmedCaseSnapshotData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Short read-only capture of explicit input and reviewed common routing/date answers. */
@Service
public class InformationConfirmedFactsService {
    private final GuestSessionService sessions;
    private final ConsultationRepository consultations;
    private final FollowUpQuestionRepository questions;
    private final JsonMapper mapper;

    public InformationConfirmedFactsService(GuestSessionService sessions, ConsultationRepository consultations,
            FollowUpQuestionRepository questions, JsonMapper mapper) {
        this.sessions = sessions;
        this.consultations = consultations;
        this.questions = questions;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ConfirmedCaseSnapshotData current(UUID id, String token) {
        var session = sessions.requireValidSession(token);
        var consultation = consultations.findByIdAndGuestSession_Id(id, session.getId())
                .orElseThrow(ConsultationNotFoundException::new);
        ConsultationScenario scenario = ConsultationScenarioResolver.resolve(consultation);
        List<ConfirmedCaseSnapshotData.Fact> facts = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (consultation.getCategory() == null) missing.add("CATEGORY");
        else facts.add(new ConfirmedCaseSnapshotData.Fact("CATEGORY", "category",
                consultation.getCategory().name(), null, "USER_SELECTED", null));
        if (scenario == ConsultationScenario.UNKNOWN) missing.add("SCENARIO");
        else facts.add(new ConfirmedCaseSnapshotData.Fact("SCENARIO", "scenario",
                scenario.name(), null, "SYSTEM_RESOLVED_FROM_EXPLICIT_INPUT", null));
        if (consultation.getSituationText() == null || consultation.getSituationText().isBlank()) {
            missing.add("SITUATION");
        } else {
            facts.add(new ConfirmedCaseSnapshotData.Fact("SITUATION", "situationText",
                    consultation.getSituationText(), null, "USER_STATED", null));
        }
        Set<String> seenKeys = new HashSet<>();
        for (var question : questions.findByConsultation_IdAndCaseInputRevisionAndSupersededAtIsNullOrderBySequenceNoAsc(
                id, consultation.getCaseInputRevision())) {
            if (!("SCENARIO_ROUTING".equals(question.getQuestionScope())
                    || "COMMON_DATE".equals(question.getQuestionScope()))) continue;
            var contract = ScenarioRoutingFactContract.byId(question.getFactContractId());
            boolean correctScope = "COMMON_DATE".equals(question.getQuestionScope())
                    ? contract == ScenarioRoutingFactContract.date(scenario)
                    : contract == ScenarioRoutingFactContract.routing(question.getFactKey());
            if (contract == null || contract.scenario() != scenario || !correctScope
                    || question.getProcedureVersionId() != null || !contract.matches(question, mapper)
                    || !seenKeys.add(contract.key())) {
                throw new IllegalStateException("common fact attribution is invalid");
            }
            if (!question.isAnswered() || question.getAnswerValue().isBlank()
                    || "UNKNOWN".equalsIgnoreCase(question.getAnswerValue())) {
                missing.add(contract.key());
                continue;
            }
            facts.add(new ConfirmedCaseSnapshotData.Fact(contract.factType(), contract.key(),
                    question.getAnswerValue(), null, "USER_ANSWERED", question.getId()));
        }
        return new ConfirmedCaseSnapshotData(null, id, consultation.getCaseInputRevision(),
                consultation.getFollowUpAnswerRevision(), facts, missing, null);
    }
}

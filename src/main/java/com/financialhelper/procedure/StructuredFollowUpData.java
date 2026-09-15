package com.financialhelper.procedure;

import java.util.List;

public record StructuredFollowUpData(
        String scenario,
        String institution,
        String productType,
        long caseInputRevision,
        List<String> missingFacts,
        List<FollowUpQuestionSpec> questions
) {
    public StructuredFollowUpData {
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public boolean complete() {
        return questions.isEmpty();
    }
}

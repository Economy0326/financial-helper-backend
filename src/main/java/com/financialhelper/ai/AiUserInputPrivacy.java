package com.financialhelper.ai;

import com.financialhelper.ai.summary.ConsultationSummaryAiResult;
import com.financialhelper.ai.analysis.AnalysisAiResult;
import com.financialhelper.ai.understanding.CaseUnderstandingAiResult;
import com.financialhelper.consultation.ConsultationInputPrivacy;

/** Checks user-derived text from older rows before it can enter another provider request. */
public final class AiUserInputPrivacy {
    private AiUserInputPrivacy() {}

    public static void requireSafe(ConsultationSummaryAiResult summary) {
        if (summary == null) return;
        ConsultationInputPrivacy.requireSafe(summary.headline);
        ConsultationInputPrivacy.requireSafe(summary.summaryText);
        if (summary.keyPoints != null) summary.keyPoints.forEach(point -> {
            if (point != null) ConsultationInputPrivacy.requireSafe(point.text);
        });
    }

    public static void requireSafe(CaseUnderstandingAiResult result) {
        if (result == null) return;
        if (result.facts != null) result.facts.forEach(fact -> {
            if (fact != null) {
                ConsultationInputPrivacy.requireSafe(fact.label);
                ConsultationInputPrivacy.requireSafe(fact.value);
            }
        });
        if (result.missingInformation != null) result.missingInformation.forEach(missing -> {
            if (missing != null) {
                ConsultationInputPrivacy.requireSafe(missing.topic);
                ConsultationInputPrivacy.requireSafe(missing.reason);
            }
        });
    }

    public static void requireSafe(AnalysisAiResult analysis) {
        if (analysis == null) return;
        ConsultationInputPrivacy.requireSafe(analysis.analysisSummary);
        if (analysis.keyIssues != null) analysis.keyIssues.forEach(issue -> {
            if (issue != null) {
                ConsultationInputPrivacy.requireSafe(issue.title);
                ConsultationInputPrivacy.requireSafe(issue.explanation);
            }
        });
        if (analysis.additionalInformationNeeded != null) analysis.additionalInformationNeeded.forEach(item -> {
            if (item != null) {
                ConsultationInputPrivacy.requireSafe(item.topic);
                ConsultationInputPrivacy.requireSafe(item.reason);
            }
        });
    }
}

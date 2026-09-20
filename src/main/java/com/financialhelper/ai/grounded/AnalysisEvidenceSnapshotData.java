package com.financialhelper.ai.grounded;

import com.financialhelper.law.LawEvidence;
import com.financialhelper.procedure.FinancialActionPlanData;
import com.financialhelper.consultation.ConsultationScenario;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

    /** 한 번의 AI 실행에서 사용한 Evidence를 JSON-safe하고 replay 가능하게 표현한다. */
public record AnalysisEvidenceSnapshotData(
        UUID id,
        UUID consultationId,
        UUID analysisJobId,
        long caseInputRevision,
        long followUpAnswerRevision,
        UUID procedureVersionId,
        UUID financialActionPlanId,
        UUID retrievalGenerationId,
        String retrievalGenerationKey,
        String retrievalModelIdentifier,
        String retrievalModelRevision,
        String retrievalTokenizerIdentifier,
        String retrievalTokenizerRevision,
        String retrievalEncodingConfigJson,
        String retrievalIndexConfigJson,
        String retrievalCorpusSnapshotSha256,
        FinancialActionPlanData actionPlan,
        List<SourceEvidence> sourceEvidence,
        List<ReviewedLawEvidence> reviewedLawEvidence,
        int snapshotRevision,
        AnalysisEvidenceSnapshotStatus status,
        OffsetDateTime capturedAt,
        ConsultationScenario scenario
) {
    /** legacy CARD snapshot fixture를 위한 호환 생성자다. */
    public AnalysisEvidenceSnapshotData(
            UUID id,
            UUID consultationId,
            UUID analysisJobId,
            long caseInputRevision,
            long followUpAnswerRevision,
            UUID procedureVersionId,
            UUID financialActionPlanId,
            UUID retrievalGenerationId,
            String retrievalGenerationKey,
            String retrievalModelIdentifier,
            String retrievalModelRevision,
            String retrievalTokenizerIdentifier,
            String retrievalTokenizerRevision,
            String retrievalEncodingConfigJson,
            String retrievalIndexConfigJson,
            String retrievalCorpusSnapshotSha256,
            FinancialActionPlanData actionPlan,
            List<SourceEvidence> sourceEvidence,
            List<ReviewedLawEvidence> reviewedLawEvidence,
            int snapshotRevision,
            AnalysisEvidenceSnapshotStatus status,
            OffsetDateTime capturedAt
    ) {
        this(id, consultationId, analysisJobId, caseInputRevision, followUpAnswerRevision,
                procedureVersionId, financialActionPlanId, retrievalGenerationId,
                retrievalGenerationKey, retrievalModelIdentifier, retrievalModelRevision,
                retrievalTokenizerIdentifier, retrievalTokenizerRevision,
                retrievalEncodingConfigJson, retrievalIndexConfigJson,
                retrievalCorpusSnapshotSha256, actionPlan, sourceEvidence,
                reviewedLawEvidence, snapshotRevision, status, capturedAt, null);
    }

    public AnalysisEvidenceSnapshotData {
        sourceEvidence = sourceEvidence == null ? List.of() : List.copyOf(sourceEvidence);
        reviewedLawEvidence = reviewedLawEvidence == null ? List.of() : List.copyOf(reviewedLawEvidence);
    }

    public Set<String> evidenceIds() {
        return sourceEvidence.stream().map(SourceEvidence::evidenceId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> lawEvidenceIds() {
        return reviewedLawEvidence.stream().map(ReviewedLawEvidence::evidenceId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public record SourceEvidence(
            String evidenceId,
            UUID sourceDocumentId,
            int documentVersion,
            String rawSha256,
            String contentSha256,
            String organizationName,
            String officialDomain,
            String canonicalUrl,
            String resolvedUrl,
            String title,
            UUID sourceChunkId,
            int sequence,
            String articleReference,
            String pageReference,
            String locator,
            String body
    ) {
        public SourceEvidence {
            if (evidenceId == null || evidenceId.isBlank() || sourceChunkId == null
                    || sourceDocumentId == null || body == null || body.isBlank()) {
                throw new IllegalArgumentException("source evidence identity/content is required");
            }
        }
    }

    public record ReviewedLawEvidence(
            String evidenceId,
            String statuteName,
            String lawIdentifier,
            String mst,
            String articleLocator,
            String effectiveDate,
            String canonicalUrl,
            String sourceUrl,
            String derivedUrl,
            String text,
            String toolName,
            String serverVersion,
            String serverCommit,
            String acquisitionKind,
            String validationStatus,
            OffsetDateTime retrievedAt
    ) {
        public ReviewedLawEvidence {
            if (evidenceId == null || evidenceId.isBlank() || statuteName == null || statuteName.isBlank()
                    || lawIdentifier == null || lawIdentifier.isBlank() || mst == null || mst.isBlank()
                    || articleLocator == null || articleLocator.isBlank() || effectiveDate == null
                    || effectiveDate.isBlank() || text == null || text.isBlank()) {
                throw new IllegalArgumentException("reviewed law evidence identity/content is required");
            }
        }

        public static ReviewedLawEvidence from(LawEvidence evidence) {
            String id = "law:" + evidence.lawIdentifier() + ":" + evidence.mst()
                    + ":" + evidence.articleLocator();
            return new ReviewedLawEvidence(
                    id,
                    evidence.statuteName(),
                    evidence.lawIdentifier(),
                    evidence.mst(),
                    evidence.articleLocator(),
                    evidence.effectiveDate(),
                    evidence.canonicalUrl(),
                    evidence.sourceUrl(),
                    evidence.derivedUrl(),
                    evidence.text(),
                    evidence.toolName(),
                    evidence.serverVersion(),
                    evidence.serverCommit(),
                    evidence.acquisitionKind(),
                    evidence.validationStatus(),
                    evidence.retrievedAt().atOffset(java.time.ZoneOffset.UTC)
            );
        }
    }
}

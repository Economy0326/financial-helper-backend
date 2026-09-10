package com.financialhelper.ai.understanding;

import com.financialhelper.consultation.ConsultationCategory;

import java.time.OffsetDateTime;
import java.util.UUID;

// Service가 JPA Entity를 직접 다루지 않고, 필요한 데이터만 담아 전달하기 위한 DTO 클래스
public final class CaseUnderstandingData {

    // private => 직접 생성 x
    private CaseUnderstandingData() {
    }

    public record Snapshot(
            UUID consultationId,
            UUID guestSessionId,
            ConsultationCategory category,
            String situationText,
            long caseInputRevision
    ) {
    }

    public record Document(
            UUID consultationId,
            long caseInputRevision,
            String model,
            CaseUnderstandingAiResult result,
            OffsetDateTime generatedAt
    ) {
    }
}
package com.financialhelper.retrieval;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 검색 범위는 요청에서 받지 않고 현재 상담의 확인된 사실에서만 만든다. */
public record ConsultationOfficialInformationRequest(
        @NotNull @Min(0) Long expectedCaseInputRevision
) {}

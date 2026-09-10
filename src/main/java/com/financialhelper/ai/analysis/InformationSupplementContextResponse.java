package com.financialhelper.ai.analysis;

import java.util.List;

public record InformationSupplementContextResponse(
        boolean active,
        int supplementCount,
        List<Item> neededInformation
) {
    // 추가정보 보완 모드가 아닐 때, 기존 보완 횟수만 유지하고 필요한 정보 목록은 비워서 반환한다.
    public static InformationSupplementContextResponse inactive(
            int supplementCount
    ) {

        return new InformationSupplementContextResponse(
                false,
                supplementCount,
                List.of()
        );
    }

    public record Item(
            String topic,
            String reason
    ) {
    }
}
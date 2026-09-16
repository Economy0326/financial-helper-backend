package com.financialhelper.source;

// 한 번 처리하고 호출한 코드에 결과를 알려주는 객체
// 따라서 DB나 Entity 필요 없음
public record SourceIngestionResult(
        String sourceKey,
        Outcome outcome,
        Integer documentVersion,
        String contentSha256,
        String failureCode
) {

    public enum Outcome {

        // 첫 수집
        CREATED,

        // 변경이 없으면 -> 확인 시간만 변경
        UNCHANGED,

        // 본문이 바뀌면 -> 버전 + Hash 변경
        UPDATED,

        // 실패
        FAILED
    }

    public static SourceIngestionResult failed(
            String sourceKey,
            String failureCode
    ) {
        return new SourceIngestionResult(
                sourceKey,
                Outcome.FAILED,
                null,
                null,
                failureCode
        );
    }
}
package com.financialhelper.ai.understanding;

import com.financialhelper.common.error.ApiException;

import org.springframework.http.HttpStatus;

// AI 응답 생성 실패 Exception
// Provider 장애, Structured Output 실패 모두 처리
public class AiGenerationFailedException
        extends ApiException {

    public AiGenerationFailedException() {
        super(
                HttpStatus.BAD_GATEWAY,
                "AI_GENERATION_FAILED",
                "AI 응답을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요."
        );
    }
}
package com.financialhelper.ai.understanding;

import com.financialhelper.common.error.ApiException;

import org.springframework.http.HttpStatus;

// 입력 변경 Exception
public class AiInputChangedException
        extends ApiException {

    public AiInputChangedException() {
        super(
                HttpStatus.CONFLICT,
                "AI_INPUT_CHANGED",
                "상담 내용이 변경되었습니다. 다시 시도해 주세요."
        );
    }
}
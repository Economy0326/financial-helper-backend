package com.financialhelper.ai.followup;

import com.financialhelper.common.error.ApiException;

import org.springframework.http.HttpStatus;

public class InvalidFollowUpAnswerException
        extends ApiException {

    public InvalidFollowUpAnswerException() {
        super(
                HttpStatus.BAD_REQUEST,
                "INVALID_FOLLOW_UP_ANSWER",
                "선택할 수 없는 답변입니다."
        );
    }
}
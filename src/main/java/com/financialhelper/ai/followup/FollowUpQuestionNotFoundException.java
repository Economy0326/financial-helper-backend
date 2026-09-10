package com.financialhelper.ai.followup;

import com.financialhelper.common.error.ApiException;

import org.springframework.http.HttpStatus;

public class FollowUpQuestionNotFoundException
        extends ApiException {

    public FollowUpQuestionNotFoundException() {
        super(
                HttpStatus.NOT_FOUND,
                "FOLLOW_UP_QUESTION_NOT_FOUND",
                "추가 질문을 찾을 수 없습니다."
        );
    }
}
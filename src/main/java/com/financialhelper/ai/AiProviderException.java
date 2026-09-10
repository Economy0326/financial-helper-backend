package com.financialhelper.ai;

// 외부 AI 제공자 호출 자체 실패
public class AiProviderException
        extends RuntimeException {

    public AiProviderException(
            String message
    ) {
        super(message);
    }
}
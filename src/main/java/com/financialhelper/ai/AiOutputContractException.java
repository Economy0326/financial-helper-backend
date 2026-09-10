package com.financialhelper.ai;

// AI 출력 결과가 계약을 위반했을 때 발생하는 예외
public class AiOutputContractException
        extends RuntimeException {

    public AiOutputContractException(
            String message
    ) {
        super(message);
    }
}
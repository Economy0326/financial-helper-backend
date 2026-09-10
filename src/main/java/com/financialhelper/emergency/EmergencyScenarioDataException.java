package com.financialhelper.emergency;

import com.financialhelper.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class EmergencyScenarioDataException
        extends ApiException {

    public EmergencyScenarioDataException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EMERGENCY_GUIDANCE_UNAVAILABLE",
                "현재 긴급 대응 정보를 제공할 수 없습니다."
        );
    }
}
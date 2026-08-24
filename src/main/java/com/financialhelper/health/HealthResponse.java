// 응답 JSON 형태를 명확히 정하기 위해서
package com.financialhelper.health;

// record: 단순 데이터 객체를 짧게 표현
public record HealthResponse(
        String status,
        String database
) {
}
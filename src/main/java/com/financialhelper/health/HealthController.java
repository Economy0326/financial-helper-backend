// HTTP endpoint
package com.financialhelper.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final HealthService healthService;
    private final ReadinessService readinessService;

    public HealthController(HealthService healthService, ReadinessService readinessService) {
        this.healthService = healthService;
        this.readinessService = readinessService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean databaseUp = healthService.isDatabaseUp();

        // 200
        if (databaseUp) {
            return ResponseEntity.ok(
                    new HealthResponse("UP", "UP")
            );
        }

        // 503 -> Health check는 서비스가 정상인지 확인하는 용도이므로, 서비스가 정상적이지 않다면 503을 반환하는 것이 적절함
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("DOWN", "DOWN"));
    }

    @GetMapping({"/health/readiness", "/ready"})
    public ResponseEntity<ReadinessResponse> readiness() {
        ReadinessService.ReadinessResult result = readinessService.check();
        ReadinessResponse response = new ReadinessResponse(result.status(), result.dependencies());
        return "READY".equals(result.status())
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}

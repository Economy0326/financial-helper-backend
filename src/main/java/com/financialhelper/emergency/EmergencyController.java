package com.financialhelper.emergency;

import com.financialhelper.guest.GuestSessionCookie;
import com.financialhelper.guest.GuestSessionCookieService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/emergency")
public class EmergencyController {

    private final EmergencyService emergencyService;
    private final GuestSessionCookieService cookieService;

    public EmergencyController(
            EmergencyService emergencyService,
            GuestSessionCookieService cookieService
    ) {
        this.emergencyService = emergencyService;
        this.cookieService = cookieService;
    }

    // 서버에 저장되어있는 Emergency Type
    @GetMapping("/types")
    public List<EmergencyTypeOptionResponse> getTypes() {
        return emergencyService.getTypes();
    }

    // 사용자 상태 데이터
    @PutMapping("/selection")
    public ResponseEntity<EmergencySelectionResponse> selectType(
            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken,

            @Valid
            @RequestBody
            SelectEmergencyTypeRequest request,

            // Set-Cookie 헤더 추가
            HttpServletResponse servletResponse
    ) {
        EmergencySelectionResult result =
                emergencyService.selectType(
                        rawToken,
                        request
                );

        result.getRawTokenToSet()
                .ifPresent(token ->
                        servletResponse.addHeader(
                                HttpHeaders.SET_COOKIE,
                                cookieService
                                        .create(token)
                                        .toString()
                        )
                );

        return ResponseEntity.ok(
                result.getResponse()
        );
    }

    // 서버가 갖고 있는 긴급대응 기준 데이터
    @GetMapping("/scenario")
    public EmergencyScenarioStateResponse getScenario(
            @CookieValue(
                    name = GuestSessionCookie.NAME,
                    required = false
            )
            String rawToken
    ) {
        return emergencyService.getScenario(rawToken);
    }
}
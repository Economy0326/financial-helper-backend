package com.financialhelper.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
    private final AccountOverviewService overviewService;

    public AccountController(AccountOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/me")
    public AccountOverviewResponse me() { return overviewService.overview(); }

    @GetMapping("/consultations")
    public Page<AccountConsultationHistoryItem> consultations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return overviewService.history(pageable);
    }

    @GetMapping("/emergency-history")
    public Page<AccountEmergencyHistoryItem> emergencyHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return overviewService.emergencyHistory(pageable);
    }
}

package com.financialhelper.consultation;

import com.financialhelper.account.Account;
import com.financialhelper.account.AccountAuthenticationException;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.account.AccountConsultationQuotaService;
import com.financialhelper.account.AccountRepository;
import com.financialhelper.account.InputLimitException;
import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionResolution;
import com.financialhelper.guest.GuestSessionService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Service
public class ConsultationService {

    private final ConsultationRepository consultationRepository;
    private final GuestSessionService guestSessionService;
    private final AccountSessionService accountSessionService;
    private final AccountProperties accountProperties;
    private final AccountConsultationQuotaService consultationQuotaService;
    private final AccountRepository accountRepository;

    @Autowired
    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            AccountSessionService accountSessionService,
            AccountProperties accountProperties,
            AccountConsultationQuotaService consultationQuotaService,
            AccountRepository accountRepository
    ) {
        this.consultationRepository = consultationRepository;
        this.guestSessionService = guestSessionService;
        this.accountSessionService = accountSessionService;
        this.accountProperties = accountProperties;
        this.consultationQuotaService = consultationQuotaService;
        this.accountRepository = accountRepository;
    }

    /** Compatibility constructor for existing unit tests and non-web callers. */
    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            AccountSessionService accountSessionService,
            AccountProperties accountProperties
    ) {
        this(consultationRepository, guestSessionService, accountSessionService,
                accountProperties, null, null);
    }

    /** Compatibility constructor for existing unit tests and non-web callers. */
    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService
    ) {
        this(consultationRepository, guestSessionService, null, null, null, null);
    }

    private static final Set<ConsultationStep>
        CATEGORY_EDITABLE_STEPS =
        EnumSet.of(
                ConsultationStep.CATEGORY,
                ConsultationStep.SITUATION
        );

    private static final Set<ConsultationStep>
        SITUATION_EDITABLE_STEPS =
        EnumSet.of(
                ConsultationStep.SITUATION,
                ConsultationStep.FOLLOW_UP,
                // 요약 수정 => Situation에서 진행
                ConsultationStep.SUMMARY
        );

    // 새로운 상담 생성
    @Transactional
    public ConsultationStartResult startConsultation(
            String rawToken
    ) {
        return startConsultation(rawToken, false);
    }

    // Default start resumes the account's active consultation. Explicit new starts
    // close the active consultation and consume one rolling-window start quota.
    @Transactional
    public ConsultationStartResult startConsultation(
            String rawToken,
            boolean startNew
    ) {
        Account account = accountSessionService == null
                ? null : accountSessionService.currentAccount().orElse(null);
        if (accountProperties != null && accountProperties.generalConsultationRequired() && account == null) {
            throw AccountAuthenticationException.required();
        }
        GuestSessionResolution sessionResolution =
                guestSessionService
                        .resolveOrCreate(
                                rawToken
                        );

        GuestSession guestSession =
                sessionResolution.getGuestSession();

        if (account != null && accountRepository != null) {
            accountRepository.findForUpdate(account.getId());
            Optional<Consultation> accountActive = consultationRepository
                    .findActiveForUpdateByAccountId(account.getId(), ConsultationStatus.activeStatuses());
            if (accountActive.isPresent()) {
                Consultation active = accountActive.get();
                if (!startNew) {
                    if (sessionResolution.getRawTokenToSet().isPresent()) {
                        guestSession.bindAccount(account);
                    }
                    active.bindAccount(account);
                    return new ConsultationStartResult(ConsultationCreateResponse.from(active),
                            sessionResolution.getRawTokenToSet().orElse(null));
                }
                active.abandon(OffsetDateTime.now(ZoneOffset.UTC));
            }
        }

        Optional<Consultation> activeConsultation =
                consultationRepository
                        .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                                guestSession.getId(),
                                ConsultationStatus.activeStatuses()
                        );

        if (activeConsultation.isPresent()
                && (account == null || (activeConsultation.get().getAccount() != null
                && activeConsultation.get().getAccount().getId().equals(account.getId())))) {
            return new ConsultationStartResult(
                    ConsultationCreateResponse.from(
                            activeConsultation.get()
                    ),
                    sessionResolution
                            .getRawTokenToSet()
                            .orElse(null)
            );
        }

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        Consultation consultation =
                new Consultation(
                        guestSession,
                        now
        );
        if (account != null) {
            if (sessionResolution.getRawTokenToSet().isPresent()) {
                guestSession.bindAccount(account);
            }
            consultation.bindAccount(account);
        }

        Consultation savedConsultation =
                consultationRepository.save(
                        consultation
                );

        if (account != null && consultationQuotaService != null) {
            consultationQuotaService.recordNewConsultation(account, savedConsultation, now);
        }

        return new ConsultationStartResult(
                ConsultationCreateResponse.from(
                        savedConsultation
                ),
                sessionResolution
                        .getRawTokenToSet()
                        .orElse(null)
        );
    }

    // 이어서하기 조회
    @Transactional(readOnly = true)
    public ActiveConsultationResponse getActiveConsultation(
            String rawToken
    ) {
        Account account = accountSessionService == null ? null
                : accountSessionService.currentAccount().orElse(null);
        Consultation consultation;
        if (account != null) {
            consultation = consultationRepository
                    .findFirstByAccount_IdAndStatusInOrderByUpdatedAtDesc(
                            account.getId(), ConsultationStatus.resumableStatuses())
                    .orElseThrow(ConsultationNotFoundException::new);
        } else {
            GuestSession guestSession = guestSessionService.requireValidSession(rawToken);
            consultation = consultationRepository
                    .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                            guestSession.getId(), ConsultationStatus.resumableStatuses())
                    .orElseThrow(ConsultationNotFoundException::new);
        }

        return ActiveConsultationResponse.from(
                consultation
        );
    }

    // Consultation 상세 조회
    @Transactional(readOnly = true)
    public ConsultationDetailResponse getConsultation(
            UUID consultationId,
            String rawToken
    ) {
        GuestSession guestSession =
                guestSessionService.requireValidSession(
                        rawToken
                );

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        return ConsultationDetailResponse.from(
                consultation
        );
    }
    
    // Category
    @Transactional
    public UpdateConsultationCategoryResponse updateCategory(
            UUID consultationId,
            String rawToken,
            UpdateConsultationCategoryRequest request
    ) {
        // 해당 Guest는 유효한가
        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);
        
        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        ensureInProgress(consultation);

        // 현재 단계에서 Category 수정이 가능한가
        if (!CATEGORY_EDITABLE_STEPS.contains(
                consultation.getCurrentStep()
        )) {
            throw new InvalidConsultationStateException();
        }

        // 실제 카테고리 수정
        // Entity 값을 변경하면 JPA Dirty Checking으로 Transaction 종료 시 DB에 자동 반영된다
        // 그래서 update 후 repository.save()를 다시 호출하지 않는다
        consultation.updateCategory(
                request.category(),
                request.scenario(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return UpdateConsultationCategoryResponse.from(
                consultation
        );
    }

    // Situation
    @Transactional
    public UpdateConsultationSituationResponse updateSituation(
            UUID consultationId,
            String rawToken,
            UpdateConsultationSituationRequest request
    ) {
        return updateSituation(consultationId, rawToken, request, false);
    }

    @Transactional
    public UpdateConsultationSituationResponse updateSituation(
            UUID consultationId,
            String rawToken,
            UpdateConsultationSituationRequest request,
            boolean editFromSummary
    ) {
        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        ensureInProgress(consultation);

        boolean normalEdit = SITUATION_EDITABLE_STEPS.contains(
                consultation.getCurrentStep());
        boolean explicitSummaryEdit = editFromSummary
                && consultation.getCurrentStep() == ConsultationStep.SUMMARY;
        if (!normalEdit && !explicitSummaryEdit) {
            throw new InvalidConsultationStateException();
        }

        if (consultation.getCategory() == null) {
            throw new InvalidConsultationStateException();
        }

        if (accountProperties != null && request.situationText().length()
                > accountProperties.limits().maxSituationCharacters()) {
            throw new InputLimitException("situation");
        }

        consultation.updateSituation(
                request.situationText(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
        // Re-resolve from the newly submitted explicit situation.  The stored
        // scenario is a cache for downstream reads and must not prevent a
        // deliberate situation edit from moving between supported scenarios.
        ConsultationScenario resolved = ConsultationScenarioResolver.resolve(
                consultation.getCategory(), request.situationText());
        // Clear a previously resolved breadth scenario when the edited
        // situation no longer contains an explicit supported signal.  A
        // stale scenario must never route the new text through an old
        // Procedure/FAP scope.
        consultation.assignScenario(resolved, OffsetDateTime.now(ZoneOffset.UTC));

        return UpdateConsultationSituationResponse.from(
                consultation
        );
    }

    // 해당 상담이 Guest 소속인가
    private Consultation findOwnedConsultation(
            UUID consultationId,
            GuestSession guestSession
    ) {
        return consultationRepository
                // guestSessionId와 consultationId가 일치해야함
                .findByIdAndGuestSession_Id(
                        consultationId,
                        guestSession.getId()
                )
                .orElseThrow(
                        ConsultationNotFoundException::new
                );
    }

    // 상담이 현재 진행중인가
    private void ensureInProgress(
            Consultation consultation
    ) {
        if (consultation.getStatus()
                != ConsultationStatus.IN_PROGRESS) {

            throw new InvalidConsultationStateException();
        }
    }
}

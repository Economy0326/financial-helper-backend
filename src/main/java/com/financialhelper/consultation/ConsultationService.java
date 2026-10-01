package com.financialhelper.consultation;

import com.financialhelper.account.Account;
import com.financialhelper.account.AccountAuthenticationException;
import com.financialhelper.account.AccountProperties;
import com.financialhelper.account.AccountSessionService;
import com.financialhelper.account.AccountConsultationQuotaService;
import com.financialhelper.account.AccountRepository;
import com.financialhelper.procedure.CardCaseFactExtractor;
import com.financialhelper.ai.followup.FollowUpQuestionRepository;
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
    private final FollowUpQuestionRepository followUpQuestionRepository;

    @Autowired
    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            AccountSessionService accountSessionService,
            AccountProperties accountProperties,
            AccountConsultationQuotaService consultationQuotaService,
            AccountRepository accountRepository, FollowUpQuestionRepository followUpQuestionRepository
    ) {
        this.consultationRepository = consultationRepository;
        this.guestSessionService = guestSessionService;
        this.accountSessionService = accountSessionService;
        this.accountProperties = accountProperties;
        this.consultationQuotaService = consultationQuotaService;
        this.accountRepository = accountRepository;
        this.followUpQuestionRepository = followUpQuestionRepository;
    }

    /** 기존 unit test와 non-web caller를 위한 호환 생성자다. */
    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService,
            AccountSessionService accountSessionService,
            AccountProperties accountProperties
    ) {
        this(consultationRepository, guestSessionService, accountSessionService,
                accountProperties, null, null, null);
    }

    /** 기존 unit test와 non-web caller를 위한 호환 생성자다. */
    public ConsultationService(
            ConsultationRepository consultationRepository,
            GuestSessionService guestSessionService
    ) {
        this(consultationRepository, guestSessionService, null, null, null, null, null);
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

    // 기본 시작은 account의 진행 중 상담을 이어간다. 명시적인 새 시작은
    // 진행 중 상담을 종료하고 rolling window의 시작 quota를 한 번 소비한다.
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
    public ActiveConsultationStateResponse getActiveConsultation(
            String rawToken
    ) {
        Account account = accountSessionService == null ? null
                : accountSessionService.currentAccount().orElse(null);
        Consultation consultation;
        if (account != null) {
            consultation = consultationRepository
                    .findFirstByAccount_IdAndStatusInOrderByUpdatedAtDesc(
                            account.getId(), ConsultationStatus.resumableStatuses())
                    .orElse(null);
        } else {
        // guest cookie가 없는 브라우저도 단순히 진입 상태로 처리한다.
            if (rawToken == null || rawToken.isBlank()) {
                return ActiveConsultationStateResponse.none();
            }
            GuestSession guestSession;
            try {
                guestSession = guestSessionService.requireValidSession(rawToken);
            } catch (RuntimeException exception) {
                return ActiveConsultationStateResponse.none();
            }
            consultation = consultationRepository
                    .findFirstByGuestSession_IdAndStatusInOrderByUpdatedAtDesc(
                            guestSession.getId(), ConsultationStatus.resumableStatuses())
                    .orElse(null);
        }
        return consultation == null
                ? ActiveConsultationStateResponse.none()
                : ActiveConsultationStateResponse.active(consultation);
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
    public ConfirmSuggestedScenarioResponse confirmSuggestedScenario(UUID consultationId, String rawToken,
            ConfirmSuggestedScenarioRequest request) {
        Consultation consultation = findOwnedConsultation(consultationId,
                guestSessionService.requireValidSession(rawToken));
        if (consultation.getCaseInputRevision() != request.expectedCaseInputRevision()) {
            throw new InvalidConsultationStateException();
        }
        ConsultationScenario detected = ConsultationScenarioResolver.resolve(consultation.getCategory(),
                consultation.getSituationText());
        if (consultation.getScenario() == null || consultation.getScenario() == ConsultationScenario.UNKNOWN
                || detected == ConsultationScenario.UNKNOWN || detected != request.scenario()
                || consultation.getScenario() == detected) {
            throw new InvalidConsultationStateException();
        }
        if (followUpQuestionRepository != null) {
            followUpQuestionRepository.deleteAll(followUpQuestionRepository
                    .findByConsultation_IdAndCaseInputRevisionOrderBySequenceNoAsc(consultationId,
                            consultation.getCaseInputRevision()));
        }
        consultation.confirmSuggestedScenario(detected, OffsetDateTime.now(ZoneOffset.UTC));
        return new ConfirmSuggestedScenarioResponse(detected, ScenarioAlignment.SELECTED_SCENARIO_MATCH,
                consultation.getCaseInputRevision(), consultation.getCurrentStep());
    }

    @Transactional
    public UpdateConsultationSituationResponse updateSituation(
            UUID consultationId,
            String rawToken,
            UpdateConsultationSituationRequest request,
            boolean explicitEdit
    ) {
        GuestSession guestSession =
                guestSessionService
                        .requireValidSession(rawToken);

        Consultation consultation =
                findOwnedConsultation(
                        consultationId,
                        guestSession
                );

        boolean normalEdit = SITUATION_EDITABLE_STEPS.contains(
                consultation.getCurrentStep())
                && consultation.getStatus() == ConsultationStatus.IN_PROGRESS;
        boolean explicitFailedAnalysisEdit = explicitEdit
                && consultation.getCurrentStep() == ConsultationStep.ANALYSIS
                && consultation.getStatus() == ConsultationStatus.FAILED;
        if (!normalEdit && !explicitFailedAnalysisEdit) {
            throw new InvalidConsultationStateException();
        }

        if (consultation.getCategory() == null) {
            throw new InvalidConsultationStateException();
        }

        if (accountProperties != null && request.situationText().length()
                > accountProperties.limits().maxSituationCharacters()) {
            throw new InputLimitException("situation");
        }

        // "체크카드" 같은 직접적인 사용자 진술은 추론한 값이 아니다.
        // 이 값은 추론 후보나 missing 값이 아니다. 신용카드 follow-up이
        // 지원 product 선택으로 덮어쓰기 전에 종료한다.
        if (consultation.getCategory() == ConsultationCategory.CARD
                && CardCaseFactExtractor.hasExplicitUnsupportedProduct(request.situationText())) {
            throw new UnsupportedConsultationScopeException();
        }

        ConsultationScenario selectedScenario = consultation.getScenario();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (explicitFailedAnalysisEdit) {
            consultation.replaceFailedAnalysisSituation(
                    request.situationText(), now);
        } else {
            consultation.updateSituation(
                    request.situationText(), now);
        }

        // 새 Situation은 후속 경로에서 resolver가 다시 판정한다. 사용자가 선택한
        // scenario 자체를 조용히 덮어쓰지 않아 Frontend가 mismatch를 확인·변경할 수 있다.
        ConsultationScenario resolved = ConsultationScenarioResolver.resolve(
                consultation.getCategory(), request.situationText());

        return UpdateConsultationSituationResponse.from(consultation, selectedScenario, resolved);
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

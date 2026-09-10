package com.financialhelper.emergency;

import com.financialhelper.guest.GuestSession;
import com.financialhelper.guest.GuestSessionResolution;
import com.financialhelper.guest.GuestSessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class EmergencyService {

    private final EmergencyScenarioRepository scenarioRepository;
    private final EmergencySelectionRepository selectionRepository;
    private final EmergencyScenarioReader scenarioReader;
    private final GuestSessionService guestSessionService;

    public EmergencyService(
            EmergencyScenarioRepository scenarioRepository,
            EmergencySelectionRepository selectionRepository,
            EmergencyScenarioReader scenarioReader,
            GuestSessionService guestSessionService
    ) {
        this.scenarioRepository = scenarioRepository;
        this.selectionRepository = selectionRepository;
        this.scenarioReader = scenarioReader;
        this.guestSessionService = guestSessionService;
    }

    // Emergency Type 화면에 필요한 서버 기준 목록 반환
    @Transactional(readOnly = true)
    public List<EmergencyTypeOptionResponse> getTypes() {
        List<EmergencyScenario> scenarios =
                scenarioRepository
                        .findAllByOrderByDisplayOrderAsc();

        validateScenarioSet(scenarios);

        return scenarios
                .stream()
                .map(EmergencyTypeOptionResponse::from)
                .toList();
    }

    // 선택한 EmergencyType을 Guest 기준으로 1개만 저장
    @Transactional
    public EmergencySelectionResult selectType(
            String rawToken,
            SelectEmergencyTypeRequest request
    ) {
        EmergencyScenario scenario =
                scenarioRepository
                        .findByEmergencyType(request.type())
                        .orElseThrow(
                                EmergencyScenarioDataException::new
                        );

        // 깨진 Scenario를 선택 상태로 저장하지 않도록 먼저 구조 검증
        scenarioReader.read(scenario);

        GuestSessionResolution sessionResolution =
                guestSessionService
                        .resolveOrCreate(rawToken);

        GuestSession guestSession =
                sessionResolution.getGuestSession();

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        EmergencySelection selection =
                selectionRepository
                        .findByGuestSession_Id(
                                guestSession.getId()
                        )
                        .map(existing -> {
                            existing.updateSelectedType(
                                    request.type(),
                                    now
                            );
                            return existing;
                        })
                        .orElseGet(() ->
                                new EmergencySelection(
                                        guestSession,
                                        request.type(),
                                        now
                                )
                        );

        selectionRepository.save(selection);

        return new EmergencySelectionResult(
                new EmergencySelectionResponse(
                        request.type(),
                        scenario.getScenarioKey()
                ),
                sessionResolution
                        .getRawTokenToSet()
                        .orElse(null)
        );
    }

    // Guest가 선택한 현재 Scenario 반환
    // 세션/선택이 없으면 새로운 세션을 만들지 않고 not-selected 반환
    @Transactional(readOnly = true)
    public EmergencyScenarioStateResponse getScenario(
            String rawToken
    ) {
        Optional<GuestSession> guestSession =
                guestSessionService
                        .findValidSession(rawToken);

        if (guestSession.isEmpty()) {
            return EmergencyScenarioStateResponse
                    .notSelected();
        }

        Optional<EmergencySelection> selection =
                selectionRepository
                        .findByGuestSession_Id(
                                guestSession.get().getId()
                        );

        if (selection.isEmpty()) {
            return EmergencyScenarioStateResponse
                    .notSelected();
        }

        EmergencyScenario scenario =
                scenarioRepository
                        .findByEmergencyType(
                                selection.get()
                                        .getSelectedType()
                        )
                        .orElseThrow(
                                EmergencyScenarioDataException::new
                        );

        EmergencyScenarioDocument document =
                scenarioReader.read(scenario);

        return EmergencyScenarioStateResponse.ready(
                scenario,
                document
        );
    }

    private void validateScenarioSet(
            List<EmergencyScenario> scenarios
    ) {
        Set<EmergencyType> expectedTypes =
                EnumSet.allOf(EmergencyType.class);

        Set<EmergencyType> actualTypes =
                EnumSet.noneOf(EmergencyType.class);

        for (EmergencyScenario scenario : scenarios) {
            actualTypes.add(scenario.getEmergencyType());
            scenarioReader.read(scenario);
        }

        if (!actualTypes.equals(expectedTypes)) {
            throw new EmergencyScenarioDataException();
        }
    }
}
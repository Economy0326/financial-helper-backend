package com.financialhelper.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 사람이 명시적으로 승인한 CARD corpus 활성화의 선택 실행 runner다. */
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "app.card-corpus",
        name = "run-on-startup",
        havingValue = "true"
)
public class CardOfficialCorpusActivationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            CardOfficialCorpusActivationRunner.class);

    private final CardOfficialCorpusActivationService activationService;

    public CardOfficialCorpusActivationRunner(
            CardOfficialCorpusActivationService activationService
    ) {
        this.activationService = activationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        CardOfficialCorpusActivationService.ActivationResult result =
                activationService.activate();
        log.info(
                "CARD official corpus activated sourceKeys={}, chunks={}, generationId={}",
                result.sourceKeys(), result.chunkCounts(), result.generationId()
        );
    }
}

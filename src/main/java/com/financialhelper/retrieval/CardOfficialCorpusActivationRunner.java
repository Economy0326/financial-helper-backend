package com.financialhelper.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Opt-in runner for the explicit, human-approved CARD corpus activation. */
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

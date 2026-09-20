package com.financialhelper.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 동결 및 검토된 breadth MVP corpus의 선택 실행 runner다. */
@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "app.breadth-corpus",
        name = "run-on-startup",
        havingValue = "true"
)
public class BreadthOfficialCorpusActivationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(
            BreadthOfficialCorpusActivationRunner.class);

    private final BreadthOfficialCorpusActivationService activationService;

    public BreadthOfficialCorpusActivationRunner(
            BreadthOfficialCorpusActivationService activationService
    ) {
        this.activationService = activationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        BreadthOfficialCorpusActivationService.ActivationResult result =
                activationService.activate();
        log.info(
                "Breadth official corpus activated sourceKeys={}, chunks={}, generationId={}",
                result.sourceKeys(), result.chunkCounts(), result.generationId());
    }
}

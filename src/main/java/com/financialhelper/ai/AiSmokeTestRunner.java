package com.financialhelper.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(
        prefix = "app.ai",
        name = {
                "enabled",
                "smoke-test-enabled"
        },
        havingValue = "true"
)
public class AiSmokeTestRunner
        implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(
                    AiSmokeTestRunner.class
            );

    private final OpenAiTextClient openAiTextClient;

    public AiSmokeTestRunner(
            OpenAiTextClient openAiTextClient
    ) {
        this.openAiTextClient =
                openAiTextClient;
    }

    @Override
    public void run(
            ApplicationArguments args
    ) {

        openAiTextClient.generateText(
                """
                This is a connectivity test.

                Return only:
                AI_CONNECTION_OK
                """
        );

        log.info(
                "OpenAI API smoke test succeeded."
        );
    }
}
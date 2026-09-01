package com.financialhelper.ai.probe;

import com.financialhelper.ai.OpenAiStructuredClient;

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

    private final OpenAiStructuredClient openAiStructuredClient;

    public AiSmokeTestRunner(
            OpenAiStructuredClient openAiStructuredClient
    ) {
        this.openAiStructuredClient =
                openAiStructuredClient;
    }

    @Override
    public void run(
            ApplicationArguments args
    ) {

        openAiStructuredClient.generateStructured(
                """
                You are performing a backend connectivity test.

                Follow the requested output schema exactly.

                The status field must be exactly:
                AI_CONNECTION_OK

                Do not include user data or financial advice.
                """,
                """
                Confirm that structured AI output is working.

                Return a short Korean confirmation message.
                """,
                AiStructuredProbeResponse.class
        );

        log.info(
                "OpenAI structured output smoke test succeeded."
        );
    }
}
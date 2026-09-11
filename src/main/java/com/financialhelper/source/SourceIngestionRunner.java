package com.financialhelper.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;

import org.springframework.context.annotation.Profile;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("!test")
@ConditionalOnProperty(
        prefix = "app.source-ingestion",
        name = "run-on-startup",
        havingValue = "true"
)
public class SourceIngestionRunner
        implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(
                    SourceIngestionRunner.class
            );

    private final SourceIngestionService
            sourceIngestionService;

    public SourceIngestionRunner(
            SourceIngestionService sourceIngestionService
    ) {
        this.sourceIngestionService =
                sourceIngestionService;
    }

    @Override
    public void run(
            ApplicationArguments args
    ) {
        List<SourceIngestionResult> results =
                sourceIngestionService
                        .ingestAll();

        for (
                SourceIngestionResult result
                : results
        ) {
            log.info(
                    "Official source ingestion sourceKey={}, outcome={}, version={}, failureCode={}",
                    result.sourceKey(),
                    result.outcome(),
                    result.documentVersion(),
                    result.failureCode()
            );
        }
    }
}
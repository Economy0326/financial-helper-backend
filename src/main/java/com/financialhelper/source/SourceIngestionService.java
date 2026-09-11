package com.financialhelper.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 느린 외부 I/O는 transaction 밖에서 처리하고,
// 서로 일관성이 필요한 DB 변경만 짧은 transaction 안에서 처리한다.
@Service
public class SourceIngestionService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    SourceIngestionService.class
            );

    private final SourceRegistryRepository
            sourceRegistryRepository;

    private final OfficialSourceDomainRepository
            officialSourceDomainRepository;

    private final SourceContentFetcher
            sourceContentFetcher;

    private final HtmlSourceDocumentParser
            htmlSourceDocumentParser;

    private final SourceDocumentWriter
            sourceDocumentWriter;

    public SourceIngestionService(
            SourceRegistryRepository sourceRegistryRepository,
            OfficialSourceDomainRepository officialSourceDomainRepository,
            SourceContentFetcher sourceContentFetcher,
            HtmlSourceDocumentParser htmlSourceDocumentParser,
            SourceDocumentWriter sourceDocumentWriter
    ) {
        this.sourceRegistryRepository =
                sourceRegistryRepository;

        this.officialSourceDomainRepository =
                officialSourceDomainRepository;

        this.sourceContentFetcher =
                sourceContentFetcher;

        this.htmlSourceDocumentParser =
                htmlSourceDocumentParser;

        this.sourceDocumentWriter =
                sourceDocumentWriter;
    }

    public List<SourceIngestionResult> ingestAll() {

        List<SourceRegistry> sources =
                sourceRegistryRepository
                        .findAllByEnabledTrueOrderBySourceKeyAsc();

        List<SourceIngestionResult> results =
                new ArrayList<>();

        for (
                SourceRegistry source
                : sources
        ) {
            try {

                results.add(
                        ingest(source)
                );

            } catch (
                    SourceIngestionException exception
            ) {

                log.warn(
                        "Official source ingestion failed sourceKey={}, code={}, message={}",
                        source.getSourceKey(),
                        exception.getCode(),
                        exception.getMessage()
                );

                results.add(
                        SourceIngestionResult.failed(
                                source.getSourceKey(),
                                exception.getCode()
                        )
                );
            }
        }

        return List.copyOf(
                results
        );
    }

    public SourceIngestionResult ingestOne(
            String sourceKey
    ) {

        SourceRegistry source =
                sourceRegistryRepository
                        .findBySourceKeyAndEnabledTrue(
                                sourceKey
                        )
                        .orElseThrow(() ->
                                new SourceIngestionException(
                                        "SOURCE_NOT_FOUND",
                                        "Enabled source registry was not found"
                                )
                        );

        return ingest(
                source
        );
    }

    private SourceIngestionResult ingest(
            SourceRegistry source
    ) {

        if (
                !officialSourceDomainRepository
                        .existsByDomainAndEnabledTrue(
                                source.getOfficialDomain()
                        )
        ) {
            throw new SourceIngestionException(
                    "SOURCE_DOMAIN_DISABLED",
                    "Official source domain is not enabled"
            );
        }

        // HTTP 요청 전에 현재 SourceRegistry 정의를 Snapshot으로 복사
        SourceIngestionData.Snapshot snapshot =
                SourceIngestionData
                        .Snapshot
                        .from(
                                source
                        );

        // 외부 HTTP 호출은 DB transaction 밖에서 진행
        SourceIngestionData.Fetched fetched =
                sourceContentFetcher.fetch(
                        snapshot
                );

        // 가져온 HTML을 실제 근거로 사용할 수 있는 normalized content로 변환
        SourceIngestionData.Parsed parsed =
                htmlSourceDocumentParser.parse(
                        snapshot,
                        fetched
                );

        // 새로운 write transaction 안에서 SourceRegistry를 다시 확인하고 저장
        return sourceDocumentWriter
                .saveIfCurrent(
                        snapshot,
                        parsed
                );
    }

}
package com.financialhelper.source;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class SourceDocumentWriter {

    private final SourceRegistryRepository
            sourceRegistryRepository;

    private final OfficialSourceDomainRepository
            officialSourceDomainRepository;

    private final SourceDocumentRepository
            sourceDocumentRepository;

    public SourceDocumentWriter(
            SourceRegistryRepository sourceRegistryRepository,
            OfficialSourceDomainRepository officialSourceDomainRepository,
            SourceDocumentRepository sourceDocumentRepository
    ) {
        this.sourceRegistryRepository =
                sourceRegistryRepository;

        this.officialSourceDomainRepository =
                officialSourceDomainRepository;

        this.sourceDocumentRepository =
                sourceDocumentRepository;
    }

    @Transactional
    public SourceIngestionResult saveIfCurrent(
            SourceIngestionData.Snapshot snapshot,
            SourceIngestionData.Parsed parsed
    ) {

        SourceRegistry currentSource =
                sourceRegistryRepository

                        // Repository에서 Lock으로 조회
                        // 다른 ingestion과 충돌하지 않도록 함
                        .findForUpdateById(
                                snapshot.sourceRegistryId()
                        )

                        .orElseThrow(() ->
                                new SourceIngestionException(
                                        "SOURCE_NOT_FOUND",
                                        "Source registry disappeared during ingestion"
                                )
                        );

        // HTTP 요청을 수행하는 동안 source 설정이 변경되지 않았는지 확인
        validateSnapshotStillCurrent(
                snapshot,
                currentSource
        );

        if (
                !officialSourceDomainRepository
                        .existsByDomainAndEnabledTrue(
                                currentSource
                                        .getOfficialDomain()
                        )
        ) {
            throw new SourceIngestionException(
                    "SOURCE_DOMAIN_DISABLED",
                    "Official source domain was disabled during ingestion"
            );
        }

        SourceDocument activeDocument =
                sourceDocumentRepository
                        .findBySourceRegistry_IdAndStatus(
                                currentSource.getId(),
                                SourceDocumentStatus.ACTIVE
                        )
                        .orElse(null);

        if (
                activeDocument != null

                        && activeDocument
                        .getContentSha256()

                        // Hash 값이 같으면 => 내용 변화 없음
                        .equals(
                                parsed.contentSha256()
                        )
        ) {

            // 실제 내용은 동일하므로 새 version을 만들지 않고
            // 마지막 확인 시간만 업데이트
            activeDocument.markChecked(
                    parsed.retrievedAt()
            );

            return new SourceIngestionResult(
                    currentSource.getSourceKey(),
                    SourceIngestionResult
                            .Outcome
                            .UNCHANGED,
                    activeDocument
                            .getDocumentVersion(),
                    activeDocument
                            .getContentSha256(),
                    null
            );
        }

        int nextVersion = 1;

        // Hash 값이 다르면 => 내용이 바뀜
        if (activeDocument != null) {

            nextVersion =
                    activeDocument
                            .getDocumentVersion()
                            + 1;

            // 기존 ACTIVE 버전을 SUPERSEDED로 변경
            activeDocument.supersede(
                    parsed.retrievedAt()
            );

            // SourceRegistry당 ACTIVE SourceDocument는 최대 1개만 허용하므로
            // 새 ACTIVE 버전을 INSERT하기 전에 기존 상태 변경을 DB에 먼저 반영
            sourceDocumentRepository.flush();
        }

        SourceDocument newDocument =
                new SourceDocument(
                        currentSource,
                        nextVersion,
                        parsed
                );

        sourceDocumentRepository.save(
                newDocument
        );

        SourceIngestionResult.Outcome outcome =
                activeDocument == null

                        ? SourceIngestionResult
                        .Outcome
                        .CREATED

                        : SourceIngestionResult
                        .Outcome
                        .UPDATED;

        return new SourceIngestionResult(
                currentSource.getSourceKey(),
                outcome,
                nextVersion,
                parsed.contentSha256(),
                null
        );
    }

    private void validateSnapshotStillCurrent(
            SourceIngestionData.Snapshot snapshot,
            SourceRegistry currentSource
    ) {

        boolean sameDefinition =

                currentSource.isEnabled()

                        && Objects.equals(
                        snapshot.sourceKey(),
                        currentSource.getSourceKey()
                )

                        && Objects.equals(
                        snapshot.organizationName(),
                        currentSource
                                .getOrganizationName()
                )

                        && Objects.equals(
                        snapshot.officialDomain(),
                        currentSource
                                .getOfficialDomain()
                )

                        && Objects.equals(
                        snapshot.canonicalUrl(),
                        currentSource
                                .getCanonicalUrl()
                )

                        && snapshot.acquisitionType()
                        == currentSource
                        .getAcquisitionType()

                        // content selector 역시 source 정의의 일부이므로 fetch 도중 변경되었다면 저장하지 않음
                        && Objects.equals(
                        snapshot.contentSelector(),
                        currentSource
                                .getContentSelector()
                );

        if (!sameDefinition) {
            throw new SourceIngestionException(
                    "SOURCE_DEFINITION_CHANGED",
                    "Source definition changed while the official document was being fetched"
            );
        }
    }

}
package com.financialhelper.source;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceChunkRepository
        extends JpaRepository<SourceChunk, UUID> {

    Optional<SourceChunk>
    findBySourceDocument_IdAndChunkConfiguration_ConfigVersionAndSequence(
            UUID sourceDocumentId,
            String configVersion,
            int sequence
    );

    Optional<SourceChunk>
    findFirstByChunkConfiguration_ConfigVersion(
            String configVersion
    );

    List<SourceChunk>
    findAllBySourceDocument_IdOrderBySequenceAsc(
            UUID sourceDocumentId
    );
}

package com.financialhelper.source;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository
        extends JpaRepository<SourceDocument, UUID> {

    // Chunk creation is serialized per document version so two concurrent
    // writers cannot create conflicting definitions for the same sequence or
    // chunk configuration.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select document
            from SourceDocument document
            where document.id = :documentId
            """
    )
    Optional<SourceDocument> findForUpdateById(
            @Param("documentId")
            UUID documentId
    );

    // 해당 SourceRegistry에 속한 문서 중 해당 상태에 맞는 SourceDocument 찾기
    Optional<SourceDocument>
    findBySourceRegistry_IdAndStatus(
            UUID sourceRegistryId,
            SourceDocumentStatus status
    );

    // 해당 SourceRegistry에 속한 모든 SourceDocument 버전을 documentVersion 오름차순으로 찾기
    List<SourceDocument>
    findAllBySourceRegistry_IdOrderByDocumentVersionAsc(
            UUID sourceRegistryId
    );
}

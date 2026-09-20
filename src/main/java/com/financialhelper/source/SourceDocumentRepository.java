package com.financialhelper.source;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceDocumentRepository
        extends JpaRepository<SourceDocument, UUID> {

    // 두 concurrent writer가 충돌하지 않도록 document version별 chunk 생성을 직렬화한다.
    // writer가 같은 sequence에 대해 충돌하는 정의를 만들 수 없도록 한다.
    // 같은 chunk configuration에도 적용된다.
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
    @EntityGraph(attributePaths = "sourceRegistry")
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

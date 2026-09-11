package com.financialhelper.source;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceRegistryRepository
        extends JpaRepository<SourceRegistry, UUID> {

    // 사용 가능한 수집 대상들을 sourceKey 오름차순으로 전부 찾기
    List<SourceRegistry>
    findAllByEnabledTrueOrderBySourceKeyAsc();

    // 해당 sourceKey에 맞는 사용 가능한 SourceRegistry 하나 찾기
    Optional<SourceRegistry>
    findBySourceKeyAndEnabledTrue(
            String sourceKey
    );

    // Lock을 HTTP 수집 전체에 걸지 않고,
    // 실제 저장 직전에만 상태 재확인
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select source
            from SourceRegistry source
            where source.id = :sourceId
            """
    )
    // 해당 Id의 데이터를 수정 판단용 lock을 걸고 찾기
    Optional<SourceRegistry> findForUpdateById(
            @Param("sourceId")
            UUID sourceId
    );
}
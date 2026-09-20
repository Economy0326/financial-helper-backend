package com.financialhelper.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceChunkConfigurationRepository
        extends JpaRepository<SourceChunkConfiguration, String> {

    /**
     * config version 생성은 먼저 쓴 요청이 이기도록 database가 직렬화한다.
     * caller는 사용 전에 생성된 JSON tree를 비교한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    INSERT INTO source_chunk_configuration (
                        config_version,
                        config_json,
                        config_sha256,
                        created_at
                    )
                    VALUES (
                        :configVersion,
                        :configJson,
                        :configSha256,
                        CURRENT_TIMESTAMP
                    )
                    ON CONFLICT (config_version) DO NOTHING
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("configVersion")
            String configVersion,
            @Param("configJson")
            String configJson,
            @Param("configSha256")
            String configSha256
    );
}

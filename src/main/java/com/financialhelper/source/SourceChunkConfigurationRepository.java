package com.financialhelper.source;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceChunkConfigurationRepository
        extends JpaRepository<SourceChunkConfiguration, String> {

    /**
     * Let the database serialize first-writer-wins creation of a config
     * version.  The caller compares the resulting JSON tree before use.
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

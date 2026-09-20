package com.financialhelper.retrieval;

import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** PostgreSQL keyword 기반을 위한 작은 persistence-only 경계다. */
@Service
public class SourceKeywordSearchService {

    private final SourceChunkRepository sourceChunkRepository;

    public SourceKeywordSearchService(SourceChunkRepository sourceChunkRepository) {
        this.sourceChunkRepository = sourceChunkRepository;
    }

    @Transactional(readOnly = true)
    public List<SourceChunk> searchApproved(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return sourceChunkRepository.searchApprovedKeyword(query.trim(), limit);
    }
}

package com.financialhelper.retrieval;

import com.financialhelper.source.SourceChunk;
import com.financialhelper.source.SourceChunkRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Conservative fallback: every retained term must match; a prefix only handles particles. */
    @Transactional(readOnly = true)
    public List<SourceChunk> searchDocumentClaims(String query, int limit) {
        List<SourceChunk> exact = searchApproved(query, limit);
        if (!exact.isEmpty()) return exact;
        String terms = prefixQuery(query);
        return terms == null ? List.of() : sourceChunkRepository.searchApprovedKeywordPrefixes(terms, limit);
    }

    @Transactional(readOnly = true)
    public List<SourceChunk> searchDocumentClaims(String query, int limit, UUID generation) {
        var results = new java.util.LinkedHashMap<UUID, SourceChunk>();
        searchDocumentClaims(query, limit).forEach(chunk -> results.put(chunk.getId(), chunk));
        sourceChunkRepository.searchDocumentContextKeyword(query.trim(), limit)
                .forEach(chunk -> results.putIfAbsent(chunk.getId(), chunk));
        return results.values().stream().limit(limit).toList();
    }

    static String prefixQuery(String question) {
        Set<String> questionWords = Set.of("얼마", "얼마인가요", "얼마입니까", "얼마예요", "어떻게");
        var terms = Arrays.stream(question.split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isBlank() && !questionWords.contains(word))
                .map(word -> word.replaceFirst("(에서도|에게도|으로는|에는|에도|에서|으로|에게|이나|이나요|은|는|이|가|을|를|의|에|도)$", ""))
                .filter(word -> word.codePointCount(0, word.length()) >= 2)
                .distinct().toList();
        if (terms.size() < 2) return null;
        return terms.stream().map(word -> word + ":*").collect(Collectors.joining(" & "));
    }
}

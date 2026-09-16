package com.financialhelper.source;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceChunkingServiceTest {

    @Test
    void structureAndUtf16OffsetsAreRetained() {
        SourceChunkPersistenceService persistence = mock(
                SourceChunkPersistenceService.class
        );
        SourceDocument document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNormalizedContent()).thenReturn(
                "제1장 총칙\n제1조 목적 😊\n분실과 도난의 조건"
        );

        List<SourceChunkData.Definition> definitions = new ArrayList<>();
        doAnswer(invocation -> {
            SourceChunkData.Definition definition = invocation.getArgument(1);
            definitions.add(definition);
            return mock(SourceChunk.class);
        }).when(persistence).saveIfAbsent(any(), any());

        SourceChunkingService service = new SourceChunkingService(
                persistence,
                new CountingTokenizer(),
                new SourceChunkingProperties(
                        500,
                        600,
                        0,
                        "structure-first-v1",
                        "test-tokenizer",
                        "test-revision"
                ),
                JsonMapper.builder().build()
        );

        service.chunk(document);

        assertThat(definitions).hasSize(1);
        SourceChunkData.Definition definition = definitions.getFirst();
        assertThat(definition.parentSection()).isEqualTo("제1장 총칙");
        assertThat(definition.articleReference()).isEqualTo("제1조 목적 😊");
        assertThat(definition.body()).contains("😊");
        assertThat(definition.sourceStartOffset()).isZero();
        assertThat(definition.sourceEndOffset())
                .isEqualTo(document.getNormalizedContent().length());
        assertThat(definition.locator()).contains("normalized-content:utf16:");
    }

    @Test
    void longStructuralUnitSplitsByTokenizerCountInsteadOfCharacters() {
        SourceChunkPersistenceService persistence = mock(
                SourceChunkPersistenceService.class
        );
        SourceDocument document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNormalizedContent()).thenReturn(
                "제1조 목적\n하나 둘 셋\n넷 다섯 여섯\n일곱 여덟 아홉"
        );
        List<SourceChunkData.Definition> definitions = new ArrayList<>();
        doAnswer(invocation -> {
            SourceChunkData.Definition definition = invocation.getArgument(1);
            definitions.add(definition);
            return mock(SourceChunk.class);
        }).when(persistence).saveIfAbsent(any(), any());

        SourceChunkingService service = new SourceChunkingService(
                persistence,
                new CountingTokenizer(),
                new SourceChunkingProperties(
                        5,
                        6,
                        0,
                        "structure-first-v1",
                        "test-tokenizer",
                        "test-revision"
                ),
                JsonMapper.builder().build()
        );

        service.chunk(document);

        assertThat(definitions).hasSize(2);
        assertThat(definitions.get(0).body()).contains("하나 둘 셋");
        assertThat(definitions.get(1).body()).contains("넷 다섯 여섯");
        assertThat(definitions.get(0).sourceEndOffset())
                .isLessThanOrEqualTo(definitions.get(1).sourceStartOffset());
    }

    private static final class CountingTokenizer implements SourceChunkTokenizer {
        @Override
        public int countDocumentTokens(String text) {
            return text.trim().split("\\s+").length;
        }

        @Override
        public String identifier() {
            return "test-tokenizer";
        }

        @Override
        public String revision() {
            return "test-revision";
        }
    }
}

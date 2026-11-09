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
    void numberedFaqQuestionsKeepTheirOwnNotesAndExcludedQuestionSeparate() {
        SourceChunkPersistenceService persistence = mock(SourceChunkPersistenceService.class);
        SourceDocument document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNormalizedContent()).thenReturn("""
                4. 외화예금도 보호되나요?
                이번 검토에서 제외할 문답입니다.
                6. 원금과 이자는 어떻게 계산하나요?
                원금과 소정의 이자를 합산합니다.
                * 이자 계산 기준은 기관마다 다릅니다.
                10. 별도 보호한도는 어떻게 적용되나요?
                보호상품으로 운용되는 금액에 한합니다.
                * 사고보험금은 지급사유 발생 후 미지급된 금액입니다.
                """);
        List<SourceChunkData.Definition> definitions = new ArrayList<>();
        doAnswer(call -> {
            definitions.add(call.getArgument(1));
            return mock(SourceChunk.class);
        }).when(persistence).saveIfAbsent(any(), any());
        var service = new SourceChunkingService(persistence, new CountingTokenizer(),
                new SourceChunkingProperties(500, 600, 0, "structure-first-v1",
                        "test-tokenizer", "test-revision"), JsonMapper.builder().build());

        service.chunk(document);

        assertThat(definitions).hasSize(3);
        assertThat(definitions.get(0).body()).contains("4. 외화예금").doesNotContain("6. 원금");
        assertThat(definitions.get(1).body()).contains("6. 원금", "이자 계산 기준")
                .doesNotContain("4. 외화예금", "10. 별도");
        assertThat(definitions.get(2).body()).contains("10. 별도", "보호상품으로 운용", "사고보험금은")
                .doesNotContain("6. 원금");
        assertThat(definitions).allSatisfy(definition ->
                assertThat(definition.body()).isEqualTo(document.getNormalizedContent().substring(
                        definition.sourceStartOffset(), definition.sourceEndOffset())));
    }

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

    @Test
    void oversizedSingleHtmlLineSplitsAtSafeBoundaries() {
        SourceChunkPersistenceService persistence = mock(
                SourceChunkPersistenceService.class
        );
        SourceDocument document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        String content = String.join(" ", "공식 안내입니다.".repeat(20));
        when(document.getNormalizedContent()).thenReturn(content);
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

        assertThat(definitions).hasSizeGreaterThan(1);
        assertThat(definitions).allSatisfy(definition -> {
            assertThat(definition.body()).doesNotEndWith(" ");
            assertThat(definition.body()).isNotBlank();
        });
        assertThat(definitions.getLast().sourceEndOffset()).isEqualTo(content.length());
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

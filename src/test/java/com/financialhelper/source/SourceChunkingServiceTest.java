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
    void wrappedPdfQuestionsKeepNotesExamplesAndPageLocatorsTogether() {
        String content = """
                [PAGE 1]
                2. 어떤 상품이 보호되나요?
                보호상품으로 운용되는 경우에 한합니다.
                6. 이자 산정은 어떻게 하나요?
                * 기관별 산정 기준을 확인합니다.
                7. 보호사고가 발생하면 어떻게 되나요?
                [PAGE 2]
                예금보험공사가 안내합니다.
                8. 여러 계좌를 보유한 경우,
                한도는 어떻게 적용되나요?
                계좌별 금액을 합산합니다.
                9. 서로 다른 기관에 보유한 경우,
                한도는 어떻게 적용되나요?
                기관별로 확인합니다.
                10. 별도 한도 대상은
                어떻게 적용되나요?
                별도 조건을 확인합니다.
                * 사고보험금 각주를 확인합니다.
                예시: 조건에 따라 다릅니다.
                """;
        List<SourceChunkData.Definition> definitions = definitionsFor(content, 500, 600);

        assertThat(definitions).hasSize(7);
        for (int question : List.of(2, 6, 7, 8, 9, 10)) {
            String marker = question + ". ";
            assertThat(definitions.stream().filter(d -> d.body().contains(marker)).count())
                    .isEqualTo(1);
        }
        assertThat(definitions.stream().filter(d -> d.body().contains("8. 여러")).findFirst().orElseThrow()
                .body()).doesNotContain("7. 보호", "9. 서로");
        assertThat(definitions.stream().filter(d -> d.body().contains("10. 별도")).findFirst().orElseThrow()
                .body()).contains("별도 조건", "사고보험금 각주", "예시:")
                .doesNotContain("9. 서로");
        assertThat(definitions.stream().filter(d -> d.body().contains("7. 보호")).findFirst().orElseThrow()
                .pageReference()).isEqualTo("p.1-2");
        assertThat(definitions.stream().filter(d -> d.body().contains("10. 별도")).findFirst().orElseThrow()
                .parentSection()).contains("10. 별도 한도 대상은 어떻게 적용되나요?");
        assertThat(definitions).allSatisfy(d -> {
            assertThat(d.body()).isEqualTo(content.substring(d.sourceStartOffset(), d.sourceEndOffset()));
            assertThat(d.locator()).isEqualTo("normalized-content:utf16:" + d.sourceStartOffset()
                    + "-" + d.sourceEndOffset());
        });
    }

    @Test
    void wrappedQuestionAcrossPageMarkerAndOrdinaryNumberedListStayDistinct() {
        String content = """
                1. 신청 절차
                2. 준비 서류
                본문 설명입니다.
                [PAGE 1]
                8. 여러 계좌를 보유한 경우,
                [PAGE 2]
                한도는 어떻게 적용되나요?
                조건과 예외를 확인합니다.
                9. 다음 질문은 무엇인가요?
                다음 답변입니다.
                """;
        List<SourceChunkData.Definition> definitions = definitionsFor(content, 500, 600);

        assertThat(definitions).hasSize(3);
        assertThat(definitions.get(0).body()).contains("1. 신청 절차", "2. 준비 서류")
                .doesNotContain("8. 여러");
        assertThat(definitions.get(1).body()).contains("8. 여러", "[PAGE 2]", "조건과 예외")
                .doesNotContain("9. 다음");
        assertThat(definitions.get(1).pageReference()).isEqualTo("p.1-2");
        assertThat(definitions.get(2).body()).contains("9. 다음 질문");
    }

    private List<SourceChunkData.Definition> definitionsFor(String content, int target, int max) {
        SourceChunkPersistenceService persistence = mock(SourceChunkPersistenceService.class);
        SourceDocument document = mock(SourceDocument.class);
        when(document.getId()).thenReturn(UUID.randomUUID());
        when(document.getNormalizedContent()).thenReturn(content);
        List<SourceChunkData.Definition> definitions = new ArrayList<>();
        doAnswer(call -> {
            definitions.add(call.getArgument(1));
            return mock(SourceChunk.class);
        }).when(persistence).saveIfAbsent(any(), any());
        new SourceChunkingService(persistence, new CountingTokenizer(),
                new SourceChunkingProperties(target, max, 0, "structure-first-v2",
                        "test-tokenizer", "test-revision"), JsonMapper.builder().build()).chunk(document);
        return definitions;
    }

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

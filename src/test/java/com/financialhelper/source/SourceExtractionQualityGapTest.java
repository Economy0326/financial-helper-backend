package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.financialhelper.source.ExtractionGoldenSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reproductions of quality gaps. Do not disable or invert these assertions to claim quality PASS. */
class SourceExtractionQualityGapTest {
    @Test
    void oversizedFirstLineMustNotDropFollowingException() {
        String text = "합성 접수 안내 문장입니다. ".repeat(20)
                + "\n다만 대리 접수에는 별도 확인이 필요합니다.";
        assertThat(bodies(chunks(text, 5, 8)))
                .contains("다만 대리 접수에는 별도 확인이 필요합니다.");
    }

    @Test
    void oversizedFirstLinePreservesAllFollowingLinesAndUtf16OffsetsWithoutDuplication() {
        String text = "합성 안내 😊 문장입니다. ".repeat(20)
                + "\n조건: 대출 사고에만 적용됩니다.\n\n다만 대리 접수에는 추가 확인이 필요합니다."
                + "\n마지막 결과 통지 문장입니다.";
        var result = chunks(text, 5, 8);
        assertThat(bodies(result).replaceAll("\\s+", ""))
                .isEqualTo(text.replaceAll("\\s+", ""));
        assertThat(result.getLast().sourceEndOffset()).isEqualTo(text.length());
        assertThat(result).allSatisfy(chunk -> {
            assertThat(chunk.body().strip().split("\\s+").length).isLessThanOrEqualTo(8);
            assertThat(Character.isLowSurrogate(chunk.body().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.body().charAt(chunk.body().length() - 1))).isFalse();
        });
    }

    @Test
    void hwpSectionTenMustFollowSectionNineInsteadOfSectionOne() throws Exception {
        List<List<String>> sections = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            sections.add(List.of("[ORDER-" + i + "] 합성 문단은 문서 순서 보존을 검증합니다."));
        }
        String text = parse(new HwpSourceDocumentParser(), SourceAcquisitionType.HWP,
                "application/x-hwp", hwp(sections)).normalizedContent();
        String chunked = bodies(chunks(text, 8, 16));
        assertThat(chunked.replaceAll("\\s+", "")).isEqualTo(text.replaceAll("\\s+", ""));
        for (int i = 0; i < 10; i++) {
            assertThat(text.indexOf("[ORDER-" + i + "]"))
                    .as("Section%d must precede Section%d", i, i + 1)
                    .isLessThan(text.indexOf("[ORDER-" + (i + 1) + "]"));
            assertThat(chunked.indexOf("[ORDER-" + i + "]"))
                    .isLessThan(chunked.indexOf("[ORDER-" + (i + 1) + "]"));
        }
    }

    static Stream<List<String>> invalidSectionNames() {
        return Stream.of(
                List.of("Section0", "SectionX"),
                List.of("Section0", "Section-1"),
                List.of("Section0", "Section2147483648"),
                List.of("Section0", "Section2"),
                List.of("Section1", "Section2"),
                List.of("Section0", "Section00"),
                List.of("Section0", "Section01"));
    }

    @ParameterizedTest
    @MethodSource("invalidSectionNames")
    void invalidSectionSequencesFailClosed(List<String> names) throws Exception {
        byte[] raw = hwp(List.of(List.of("합성 문단 첫 번째입니다. ".repeat(10)),
                List.of("합성 문단 두 번째입니다. ".repeat(10))), names);
        assertThatThrownBy(() -> parse(new HwpSourceDocumentParser(), SourceAcquisitionType.HWP,
                "application/x-hwp", raw))
                .isInstanceOf(SourceIngestionException.class)
                .hasMessage("Could not parse official HWP source");
    }
}

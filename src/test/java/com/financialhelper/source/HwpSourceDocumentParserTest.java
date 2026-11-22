package com.financialhelper.source;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.financialhelper.source.ExtractionGoldenSupport.hwp;
import static com.financialhelper.source.ExtractionGoldenSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HwpSourceDocumentParserTest {
    private static final List<String> FIRST = List.of("제1조 합성 문단 순서",
            "가상의 별빛 정원에서 파란 표식을 먼저 찾고 초록 표식과 구분하는 합성 예시입니다.");
    private static final List<String> SECOND = List.of("제2조 합성 관찰 기록",
            "다음 날 관찰할 때에는 표식의 색과 위치를 차례로 기록합니다. 이 문단은 파서 테스트를 위해 작성했습니다.");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSyntheticParagraphOrderAndOriginalBytes(boolean compressed) throws Exception {
        byte[] bytes = hwp(List.of(SECOND, FIRST), List.of("Section1", "Section0"), compressed);
        byte[] original = bytes.clone();
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes));
             var header = fs.getRoot().createDocumentInputStream("FileHeader")) {
            assertThat(header.readAllBytes()[36] & 1).isEqualTo(compressed ? 1 : 0);
        }
        var parsed = parse(new HwpSourceDocumentParser(), SourceAcquisitionType.HWP, "application/x-hwp", bytes);
        assertThat(parsed.originalContent()).isEqualTo(original);
        assertThat(parsed.normalizedContent()).isEqualTo(String.join("\n", FIRST) + "\n" + String.join("\n", SECOND));
    }

    @Test
    void rejectsTruncatedCompressedSectionEvenAfterReadingAValidSection() throws Exception {
        byte[] bytes = hwp(List.of(FIRST, SECOND), List.of("Section0", "Section1"), true);
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes)); var output = new ByteArrayOutputStream()) {
            var body = (DirectoryNode) fs.getRoot().getEntry("BodyText");
            byte[] section;
            try (var input = body.createDocumentInputStream("Section1")) {
                section = input.readAllBytes();
            }
            body.createOrUpdateDocument("Section1", new ByteArrayInputStream(Arrays.copyOf(section, section.length / 2)));
            fs.writeFilesystem(output);
            assertThatThrownBy(() -> parse(new HwpSourceDocumentParser(), SourceAcquisitionType.HWP,
                    "application/x-hwp", output.toByteArray()))
                    .isInstanceOf(SourceIngestionException.class)
                    .extracting("code").isEqualTo("SOURCE_HWP_PARSE_FAILED");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FH_PRIVATE_HWP_PATH", matches = ".+")
    void privateSnapshotMatchesPinnedDigestAndRemainsParseable() throws Exception {
        Path path = Path.of(System.getenv("FH_PRIVATE_HWP_PATH"));
        assumeTrue(Files.isRegularFile(path), "비공개 HWP 파일이 지정한 경로에 없습니다.");
        byte[] original = Files.readAllBytes(path);
        assertThat(SourceHashing.sha256(original))
                .isEqualTo("bfffe9832f9d3ba7db80e613a120c332ad4e909d2eb8629917dc0cb695011dd6");
        var parsed = parse(new HwpSourceDocumentParser(), SourceAcquisitionType.HWP, "application/x-hwp", original);
        assertThat(parsed.normalizedContent().isBlank()).isFalse();
    }
}

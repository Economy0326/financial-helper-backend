package com.financialhelper.source;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static com.financialhelper.source.ExtractionGoldenSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class SourceExtractionGoldenTest {
    @Test
    void syntheticHtmlPreservesStagesAndRowSpecificConditionsThroughChunking() throws Exception {
        byte[] raw;
        try (var input = getClass().getResourceAsStream("/official/extraction/synthetic-card-process.html")) {
            assertThat(input).isNotNull();
            raw = input.readAllBytes();
        }
        String text = parse(new HtmlSourceDocumentParser(), SourceAcquisitionType.HTML,
                "text/html;charset=UTF-8", raw).normalizedContent();
        assertThat(text).doesNotContain("제외할 메뉴");
        assertThat(text.indexOf("접수 단계:")).isLessThan(text.indexOf("조사 단계:"));
        assertThat(text.indexOf("조사 단계:")).isLessThan(text.indexOf("결과 통지 단계:"));
        int individual = text.indexOf("개인용 확인자료 A");
        int company = text.indexOf("기업용 확인자료 D");
        assertThat(individual).isGreaterThanOrEqualTo(0);
        assertThat(company).isGreaterThan(individual);
        assertThat(text.substring(individual, company)).contains("카드대출 사고인 경우에만",
                "추가자료 B", "위조 또는 변조 사고인 경우", "카드실물 C");
        assertThat(text.substring(company)).contains("대리 접수인 경우에만", "위임자료 E");
        String chunked = bodies(chunks(text, 500, 600));
        assertThat(chunked).contains("접수 단계:", "조사 단계:", "결과 통지 단계:",
                "추가자료 B", "카드실물 C", "위임자료 E");
    }

    @Test
    void splitExceptionKeepsArticleAndExactOffsetsButNeedsExplicitReviewRelationship() {
        String text = "제1조 합성접수\n신청 자료를 접수하고 내용을 확인합니다.\n"
                + "다만 대리 접수인 경우 추가 확인이 필요합니다.";
        var result = chunks(text, 4, 9);
        assertThat(result).hasSizeGreaterThan(1);
        var support = result.stream().filter(c -> c.body().contains("신청 자료를")).findFirst().orElseThrow();
        var exception = result.stream().filter(c -> c.body().contains("다만 대리")).findFirst().orElseThrow();
        assertThat(support.sequence()).isNotEqualTo(exception.sequence());
        assertThat(exception.articleReference()).isEqualTo(support.articleReference());
        assertThat(exception.body()).contains("추가 확인이 필요합니다.");
        // Shared article/offset enables review; it does not create a V38 EXCEPTION link.
    }

    @Test
    void syntheticPdfPreservesTwoPagesRowTextAndExceptionOrder() throws Exception {
        byte[] raw;
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            List<List<String>> pages = List.of(
                    List.of("SYNTHETIC Article 1 - intake, investigation, notification.",
                            "Applicant | Required material", "Individual | Material A", "Company | Material D"),
                    List.of("SYNTHETIC Article 2 - conditions, not financial advice.",
                            "Only for loan incidents: material B.", "Except proxy applications: verify material E."));
            for (var lines : pages) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(40, 700);
                    for (var line : lines) {
                        stream.showText(line);
                        stream.newLineAtOffset(0, -20);
                    }
                    stream.endText();
                }
            }
            pdf.save(output);
            raw = output.toByteArray();
        }
        String text = parse(new PdfSourceDocumentParser(), SourceAcquisitionType.PDF,
                "application/pdf", raw).normalizedContent();
        assertThat(text).contains("[PAGE 1]", "[PAGE 2]", "Individual | Material A", "Company | Material D");
        assertThat(text.indexOf("Article 1")).isLessThan(text.indexOf("[PAGE 2]"));
        assertThat(text.indexOf("[PAGE 2]")).isLessThan(text.indexOf("Only for loan"));
        var result = chunks(text, 500, 600);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().pageReference()).isEqualTo("p.1-2");
        assertThat(bodies(result)).contains("Except proxy applications: verify material E.");
        // Delimited text rows are not proof of arbitrary PDF grid/table reconstruction.
    }

    @Test
    void syntheticHwpPreservesParagraphHeadingsAndExceptionAcrossTwoSections() throws Exception {
        byte[] raw = hwp(List.of(
                List.of("제1조 합성접수", "개인 자료 A와 기업 자료 D는 서로 다른 대상을 나타냅니다. ".repeat(3)),
                List.of("제2조 합성예외", "다만 대리인이 접수하는 경우에는 합성 자료 E를 별도로 확인합니다.")));
        String text = parse(new HwpSourceDocumentParser(), SourceAcquisitionType.HWP,
                "application/x-hwp", raw).normalizedContent();
        var result = chunks(text, 500, 600);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).articleReference()).isEqualTo("제1조 합성접수");
        assertThat(result.get(1).articleReference()).isEqualTo("제2조 합성예외");
        assertThat(result.get(1).body()).contains("다만 대리인이", "자료 E");
        assertThat(result).allSatisfy(c -> assertThat(c.pageReference()).isNull());
    }
}

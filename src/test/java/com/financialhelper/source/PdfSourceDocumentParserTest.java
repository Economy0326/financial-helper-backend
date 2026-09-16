package com.financialhelper.source;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfSourceDocumentParserTest {

    @Test
    void extracts_text_with_page_locator_and_preserves_raw_hash() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(40, 700);
                content.showText("Official card loss compensation evidence. ".repeat(6));
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            pdf = output.toByteArray();
        }

        SourceIngestionData.Snapshot source = new SourceIngestionData.Snapshot(
                UUID.randomUUID(), "kb-form", "㈜KB국민카드", "img2.kbcard.com",
                "https://img2.kbcard.com/form.pdf", SourceAcquisitionType.PDF, null);
        SourceIngestionData.Parsed parsed = new PdfSourceDocumentParser().parse(
                source,
                new SourceIngestionData.Fetched(
                        source.canonicalUrl(), "application/pdf", null, null,
                        pdf, OffsetDateTime.now()));

        assertThat(parsed.normalizedContent()).contains("[PAGE 1]");
        assertThat(parsed.normalizedContent()).contains("Official card loss compensation evidence");
        assertThat(parsed.rawSha256()).isEqualTo(SourceHashing.sha256(pdf));
        assertThat(parsed.originalContent()).isEqualTo(pdf);
    }
}

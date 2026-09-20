package com.financialhelper.source;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 문장이 포함된 공식 PDF용 문장 추출기다. 원본 PDF byte는 변경 없이 전달하며
 * PDF를 HTML로 취급하지 않고 page marker로 안정적인 chunk locator를 제공한다.
 */
@Component
public class PdfSourceDocumentParser implements SourceDocumentParser {

    private static final int MIN_NORMALIZED_LENGTH = 120;
    private static final Pattern MULTI_SPACE = Pattern.compile("[\\t\\x0B\\f ]+");

    @Override
    public boolean supports(SourceAcquisitionType acquisitionType) {
        return acquisitionType == SourceAcquisitionType.PDF;
    }

    @Override
    public SourceIngestionData.Parsed parse(
            SourceIngestionData.Snapshot source,
            SourceIngestionData.Fetched fetched
    ) {
        if (!supports(source.acquisitionType())) {
            throw new SourceIngestionException(
                    "SOURCE_TYPE_UNSUPPORTED",
                    "PDF parser cannot parse this acquisition type"
            );
        }

        String contentType = fetched.contentType();
        if (contentType != null
                && !contentType.toLowerCase().contains("pdf")) {
            throw new SourceIngestionException(
                    "SOURCE_CONTENT_TYPE_UNSUPPORTED",
                    "Official source did not return PDF"
            );
        }

        String normalizedContent = extractText(fetched.originalContent());
        if (normalizedContent.length() < MIN_NORMALIZED_LENGTH) {
            throw new SourceIngestionException(
                    "SOURCE_CONTENT_TOO_SHORT",
                    "Official PDF content is too short to trust as a document"
            );
        }

        return new SourceIngestionData.Parsed(
                fetched.resolvedUrl(),
                extractTitle(source, fetched.originalContent()),
                null,
                fetched.retrievedAt(),
                fetched.contentType(),
                fetched.httpEtag(),
                fetched.httpLastModified(),
                SourceHashing.sha256(fetched.originalContent()),
                SourceHashing.sha256(normalizedContent),
                fetched.originalContent(),
                normalizedContent
        );
    }

    private String extractText(byte[] originalContent) {
        try (PDDocument document = Loader.loadPDF(originalContent)) {
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder pages = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalize(stripper.getText(document));
                if (pageText.isBlank()) {
                    continue;
                }
                if (pages.length() > 0) {
                    pages.append('\n');
                }
                pages.append("[PAGE ").append(page).append("]\n")
                        .append(pageText);
            }
            return pages.toString().trim();
        } catch (IOException | RuntimeException exception) {
            throw new SourceIngestionException(
                    "SOURCE_PDF_PARSE_FAILED",
                    "Could not parse official PDF source",
                    exception
            );
        }
    }

    private String extractTitle(
            SourceIngestionData.Snapshot source,
            byte[] originalContent
    ) {
        try (PDDocument document = Loader.loadPDF(originalContent)) {
            String title = document.getDocumentInformation().getTitle();
            // office에서 export한 일부 PDF에는 관련 없는 application title이 있다.
            // 예를 들어 "PowerPoint" 같은 값은 Evidence 제목으로 사용하지 않는다.
            if (title != null && !title.isBlank()
                    && !title.contains("PowerPoint")
                    && !title.contains("?")
                    && !title.contains("\uFFFD")) {
                return title.trim();
            }
        } catch (IOException | RuntimeException ignored) {
            // 위 문장 parsing이 trust boundary이며 title metadata는 optional이다.
        }
        return source.sourceKey();
    }

    private String normalize(String text) {
        return text == null ? "" : text
                .replace('\u00A0', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::strip)
                .map(MULTI_SPACE::matcher)
                .map(matcher -> matcher.replaceAll(" "))
                .filter(line -> !line.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("")
                .trim();
    }
}

package com.financialhelper.source;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StructuredTablePreviewTest {
    @Test void preservesHeadersAndMergedCellsWithPreviewOnlyLocators() {
        var root = Jsoup.parse("<main><p>합성 안내</p><table><caption>합성 표</caption><tr><th rowspan='2'>대상</th><th colspan='2'>조건</th></tr><tr><th>금액</th><th>예외</th></tr><tr><td>신규</td><td>10</td><td>기존 제외</td></tr></table></main>").selectFirst("main");
        var result = new StructuredTablePreview().extract(root);
        assertThat(result.status()).isEqualTo("CONTENT_REVIEW_REQUIRED");
        var table = result.tables().getFirst();
        assertThat(table.caption()).isEqualTo("합성 표");
        assertThat(table.cells()).anyMatch(c -> c.row() == 0 && c.column() == 0 && c.rowSpan() == 2 && c.header());
        assertThat(table.cells()).anyMatch(c -> c.row() == 2 && c.column() == 2 && c.text().equals("기존 제외") && c.locator().equals("preview-table-v1:0:r2:c2"));
        assertThat(table.status()).isEqualTo("REVIEW_REQUIRED");
    }
    @Test void invalidSpanAndMissingHeaderRemainOnHold() {
        var root = Jsoup.parse("<main><table><tr><td rowspan='0'>합성</td></tr></table></main>").selectFirst("main");
        assertThat(new StructuredTablePreview().extract(root).tables().getFirst().status()).isEqualTo("HOLD");
    }
}

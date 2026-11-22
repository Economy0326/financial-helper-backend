package com.financialhelper.source;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Boundary-shape fixtures only; these strings are not reviewed official evidence. */
class SixTopicSelectorSyntheticContractTest {
    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("fsc-card-use-qa", "Q1) 국내에서 카드를 도난, 분실시 조치방법은 무엇인지?\n카드를 분실하거나 합성 신고해야 합니다.\nQ2) 리볼빙", 1),
                Arguments.of("police-campaign", "STEP 01. 보이스피싱 피해 입었다면 즉시 112로 전화하여 합성 피해 사실을 알려주세요! STEP 02.", 1),
                Arguments.of("fsc-early-repayment-qa", "1. 기존계약분도 적용대상인지? 합성 적용되지 않습니다. 2. 새마을금고", 1),
                Arguments.of("fsc-silsong24-guide", "소비자가 앱으로 요청하면 병원이 실손보험금 청구서류를 보험회사로 자동으로 보내주는 합성 실손보험 청구전산화 시행(‘24.10.25일)\n연계 완료 : 실손24 연계가 완료되어 청구전산화 서비스를 이용할 수 있는 요양기관", 2),
                Arguments.of("law-financial-consumer-protection-19", "제19조(설명의무) ① 합성 수수료 등 투자성 상품에 관한 중요한 사항으로서 대통령령으로 정하는 사항", 1),
                Arguments.of("fsc-consumer-complaints", "금융소비자와 금융회사간의 분쟁조정 또는 금융위원회·금융감독원에 대한 민원을 신청할 수 있습니다. 합성 금융민원 신청하기", 1));
    }

    @ParameterizedTest @MethodSource("fixtures")
    void preservedBoundaryAndRoleStayBoundToExactNormalizedOffsets(String key, String content, int expected) {
        SourceRegistry registry = mock(SourceRegistry.class);
        SourceDocument document = mock(SourceDocument.class);
        when(registry.getSourceKey()).thenReturn(key);
        when(registry.getCanonicalUrl()).thenReturn(OfficialInformationClaimSelector.officialUrl(key));
        when(document.getSourceRegistry()).thenReturn(registry);
        when(document.getCanonicalUrl()).thenReturn(OfficialInformationClaimSelector.officialUrl(key));
        when(document.getStatus()).thenReturn(SourceDocumentStatus.ACTIVE);
        when(document.getNormalizedContent()).thenReturn(content);
        List<SourceChunkData.Definition> selected = OfficialInformationClaimSelector.select(document, "fixture-v1", "{}");
        assertThat(selected).hasSize(expected);
        for (var item : selected) {
            assertThat(item.body()).isEqualTo(content.substring(item.sourceStartOffset(), item.sourceEndOffset()));
            assertThat(item.locator()).isEqualTo("normalized-content:utf16:" + item.sourceStartOffset() + "-" + item.sourceEndOffset());
            assertThat(OfficialInformationClaimSelector.role(key, item.sequence()))
                    .isEqualTo("fsc-silsong24-guide".equals(key) && item.sequence() == 1 ? "CONDITION" : "SUPPORT");
        }
        when(document.getNormalizedContent()).thenReturn("changed synthetic document without the reviewed boundaries");
        assertThatThrownBy(() -> OfficialInformationClaimSelector.select(document, "fixture-v1", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }
}

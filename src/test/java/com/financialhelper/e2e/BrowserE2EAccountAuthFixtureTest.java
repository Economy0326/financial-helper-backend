package com.financialhelper.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserE2EAccountAuthFixtureTest {
    @Test
    void syntheticSubjectIsScopedToRunAndUnique() {
        String first = BrowserE2EAccountAuthFixture.syntheticSubject("runabc12");
        String second = BrowserE2EAccountAuthFixture.syntheticSubject("runabc12");
        assertThat(first).startsWith("browser-e2e:runabc12:").isNotEqualTo(second);
        assertThatThrownBy(() -> BrowserE2EAccountAuthFixture.syntheticSubject("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

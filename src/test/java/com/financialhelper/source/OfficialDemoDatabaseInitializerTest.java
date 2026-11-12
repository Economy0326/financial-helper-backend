package com.financialhelper.source;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialDemoDatabaseInitializerTest {
    @Test void refusesMissingContainerIdentityBeforeDatabaseAccess() {
        assertThatThrownBy(() -> OfficialDemoDatabaseInitializer.verify(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Official demo database isolation could not be verified");
    }

    @Test void refusesAnArbitraryDatabaseTarget() {
        var environment = new MockEnvironment()
                .withProperty("FH_DEMO_RUN_ID", "0123456789abcdef0123456789abcdef")
                .withProperty("FH_DEMO_CONTAINER_ID", "not-a-container")
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/financial_helper_qa");
        assertThatThrownBy(() -> OfficialDemoDatabaseInitializer.verify(environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void persistentModeRefusesMissingOwnerAndContainerBeforeDatabaseAccess() {
        var environment = new MockEnvironment()
                .withProperty("FH_DEMO_STORAGE_MODE", "persistent")
                .withProperty("FH_DEMO_DB_URL", "jdbc:postgresql://127.0.0.1:54765/fh_official_local_demo");
        assertThatThrownBy(() -> OfficialDemoDatabaseInitializer.verify(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Official demo database isolation could not be verified");
    }
}

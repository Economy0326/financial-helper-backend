package com.financialhelper.testsafety;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IsolatedDatabaseInitializerTest {
    @Test
    void existingLocalDatabaseCannotBeAccepted() {
        Environment env = mock(Environment.class);
        when(env.getProperty("spring.datasource.url")).thenReturn("jdbc:postgresql://localhost/financial_helper_test");
        assertThatThrownBy(() -> IsolatedDatabaseInitializer.verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("isolation could not be verified");
    }
}

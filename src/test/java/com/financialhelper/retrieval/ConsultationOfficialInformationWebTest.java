package com.financialhelper.retrieval;

import com.financialhelper.account.AccountSessionAuthenticationFilter;
import com.financialhelper.account.RateLimitFilter;
import com.financialhelper.common.config.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsultationOfficialInformationController.class)
@Import(SecurityConfig.class)
class ConsultationOfficialInformationWebTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ConsultationOfficialInformationService service;
    @MockitoBean private AccountSessionAuthenticationFilter accountFilter;
    @MockitoBean private RateLimitFilter rateFilter;

    @BeforeEach
    void passThroughAuthenticationFilters() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(accountFilter).doFilter(any(), any(), any());
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(rateFilter).doFilter(any(), any(), any());
    }

    @Test
    void csrf_is_required_on_the_new_post_route() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/consultations/{id}/official-information/search", id)
                        .contentType("application/json")
                        .content("{\"expectedCaseInputRevision\":1}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void valid_csrf_reaches_the_controller_without_client_search_scope() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.search(any(), any(), any())).thenReturn(
                new ConsultationOfficialInformationResponse(id, 1, 1, null, null,
                        "NEEDS_CLARIFICATION", false, List.of("INSTITUTION"),
                        List.of(), List.of(), List.of()));
        mvc.perform(post("/api/v1/consultations/{id}/official-information/search", id)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedCaseInputRevision\":1}"))
                .andExpect(status().isOk());
        verify(service).search(eq(id), any(), any());
    }
}

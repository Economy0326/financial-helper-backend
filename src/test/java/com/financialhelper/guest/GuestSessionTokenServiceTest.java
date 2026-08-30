package com.financialhelper.guest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestSessionTokenServiceTest {

    private final GuestSessionTokenService tokenService =
            new GuestSessionTokenService();

    // 랜덤 Token은 매번 다르게
    @Test
    void generatesDifferentRawTokens() {
        String first =
                tokenService.generateRawToken();

        String second =
                tokenService.generateRawToken();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
    }

    // 같은 Raw Token = 같은 Hash
    @Test
    void hashesSameTokenDeterministically() {
        String rawToken = "test-guest-token";

        String firstHash =
                tokenService.hashToken(rawToken);

        String secondHash =
                tokenService.hashToken(rawToken);

        assertThat(firstHash)
                .isEqualTo(secondHash);

        assertThat(firstHash)
                .isNotEqualTo(rawToken);
    }
}
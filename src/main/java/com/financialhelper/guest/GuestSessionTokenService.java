package com.financialhelper.guest;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;

@Service
public class GuestSessionTokenService {

    private static final int TOKEN_BYTE_LENGTH = 32;

    // SecureRandom => 예측 불가능한 충분히 큰 랜덤값
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateRawToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(tokenBytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(tokenBytes);
    }

    public String hashToken(String rawToken) {
      try {
          MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");

          byte[] hash = messageDigest.digest(
                  // charset => 글자를 byte로 어떻게 번역할지 정하는 규칙
                  // 환경에 따라 바이트 결과가 달라지지 않게 함
                  rawToken.getBytes(StandardCharsets.UTF_8)
          );

          return HexFormat.of().formatHex(hash);
      } catch (NoSuchAlgorithmException exception) {
          throw new IllegalStateException(
                  "SHA-256 algorithm is not available.",
                  exception
          );
      }
  }
}
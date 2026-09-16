package com.financialhelper.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SourceHashing {

    private SourceHashing() {
    }

    // SHA-256 해시 실제 구현
    public static String sha256(
            byte[] content
    ) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            return HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    content
                            )
                    );

        } catch (
                NoSuchAlgorithmException exception
        ) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    exception
            );
        }
    }

    // Type을 byte로 변환
    public static String sha256(
            String content
    ) {
        return sha256(
                content.getBytes(
                        StandardCharsets.UTF_8
                )
        );
    }
}
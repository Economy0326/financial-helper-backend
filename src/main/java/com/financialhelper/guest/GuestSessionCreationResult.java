package com.financialhelper.guest;

public record GuestSessionCreationResult(
        // GuestSession => DB에 저장되는 서버쪽 세션 객체
        GuestSession guestSession,

        // rawToken => 브라우저 Cookie에 넣어줄 실제 토큰 원문
        String rawToken
) {
}
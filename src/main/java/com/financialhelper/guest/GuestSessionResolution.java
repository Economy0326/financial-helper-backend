package com.financialhelper.guest;

import java.util.Optional;

public final class GuestSessionResolution {

    private final GuestSession guestSession;
    private final String rawTokenToSet;

    private GuestSessionResolution(
            GuestSession guestSession,
            String rawTokenToSet
    ) {
        this.guestSession = guestSession;
        this.rawTokenToSet = rawTokenToSet;
    }

    public static GuestSessionResolution existing(
            GuestSession guestSession
    ) {
        return new GuestSessionResolution(
                guestSession,
                null
        );
    }

    public static GuestSessionResolution created(
            GuestSession guestSession,
            String rawToken
    ) {
        return new GuestSessionResolution(
                guestSession,
                rawToken
        );
    }

    public GuestSession getGuestSession() {
        return guestSession;
    }

    public Optional<String> getRawTokenToSet() {
        return Optional.ofNullable(rawTokenToSet);
    }
}
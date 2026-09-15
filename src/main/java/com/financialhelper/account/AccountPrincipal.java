package com.financialhelper.account;

import java.security.Principal;
import java.util.UUID;

public record AccountPrincipal(UUID accountId, AccountProvider provider, String providerSubject)
        implements Principal {
    @Override
    public String getName() {
        return accountId.toString();
    }
}

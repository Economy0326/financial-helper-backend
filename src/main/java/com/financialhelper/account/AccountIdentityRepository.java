package com.financialhelper.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountIdentityRepository extends JpaRepository<AccountIdentity, UUID> {
    Optional<AccountIdentity> findByProviderAndProviderSubject(AccountProvider provider, String providerSubject);
}

package com.financialhelper.account;

import com.financialhelper.guest.GuestSessionTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountSessionService {
    private final AccountRepository accountRepository;
    private final AccountIdentityRepository identityRepository;
    private final AccountSessionRepository sessionRepository;
    private final GuestSessionTokenService tokenService;
    private final AccountProperties properties;

    public AccountSessionService(AccountRepository accountRepository,
                                 AccountIdentityRepository identityRepository,
                                 AccountSessionRepository sessionRepository,
                                 GuestSessionTokenService tokenService,
                                 AccountProperties properties) {
        this.accountRepository = accountRepository;
        this.identityRepository = identityRepository;
        this.sessionRepository = sessionRepository;
        this.tokenService = tokenService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<Account> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return sessionRepository.findByTokenHashAndExpiresAtAfter(
                        tokenService.hashToken(rawToken), now())
                .map(AccountSession::getAccount)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE);
    }

    @Transactional
    public LoginSession createSession(AccountProvider provider, String subject, String displayName) {
        OffsetDateTime current = now();
        Account account = identityRepository.findByProviderAndProviderSubject(provider, subject)
                .map(identity -> {
                    identity.touch(current);
                    Account existing = identity.getAccount();
                    existing.refreshDisplayName(displayName, current);
                    return existing;
                })
                .orElseGet(() -> accountRepository.findByProviderAndProviderSubject(provider, subject)
                        .map(existing -> {
                            existing.refreshDisplayName(displayName, current);
                            identityRepository.save(new AccountIdentity(existing, provider, subject, current));
                            return existing;
                        })
                        .orElseGet(() -> {
                            Account created = accountRepository.save(new Account(provider, subject, displayName, current));
                            identityRepository.save(new AccountIdentity(created, provider, subject, current));
                            return created;
                        }));
        String raw = tokenService.generateRawToken();
        sessionRepository.save(new AccountSession(account, tokenService.hashToken(raw), current, current.plus(properties.sessionTtl())));
        return new LoginSession(raw, account);
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            sessionRepository.deleteByTokenHash(tokenService.hashToken(rawToken));
        }
    }

    public Optional<Account> currentAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            return Optional.empty();
        }
        return accountRepository.findById(principal.accountId())
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE);
    }

    public Optional<UUID> currentAccountId() {
        return currentAccount().map(Account::getId);
    }

    public boolean isAuthenticated() { return currentAccount().isPresent(); }

    public record LoginSession(String rawToken, Account account) { }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}

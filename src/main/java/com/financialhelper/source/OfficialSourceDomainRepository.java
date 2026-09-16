package com.financialhelper.source;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialSourceDomainRepository
        // JpaRepository를 상속함으로써 기본적인 CRUD 사용 가능
        extends JpaRepository<
                OfficialSourceDomain,
                String
        > {

    boolean existsByDomainAndEnabledTrue(
            String domain
    );
}
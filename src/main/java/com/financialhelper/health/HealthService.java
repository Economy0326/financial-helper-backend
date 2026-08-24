package com.financialhelper.health;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    // spring data JPA -> JdbcTemplate
    private final JdbcTemplate jdbcTemplate;

    // spring이 만들어 둔 객체를 HealthService에 주입해줌
    public HealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isDatabaseUp() {
        try {
            // queryForObject: 단일 값 sql 결과를 읽을 수 있음
            Integer result = jdbcTemplate.queryForObject(
                    "SELECT 1",
                    Integer.class
            );

            return result != null && result == 1;
        } catch (DataAccessException exception) {
            return false;
        }
    }
}
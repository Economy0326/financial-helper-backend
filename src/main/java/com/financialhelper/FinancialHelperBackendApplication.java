package com.financialhelper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FinancialHelperBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                FinancialHelperBackendApplication.class,
                args
        );
    }
}
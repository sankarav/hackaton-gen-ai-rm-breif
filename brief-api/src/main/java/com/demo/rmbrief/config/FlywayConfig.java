package com.demo.rmbrief.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Explicit Flyway wiring — Spring Boot 4 auto-configuration doesn't reliably
 * detect flyway-core on the classpath without this.
 * Defining the bean here also lets JPA LocalContainerEntityManagerFactoryBean
 * @DependsOn it (Spring Boot detects the Flyway bean type automatically).
 */
@Configuration
public class FlywayConfig {

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        return flyway;
    }
}

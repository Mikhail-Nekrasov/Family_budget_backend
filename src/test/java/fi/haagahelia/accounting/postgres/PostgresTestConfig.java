package fi.haagahelia.accounting.postgres;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource") // Container lifecycle is managed by Spring.
    public PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17")
            .withDatabaseName("family_budget_test")
            .withUsername("test_user")
            .withPassword("test_password");
    }
}
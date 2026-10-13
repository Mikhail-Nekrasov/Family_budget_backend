package fi.haagahelia.accounting.postgres;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import fi.haagahelia.accounting.service.TransferTransactionIntegrationTest;

@ActiveProfiles(value = "postgres-test", inheritProfiles = false)
@Import(PostgresTestConfig.class)
class PostgresTransferTransactionIntegrationTest
        extends TransferTransactionIntegrationTest {
}
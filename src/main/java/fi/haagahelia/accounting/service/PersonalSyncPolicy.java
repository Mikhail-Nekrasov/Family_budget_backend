package fi.haagahelia.accounting.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Account;

@Service
public class PersonalSyncPolicy {
    private final JdbcTemplate jdbcTemplate;

    public PersonalSyncPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requirePrivateAccount(Account account) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM group_account_access WHERE account_id = ?",
            Long.class, account.getAccount_id()
        );
        if (count != null && count > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Shared accounts require the online API; queued personal commands are not allowed"
            );
        }
    }
}

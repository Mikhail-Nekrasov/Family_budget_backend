package fi.haagahelia.accounting.repository;

import java.math.BigDecimal;

public interface AccountExpenseTotal {
    Long getAccountId();
    BigDecimal getTotal();
}
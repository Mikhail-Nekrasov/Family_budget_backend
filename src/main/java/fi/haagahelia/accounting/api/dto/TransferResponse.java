package fi.haagahelia.accounting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import fi.haagahelia.accounting.model.Transfer;

public record TransferResponse(
    Long id,
    Long fromAccountId,
    String fromAccountName,
    Long toAccountId,
    String toAccountName,
    BigDecimal amount,
    LocalDateTime dateTime,
    String description,
    UUID syncId,
    BigDecimal targetAmount,
    String sourceCurrencyCode,
    String targetCurrencyCode,
    Long version
) {
    public static TransferResponse from(Transfer transfer) {
        var from = transfer.getFromAccount();
        var to = transfer.getToAccount();
        return new TransferResponse(
            transfer.getId(), from.getAccount_id(), from.getName(),
            to.getAccount_id(), to.getName(), transfer.getAmount(),
            transfer.getDateTime(), transfer.getDescription(), transfer.getSyncId(),
            transfer.getTargetAmount(), transfer.getSourceCurrencyCode(),
            transfer.getTargetCurrencyCode(), transfer.getVersion()
        );
    }
}
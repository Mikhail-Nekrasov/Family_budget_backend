package fi.haagahelia.accounting.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(
        name = "sync_id",
        nullable = false,
        unique = true,
        updatable = false
    )
    private UUID syncId = UUID.randomUUID();

    @ManyToOne
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne
    @JoinColumn(name = "to_account_id")
    private Account toAccount;
    private BigDecimal amount;

    @Column(name = "target_amount", nullable = false)
    private BigDecimal targetAmount;

    @Column(name = "source_currency_code", nullable = false, length = 3)
    private String sourceCurrencyCode;

    @Column(name = "target_currency_code", nullable = false, length = 3)
    private String targetCurrencyCode;

    private LocalDateTime dateTime;
    private String description;

    public Transfer() {}

    public Transfer(Account fromAccount, Account toAccount, BigDecimal amount, String description) {
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.targetAmount = amount;
        this.sourceCurrencyCode = fromAccount.getCurrencyCode();
        this.targetCurrencyCode = toAccount.getCurrencyCode();
        this.description = description;
        this.dateTime = LocalDateTime.now();
    }

    // probably this view is more comfortable to read?
    public Long getId() { return id; }
    public UUID getSyncId() { return syncId; }
    public Account getFromAccount() { return fromAccount; }
    public void setFromAccount(Account fromAccount) { this.fromAccount = fromAccount; }
    public Account getToAccount() { return toAccount; }
    public void setToAccount(Account toAccount) { this.toAccount = toAccount; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getDateTime() { return dateTime; }
    public void setDateTime(LocalDateTime dateTime) { this.dateTime = dateTime; }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
    }

    public String getSourceCurrencyCode() {
        return sourceCurrencyCode;
    }

    public String getTargetCurrencyCode() {
        return targetCurrencyCode;
    }

    public void assignSyncId(UUID syncId) {
        if (id != null) {
            throw new IllegalStateException(
                "Cannot change sync ID of a persisted transfer"
            );
        }

        if (syncId == null) {
            throw new IllegalArgumentException("Sync ID is required");
        }

        this.syncId = syncId;
    }
}


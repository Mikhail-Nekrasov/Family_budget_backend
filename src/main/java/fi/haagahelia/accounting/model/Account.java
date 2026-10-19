package fi.haagahelia.accounting.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;

@Entity
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long account_id;
    @Column(
        name = "sync_id",
        nullable = false,
        unique = true,
        updatable = false
    )
    private UUID syncId = UUID.randomUUID();
    private String name;
    private BigDecimal amount;
    private String description;

    @Version
    private Long version;

    @OneToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, mappedBy = "account")
    private List<Entry> entries;

    @OneToMany(mappedBy = "fromAccount")
    private List<Transfer> fromTransfers = new ArrayList<>();

    @OneToMany(mappedBy = "toAccount")
    private List<Transfer> toTransfers = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;


    public Account() {
    }

    public Account(String name, BigDecimal amount, String description, User user) {
        this.name = name;
        this.amount = amount;
        this.description = description;
        this.user = user;
    }

    public Long getVersion() {
        return version;
    }

    public Long getAccount_id() {
        return account_id;
    }

    public UUID getSyncId() {
        return syncId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public void setEntries(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Transfer> getFromTransfers() {
        return fromTransfers;
    }

    public void setFromTransfers(List<Transfer> fromTransfers) {
        this.fromTransfers = fromTransfers;
    }

    public List<Transfer> getToTransfers() {
        return toTransfers;
    }

    public void setToTransfers(List<Transfer> toTransfers) {
        this.toTransfers = toTransfers;
    }

    public User getUser() {
        return this.user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public void assignSyncId(UUID syncId) {
        if (account_id != null) {
            throw new IllegalStateException(
                "Cannot change sync ID of a persisted account"
            );
        }

        if (syncId == null) {
            throw new IllegalArgumentException("Sync ID is required");
        }

        this.syncId = syncId;
    }

    @Override
    public String toString() {
        return this.name;
    }

}

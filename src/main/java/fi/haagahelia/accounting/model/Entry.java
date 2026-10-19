package fi.haagahelia.accounting.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;

@Entity
public class Entry {
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
    private String title;
    @Enumerated(EnumType.STRING)
    private EntryType type; // "income" or "expense"
    private BigDecimal amount;
    private LocalDateTime dateTime;
    private String description;
    @Version
    private Long version;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    

    public Entry() {}

    public Entry(String title, EntryType type, BigDecimal amount, LocalDateTime dateTime, String description,
            Category category, Account account, User user) {
        this.title = title;
        this.type = type;
        this.amount = amount;
        this.dateTime = dateTime;
        this.description = description;
        this.category = category;
        this.account = account;
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public UUID getSyncId() {
        return syncId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public EntryType getType() {
        return type;
    }

    public void setType(EntryType type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDateTime getDateTime() {
        return dateTime;
    }

    public String getFormattedDateTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM HH:mm");
        return this.dateTime.format(formatter);
    }

    public String getDateTimeForInput() {
        if (dateTime == null) return "";
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        return dateTime.format(formatter);
    }

    public void setDateTime(LocalDateTime dateTime) {
        this.dateTime = dateTime;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Long getVersion() {
        return version;
    }

    public void assignSyncId(UUID syncId) {
        if (id != null) {
            throw new IllegalStateException(
                "Cannot change sync ID of a persisted entry"
            );
        }

        if (syncId == null) {
            throw new IllegalArgumentException("Sync ID is required");
        }

        this.syncId = syncId;
    }
}

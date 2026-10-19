package fi.haagahelia.accounting.model;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;

@Entity
public class Category {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long category_id;
    @Column(
        name = "sync_id",
        nullable = false,
        unique = true,
        updatable = false
    )
    private UUID syncId = UUID.randomUUID();
    private String name;
    @Version
    private Long version;

    @Enumerated(EnumType.STRING)
    private EntryType type;

    @OneToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, mappedBy = "category")
    private List<Entry> entries;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    public Category() {
    }

    public Category(String name, EntryType type, User user) {
        this.name = name;
        this.type = type;
        this.user = user;
    }

    public Long getCategory_id() {
        return category_id;
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

    public List<Entry> getEntries() {
        return entries;
    }

    public void setEntries(List<Entry> entries) {
        this.entries = entries;
    }

    public EntryType getType() {
        return type;
    }

    public void setType(EntryType type) {
        this.type = type;
    }

    public User getUser() {
        return this.user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isGlobal() {
        return this.user == null;
    }

    public void assignSyncId(UUID syncId) {
        if (category_id != null) {
            throw new IllegalStateException(
                "Cannot change sync ID of a persisted category"
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

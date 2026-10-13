package fi.haagahelia.accounting.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "group_account_access",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_group_account_access",
        columnNames = {"group_id", "account_id"}
    )
)
@Getter
@NoArgsConstructor
public class GroupAccountAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private BudgetGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountPermission permission;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public GroupAccountAccess(BudgetGroup group,
                              Account account,
                              AccountPermission permission) {
        this.group = group;
        this.account = account;
        this.permission = permission;
        this.createdAt = LocalDateTime.now();
    }

    public void setPermission(AccountPermission permission) {
        this.permission = permission;
    }
}
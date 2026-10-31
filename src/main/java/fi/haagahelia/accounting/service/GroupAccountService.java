package fi.haagahelia.accounting.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.core.TypedPropertyPath;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.EntryResponse;
import fi.haagahelia.accounting.api.dto.GroupAccountResponse;
import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.BudgetGroup;
import fi.haagahelia.accounting.model.Entry;
import fi.haagahelia.accounting.model.EntryType;
import fi.haagahelia.accounting.model.GroupAccountAccess;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;
import fi.haagahelia.accounting.repository.BudgetGroupRepository;
import fi.haagahelia.accounting.repository.EntryRepository;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;
import fi.haagahelia.accounting.repository.GroupMembershipRepository;

@Service
public class GroupAccountService {

    private final BudgetGroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final AccountRepository accountRepository;
    private final GroupAccountAccessRepository accessRepository;
    private final EntryRepository entryRepository;

    public GroupAccountService(
            BudgetGroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            AccountRepository accountRepository,
            GroupAccountAccessRepository accessRepository,
            EntryRepository entryRepository) {

        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.accountRepository = accountRepository;
        this.accessRepository = accessRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public GroupAccountResponse setAccess(
            Long groupId,
            Long accountId,
            AccountPermission permission,
            User currentUser) {

        if (permission == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Permission is required"
            );
        }

        BudgetGroup group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Group not found"
            ));

        if (!membershipRepository.existsByGroup_IdAndUser(
                groupId, currentUser)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Group access denied"
            );
        }

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Account not found"
            ));

        if (account.getUser() == null
                || !currentUser.getId().equals(account.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the account owner can manage its group access"
            );
        }

        GroupAccountAccess access = accessRepository
            .findAccess(groupId, accountId)
            .orElseGet(() -> new GroupAccountAccess(
                group, account, permission
            ));

        access.setPermission(permission);
        accessRepository.save(access);

        return new GroupAccountResponse(
            group.getId(),
            account.getAccount_id(),
            account.getName(),
            account.getAmount(),
            account.getUser().getId(),
            access.getPermission(),
            account.getCurrencyCode(),
            account.getIconKey(),
            account.getArchivedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<GroupAccountResponse> getAccounts(
            Long groupId, User currentUser) {

        if (!groupRepository.existsById(groupId)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Group not found"
            );
        }

        if (!membershipRepository.existsByGroup_IdAndUser(
                groupId, currentUser)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Group access denied"
            );
        }

        return accessRepository.findSharedAccounts(groupId)
            .stream()
            .map(access -> {
                Account account = access.getAccount();

                return new GroupAccountResponse(
                    groupId,
                    account.getAccount_id(),
                    account.getName(),
                    account.getAmount(),
                    account.getUser().getId(),
                    access.getPermission(),
                    account.getCurrencyCode(),
                    account.getIconKey(),
                    account.getArchivedAt()
                );
            })
            .toList();
    }

    @Transactional
    public void revokeAccess(
            Long groupId, Long accountId, User currentUser) {

        if (!groupRepository.existsById(groupId)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Group not found"
            );
        }

        Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Account not found"
            ));

        if (account.getUser() == null
                || !currentUser.getId().equals(account.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only the account owner can manage its group access"
            );
        }

        accessRepository.findAccess(groupId, accountId)
            .ifPresent(accessRepository::delete);
    }

    private Account requireSharedAccount(
            Long groupId, Long accountId, User currentUser) {

        if (!groupRepository.existsById(groupId)) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Group not found"
            );
        }

        if (!membershipRepository.existsByGroup_IdAndUser(
                groupId, currentUser)) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Group access denied"
            );
        }

        GroupAccountAccess access = accessRepository
            .findAccess(groupId, accountId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Account is not shared with this group"
            ));

        Account account = access.getAccount();

        if (!membershipRepository.existsByGroup_IdAndUser(
                groupId, account.getUser())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Account is no longer available to this group"
            );
        }

        return account;
    }

    @Transactional(readOnly = true)
    public Page<EntryResponse> getEntries(
            Long groupId,
            Long accountId,
            User currentUser,
            EntryType type,
            int page,
            int size) {

        Account account = requireSharedAccount(
            groupId, accountId, currentUser
        );

        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Page must be non-negative and size must be between 1 and 100"
            );
        }

        Pageable pageable = PageRequest.of(
            page,
            size,
            Sort.by(
                TypedPropertyPath.<Entry, LocalDateTime>of(
                    entry -> entry.getDateTime()
                ),
                TypedPropertyPath.<Entry, Long>of(
                    entry -> entry.getId()
                )
            ).descending()
        );

        Page<Entry> entries = type == null
            ? entryRepository.findByAccount(account, pageable)
            : entryRepository.findByAccountAndType(account, type, pageable);

        return entries.map(entry -> new EntryResponse(
            entry.getId(),
            entry.getTitle(),
            entry.getType(),
            entry.getAmount(),
            entry.getDateTime(),
            entry.getDescription(),
            entry.getAccount().getAccount_id(),
            entry.getAccount().getName(),
            entry.getCategory().getCategory_id(),
            entry.getCategory().getName(),
            entry.getSyncId(),
            entry.getVersion()
        ));
    }
}
package fi.haagahelia.accounting.service;

import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fi.haagahelia.accounting.model.Account;
import fi.haagahelia.accounting.model.AccountPermission;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.GroupAccountAccessRepository;

@Service
public class AccountAccessService {

    private final GroupAccountAccessRepository accessRepository;

    public AccountAccessService(
            GroupAccountAccessRepository accessRepository) {
        this.accessRepository = accessRepository;
    }

    @Transactional(readOnly = true)
    public boolean canRead(Account account, User currentUser) {
        if (isOwner(account, currentUser)) {
            return true;
        }

        return accessRepository.countAvailableAccess(
            account.getAccount_id(),
            currentUser,
            Set.of(AccountPermission.READ, AccountPermission.EDIT)
        ) > 0;
    }

    @Transactional(readOnly = true)
    public boolean canEdit(Account account, User currentUser) {
        if (isOwner(account, currentUser)) {
            return true;
        }

        return accessRepository.countAvailableAccess(
            account.getAccount_id(),
            currentUser,
            Set.of(AccountPermission.EDIT)
        ) > 0;
    }

    private boolean isOwner(Account account, User currentUser) {
        return account.getUser() != null
            && currentUser.getId().equals(account.getUser().getId());
    }
}
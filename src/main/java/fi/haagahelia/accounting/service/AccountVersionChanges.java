package fi.haagahelia.accounting.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fi.haagahelia.accounting.model.Account;

final class AccountVersionChanges {

    public record Change(UUID accountSyncId, long previousVersion, long version) {}

    private record Snapshot(Account account, long version) {}

    private final Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();

    AccountVersionChanges(Account... accounts) {
        for (Account account : accounts) {
            snapshots.putIfAbsent(account.getSyncId(), new Snapshot(account, account.getVersion()));
        }
    }

    List<Change> afterFlush() {
        return snapshots.values().stream()
            .filter(snapshot -> snapshot.account().getVersion() != snapshot.version())
            .map(snapshot -> new Change(snapshot.account().getSyncId(),
                snapshot.version(), snapshot.account().getVersion()))
            .toList();
    }
}

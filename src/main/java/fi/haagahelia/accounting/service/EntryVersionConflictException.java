package fi.haagahelia.accounting.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.EntryVersionConflict.Editor;
import fi.haagahelia.accounting.api.dto.EntryVersionConflict.ServerEntry;
import fi.haagahelia.accounting.model.Entry;

public class EntryVersionConflictException extends ResponseStatusException {
    private final ServerEntry serverEntry;

    public EntryVersionConflictException(Entry entry) {
        super(HttpStatus.CONFLICT, "Entry version conflict. Synchronize before retrying.");
        var editor = entry.getLastEditedBy();
        serverEntry = new ServerEntry(
            entry.getSyncId(),
            entry.getVersion(),
            entry.getTitle(),
            entry.getType(),
            entry.getAmount().stripTrailingZeros().toPlainString(),
            entry.getAccount().getCurrencyCode(),
            entry.getDateTime(),
            entry.getDescription(),
            entry.getAccount().getSyncId(),
            entry.getAccount().getName(),
            entry.getCategory().getSyncId(),
            entry.getCategory().getName(),
            editor == null ? null : new Editor(editor.getId(), editor.getUsername())
        );
    }

    public ServerEntry getServerEntry() {
        return serverEntry;
    }
}

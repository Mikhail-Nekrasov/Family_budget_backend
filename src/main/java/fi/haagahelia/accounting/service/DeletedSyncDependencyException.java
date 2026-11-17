package fi.haagahelia.accounting.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class DeletedSyncDependencyException extends ResponseStatusException {
    private final Dependency dependency;

    public DeletedSyncDependencyException(Dependency dependency) {
        super(HttpStatus.CONFLICT, "A deleted dependency must be restored before synchronization.");
        this.dependency = dependency;
    }

    public Dependency getDependency() {
        return dependency;
    }

    public record Dependency(String entityType, UUID syncId, UUID restoreToken, boolean needsCategoryDetails) {}
}

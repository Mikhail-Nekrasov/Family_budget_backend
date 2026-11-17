package fi.haagahelia.accounting.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.SyncDependencyRecoveryService;
import fi.haagahelia.accounting.service.SyncDependencyRecoveryService.RestoreRequest;
import fi.haagahelia.accounting.service.SyncDependencyRecoveryService.RestoredDependency;

@RestController
public class SyncDependencyRecoveryRestController {
    private final UserRepository users;
    private final SyncDependencyRecoveryService recovery;

    public SyncDependencyRecoveryRestController(UserRepository users, SyncDependencyRecoveryService recovery) {
        this.users = users;
        this.recovery = recovery;
    }

    @PostMapping("/api/sync/dependencies/restore")
    public RestoredDependency restore(Authentication authentication, @RequestBody RestoreRequest request) {
        var user = users.findByUsername(authentication.getName());
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        return recovery.restore(user.getId(), request);
    }
}

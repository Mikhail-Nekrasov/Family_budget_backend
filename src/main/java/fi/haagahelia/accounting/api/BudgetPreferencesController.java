package fi.haagahelia.accounting.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.BudgetPreferencesService;
import fi.haagahelia.accounting.service.BudgetPreferencesService.Preferences;

@RestController
@RequestMapping("/api/budget-preferences")
public class BudgetPreferencesController {
    private final UserRepository users;
    private final BudgetPreferencesService preferences;

    public BudgetPreferencesController(UserRepository users, BudgetPreferencesService preferences) {
        this.users = users;
        this.preferences = preferences;
    }

    @GetMapping
    public Preferences get(Authentication auth) {
        return preferences.get(requireUser(auth));
    }

    @PutMapping
    public Preferences update(Authentication auth, @RequestBody Preferences request) {
        return preferences.update(requireUser(auth), request);
    }

    private User requireUser(Authentication auth) {
        User user = auth == null ? null : users.findByUsername(auth.getName());
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user;
    }
}

package fi.haagahelia.accounting.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fi.haagahelia.accounting.api.dto.AuthResponse;
import fi.haagahelia.accounting.api.dto.CsrfResponse;
import fi.haagahelia.accounting.api.dto.RegisterRequest;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.service.RegistrationService;

@RestController
@RequestMapping("/api/auth")
public class AuthRestController {

    private final RegistrationService registrationService;

    public AuthRestController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/csrf")
    public CsrfResponse getCsrf(CsrfToken csrfToken) {
        return new CsrfResponse(
            csrfToken.getToken(),
            csrfToken.getHeaderName()
        );
    }

    @GetMapping("/me")
    public AuthResponse getCurrentUser(Authentication auth) {
        return new AuthResponse(auth.getName());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @RequestBody RegisterRequest request) {

        User user = registrationService.register(
            request.username(),
            request.password(),
            request.passwordConfirm()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new AuthResponse(user.getUsername()));
    }
}
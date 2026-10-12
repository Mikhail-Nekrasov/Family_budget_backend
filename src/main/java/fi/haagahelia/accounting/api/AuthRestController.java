package fi.haagahelia.accounting.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fi.haagahelia.accounting.api.dto.AuthResponse;
import fi.haagahelia.accounting.api.dto.CsrfResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthRestController {

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
}
package fi.haagahelia.accounting.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.service.RegistrationService;

@Controller
@RequestMapping("/register")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping
    public String showRegistrationForm(Model model) {
        model.addAttribute("userForm", new UserForm());
        return "register";
    }

    @PostMapping
    public String register(
            @ModelAttribute("userForm") UserForm userForm,
            Model model) {

        try {
            registrationService.register(
                userForm.getUsername(),
                userForm.getPassword(),
                userForm.getPasswordConfirm()
            );
        } catch (ResponseStatusException exception) {
            int status = exception.getStatusCode().value();

            if (status != HttpStatus.BAD_REQUEST.value()
                    && status != HttpStatus.CONFLICT.value()) {
                throw exception;
            }

            boolean usernameError =
                status == HttpStatus.CONFLICT.value()
                || userForm.getUsername() == null
                || userForm.getUsername().isBlank();

            model.addAttribute(
                usernameError ? "usernameError" : "passwordError",
                exception.getReason()
            );

            return "register";
        }

        return "redirect:/login";
    }
}
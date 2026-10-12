package fi.haagahelia.accounting.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.service.RegistrationService;

@ExtendWith(MockitoExtension.class)
class RegistrationControllerTest {

    @Mock
    private RegistrationService registrationService;

    @Mock
    private Model model;

    @InjectMocks
    private RegistrationController controller;

    @Test
    void redirectsToLoginAfterRegistration() {
        UserForm form = createForm();

        String result = controller.register(form, model);

        assertEquals("redirect:/login", result);
        verify(registrationService).register(
            "test_user", "test-password", "test-password"
        );
        verifyNoInteractions(model);
    }

    @Test
    void showsUsernameErrorWhenUsernameExists() {
        UserForm form = createForm();

        when(registrationService.register(
            "test_user", "test-password", "test-password"
        )).thenThrow(new ResponseStatusException(
            HttpStatus.CONFLICT,
            "This username already exists"
        ));

        String result = controller.register(form, model);

        assertEquals("register", result);
        verify(model).addAttribute(
            "usernameError", "This username already exists"
        );
    }

    @Test
    void showsPasswordErrorWhenPasswordsDoNotMatch() {
        UserForm form = createForm();
        form.setPasswordConfirm("different-password");

        when(registrationService.register(
            "test_user", "test-password", "different-password"
        )).thenThrow(new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Passwords do not match"
        ));

        String result = controller.register(form, model);

        assertEquals("register", result);
        verify(model).addAttribute(
            "passwordError", "Passwords do not match"
        );
    }

    private UserForm createForm() {
        UserForm form = new UserForm();
        form.setUsername("test_user");
        form.setPassword("test-password");
        form.setPasswordConfirm("test-password");
        return form;
    }
}
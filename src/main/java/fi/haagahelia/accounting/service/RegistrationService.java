package fi.haagahelia.accounting.service;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.model.Role;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.RoleRepository;
import fi.haagahelia.accounting.repository.UserRepository;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final DefaultCategoryService defaultCategoryService;

    public RegistrationService(UserRepository userRepository,
                               RoleRepository roleRepository,
                               BCryptPasswordEncoder passwordEncoder,
                               DefaultCategoryService defaultCategoryService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.defaultCategoryService = defaultCategoryService;
    }

    @Transactional
    public User register(String username,
                         String password,
                         String passwordConfirm) {

        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Username is required"
            );
        }

        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Password is required"
            );
        }

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Password must not exceed 72 UTF-8 bytes"
            );
        }

        if (!password.equals(passwordConfirm)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Passwords do not match"
            );
        }

        if (userRepository.findByUsername(username) != null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This username already exists"
            );
        }

        Role role = roleRepository.findByName("ROLE_USER");

        if (role == null) {
            role = new Role();
            role.setName("ROLE_USER");
            role = roleRepository.save(role);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setRoles(Set.of(role));

        User savedUser = userRepository.save(user);
        defaultCategoryService.createForUser(savedUser);

        return savedUser;
    }
}
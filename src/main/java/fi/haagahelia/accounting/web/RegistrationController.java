package fi.haagahelia.accounting.web;

import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import fi.haagahelia.accounting.model.Role;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.RoleRepository;
import fi.haagahelia.accounting.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class RegistrationController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @GetMapping("/register")
    public String showRegistrationForm(Model model) {
        model.addAttribute("userForm", new UserForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@ModelAttribute("userForm") UserForm userForm, Model model) {

        if (!userForm.getPassword().equals(userForm.getPasswordConfirm())) {
            model.addAttribute("passwordError", "Passwords do not match");
            return "register";
        }

        if (userRepository.findByUsername(userForm.getUsername()) != null) {
            model.addAttribute("usernameError", "This username already exists");
            return "register";
        }

        Role role = roleRepository.findByName("ROLE_USER");
        if (role == null) {
            role = new Role();
            role.setName("ROLE_USER");
            roleRepository.save(role);
        }

        User user = new User();
        user.setUsername(userForm.getUsername());
        user.setPassword(passwordEncoder.encode(userForm.getPassword()));
        user.setRoles(Set.of(role));

        userRepository.save(user);

        return "redirect:/login";
    }
}

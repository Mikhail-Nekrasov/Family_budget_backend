package fi.haagahelia.accounting.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fi.haagahelia.accounting.api.dto.AccountResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.AccountService;

@ExtendWith(MockitoExtension.class)
class AccountRestControllerTest {

    @Mock
    private AccountService accountService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Authentication auth;

    @InjectMocks
    private AccountRestController controller;

    private MockMvc mockMvc;
    private User user;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        user = new User();
        user.setId(1L);
        user.setUsername("test_user");

        when(auth.getName()).thenReturn("test_user");
        when(userRepository.findByUsername("test_user")).thenReturn(user);
    }

    @Test
    void returnsAccountsAsJson() throws Exception {
        AccountResponse account = new AccountResponse(
            10L,
            "Cash",
            new BigDecimal("1000.00"),
            "Cash account"
        );

        when(accountService.getAccounts(user))
            .thenReturn(List.of(account));

        mockMvc.perform(get("/api/accounts").principal(auth))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
            ))
            .andExpect(content().json("""
                [
                  {
                    "id": 10,
                    "name": "Cash",
                    "amount": 1000.00,
                    "description": "Cash account"
                  }
                ]
                """, JsonCompareMode.STRICT));

        verify(accountService).getAccounts(user);
    }

    @Test
    void returnsEmptyArrayWhenUserHasNoAccounts() throws Exception {
        when(accountService.getAccounts(user)).thenReturn(List.of());

        mockMvc.perform(get("/api/accounts").principal(auth))
            .andExpect(status().isOk())
            .andExpect(content().json("[]", JsonCompareMode.STRICT));
    }
}
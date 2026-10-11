package fi.haagahelia.accounting.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousApiRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void anonymousWebRequestRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/accounts")
                .accept(MediaType.TEXT_HTML))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/login"));
    }
}
package fi.haagahelia.accounting.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesOpenApiDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                .with(user("documentation_user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("Family Budget API"))
            .andExpect(jsonPath("$.paths['/api/accounts']").exists())
            .andExpect(jsonPath("$.paths['/api/categories']").exists())
            .andExpect(jsonPath("$.paths['/api/entries']").exists())
            .andExpect(jsonPath("$.paths['/api/transfers']").exists());
    }

    @Test
    void documentsSessionAndCsrfRequirements() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                .with(user("documentation_user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.components.securitySchemes.sessionCookie.in"
            ).value("cookie"))
            .andExpect(jsonPath(
                "$.components.securitySchemes.csrfToken.name"
            ).value("X-CSRF-TOKEN"))
            .andExpect(jsonPath(
                "$.paths['/api/accounts'].post.security[0].sessionCookie"
            ).isArray())
            .andExpect(jsonPath(
                "$.paths['/api/accounts'].post.security[0].csrfToken"
            ).isArray())
            .andExpect(jsonPath(
                "$.paths['/api/auth/csrf'].get.security"
            ).isEmpty());
    }

    @Test
    void documentsLoginAndLogout() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                .with(user("documentation_user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath(
                "$.paths['/api/auth/login'].post.requestBody"
                    + ".content['application/x-www-form-urlencoded']"
            ).exists())
            .andExpect(jsonPath(
                "$.paths['/api/auth/login'].post.security[0].csrfToken"
            ).isArray())
            .andExpect(jsonPath(
                "$.paths['/api/auth/login'].post.security[0].sessionCookie"
            ).doesNotExist())
            .andExpect(jsonPath(
                "$.paths['/api/auth/logout'].post.responses['204']"
            ).exists());
    }
}
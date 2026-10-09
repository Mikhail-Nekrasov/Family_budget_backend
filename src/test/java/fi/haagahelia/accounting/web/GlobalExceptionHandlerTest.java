package fi.haagahelia.accounting.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class GlobalExceptionHandlerTest {

    @RestController
    static class ConflictController {

        @GetMapping("/test/conflict")
        public String conflict() {
            throw new OptimisticLockingFailureException("Test conflict");
        }
    }

    @Test
    public void returnsConflictWithReadableMessage() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ConflictController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                    "The account was updated by another request. "
                    + "Refresh the data and try again."
                ));
    }
}
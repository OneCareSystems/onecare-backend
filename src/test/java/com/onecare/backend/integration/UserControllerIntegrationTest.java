package com.onecare.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class UserControllerIntegrationTest {

    @Autowired MockMvc mvc;

    private static final String BODY = """
        {"username":"alice1","email":"alice1@x.com","password":"Passw0rd!","role":"ADMIN"}""";

    @Test
    @WithMockUser(authorities = "USER_CREATE")
    void create_then_duplicate() throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(BODY))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.password").doesNotExist())
           .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(BODY))
           .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(authorities = "USER_CREATE")
    void invalidRole_returns400() throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON)
               .content(BODY.replace("ADMIN", "HACKER")))
           .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "USER_UPDATE")
    void resetPassword_returns501() throws Exception {
        mvc.perform(post("/api/users/1/reset-password"))
           .andExpect(status().isNotImplemented());
    }

    @Test
    @WithMockUser(authorities = "SOMETHING_ELSE")
    void unauthorized_returns403() throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(BODY))
           .andExpect(status().isForbidden());
    }
}
}

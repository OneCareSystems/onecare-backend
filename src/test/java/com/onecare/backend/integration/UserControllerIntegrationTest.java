package com.onecare.backend.integration;

import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class UserControllerIntegrationTest {

    @Autowired MockMvc mvc;

    @Autowired UserRepository userRepository;

    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;

    @MockitoBean
    EmailService emailService;

    private static final String BODY = """
        {"username":"alice1","email":"alice1@x.com","password":"Passw0rd!","role":"ADMIN"}""";

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @WithMockUser(authorities = {"ROLE_ADMIN", "USER_CREATE"})
    void create_then_duplicate() throws Exception {
        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(BODY))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.data.password").doesNotExist())
           .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        mvc.perform(post("/api/users").contentType(APPLICATION_JSON).content(BODY))
           .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_ADMIN", "USER_CREATE"})
    void invalidRole_returns400() throws Exception {
        String invalidRoleBody = BODY.replace("ADMIN", "HACKER");

        mvc.perform(post("/api/users")
                .contentType(APPLICATION_JSON)
                .content(invalidRoleBody))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_ADMIN", "USER_UPDATE"})
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


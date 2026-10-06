package com.onecare.backend.integration;

import com.onecare.backend.service.MedicineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MedicineControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private MedicineService medicineService;

    @Test
    @WithMockUser(authorities = "SOMETHING_ELSE")
    void updateStock_withoutMedicineUpdateAuthority_returns403() throws Exception {

        String body = """
                {
                    "delta": 5
                }
                """;

        mvc.perform(
                patch("/api/medicines/1/stock")
                        .contentType(APPLICATION_JSON)
                        .content(body)
        )
        .andExpect(status().isForbidden());
    }
}
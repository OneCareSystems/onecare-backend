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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    // ------------------------------------------------------------------
    // Doctors hold MEDICINE_READ (not MEDICINE_READ_ALL) and must be able to
    // browse the catalogue and pick available medicines for a prescription.
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR", "MEDICINE_READ"})
    void listMedicines_asDoctorWithMedicineRead_returns200() throws Exception {
        mvc.perform(get("/api/medicines"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR", "MEDICINE_READ"})
    void searchMedicines_asDoctorWithMedicineRead_returns200() throws Exception {
        mvc.perform(get("/api/medicines").param("search", "Para"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = {"ROLE_DOCTOR", "MEDICINE_READ"})
    void availableMedicines_asDoctorWithMedicineRead_returns200() throws Exception {
        mvc.perform(get("/api/medicines/available"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void listMedicines_asDoctorWithoutMedicineRead_returns403() throws Exception {
        mvc.perform(get("/api/medicines"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ROLE_DOCTOR")
    void availableMedicines_asDoctorWithoutMedicineRead_returns403() throws Exception {
        mvc.perform(get("/api/medicines/available"))
                .andExpect(status().isForbidden());
    }
}
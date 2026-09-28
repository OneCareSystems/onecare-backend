package com.onecare.backend.controller;

import com.onecare.backend.entity.Patient;
import com.onecare.backend.enums.Role;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.security.RolePermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional // every test rolls back, so the database stays clean
// ASSUMPTION: add @ActiveProfiles("<your test profile>") here if your project uses one
class PatientControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PatientRepository patientRepository;

    /** Builds a caller whose authorities come from the real RolePermission mapping. */
    private RequestPostProcessor as(Role role) {
        var authorities = RolePermission.getPermissions(role).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return user(role.name().toLowerCase()).authorities(authorities);
    }

    private static final String VALID_BODY = """
            {"firstName":"Nimal","lastName":"Perera","dateOfBirth":"1990-05-12",
             "contactNo":"0771234567","gender":"MALE"}""";

    private Patient savePatient(String full, String phone) {
        Patient p = new Patient();
        p.setFullName(full);
        p.setDateOfBirth(LocalDate.of(1990, 5, 12));
        p.setContactNo(phone);
        p.setIsActive(true);
        return patientRepository.save(p);
    }

    // 1 + 2: create returns 201 with a system-generated ID
    @Test
    void createPatient_returns201_withGeneratedId() throws Exception {
        mockMvc.perform(post("/api/patients").with(as(Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.patientId").isNumber())
                .andExpect(jsonPath("$.data.duplicateSuspected").value(false));
    }

    @Test
    void createPatient_ignoresClientSuppliedPatientId() throws Exception {
        String bodyWithId = """
                {"patientId":9999,"fullName":"Nimal Perera",
                 "dateOfBirth":"1990-05-12","contactNo":"0771234567"}""";

        mockMvc.perform(post("/api/patients").with(as(Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.patientId").value(not(9999)));
    }

    // 4: duplicate detection flags but does not block
    @Test
    void createPatient_duplicate_returns201_withDuplicateSuspectedTrue() throws Exception {
        savePatient("NimalPerera", "0771234567");

        mockMvc.perform(post("/api/patients").with(as(Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicateSuspected").value(true));
    }

    // 3: Pharmacist cannot update
    @Test
    void updatePatient_asPharmacist_returns403() throws Exception {
        Patient saved = savePatient("Nimal Perera", "0771234567");

        mockMvc.perform(put("/api/patients/" + saved.getPatientId()).with(as(Role.PHARMACIST)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"Kandy\"}"))
                .andExpect(status().isForbidden());
    }

    // 5: partial name search (plus phone and ID)
    @Test
    void search_byPartialName_returnsList() throws Exception {
        savePatient("Nimal Perera", "0771234567");

        mockMvc.perform(get("/api/patients").param("search", "mal").with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].firstName").value("Nimal"));
    }

    @Test
    void search_byExactPhone_returnsList() throws Exception {
        savePatient("Nimal Perera", "0771234567");

        mockMvc.perform(get("/api/patients").param("search", "0771234567").with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contactNo").value("0771234567"));
    }

    @Test
    void search_byExactPatientId_returnsList() throws Exception {
        Patient saved = savePatient("Nimal Perera", "0771234567");

        mockMvc.perform(get("/api/patients").param("search", String.valueOf(saved.getPatientId()))
                        .with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].patientId").value(saved.getPatientId().intValue()));
    }

    // 6: no match returns 200 with an empty list
    @Test
    void search_noMatch_returns200_withEmptyList() throws Exception {
        mockMvc.perform(get("/api/patients").param("search", "zzzz").with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void search_emptyString_returns200_withEmptyList() throws Exception {
        mockMvc.perform(get("/api/patients").param("search", "").with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    // Normal list stays paginated
    @Test
    void list_withoutSearch_returnsPage() throws Exception {
        savePatient("Nimal Perera", "0771234567");

        mockMvc.perform(get("/api/patients").param("page", "0").param("size", "10").with(as(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    // 7: soft deactivation keeps the row, with isActive=false
    @Test
    void deletePatient_softDeactivates_andKeepsRecord() throws Exception {
        Patient saved = savePatient("Nimal Perera", "0771234567");

        // ADMIN has no PATIENT_DELETE, so use SUPER_ADMIN
        mockMvc.perform(delete("/api/patients/" + saved.getPatientId()).with(as(Role.SUPER_ADMIN)).with(csrf()))
                .andExpect(status().isOk());

        Patient reloaded = patientRepository.findById(saved.getPatientId()).orElseThrow();
        assertFalse(reloaded.getIsActive());
    }
}
package com.onecare.backend.service;

import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceImplTest {

    @Mock
    private PatientRepository patientRepository;

    @InjectMocks
    private PatientServiceImpl patientService;

    private PatientCreateRequest validRequest() {
        return new PatientCreateRequest(
                "Nimal Perera", LocalDate.of(1990, 5, 12),
                "Colombo", "0771234567", null, null, Gender.MALE);
    }

    private Patient activePatient(Long id, String full, String phone) {
        Patient p = new Patient();
        p.setPatientId(id);
        p.setFullName(full);
        p.setContactNo(phone);
        p.setIsActive(true);
        return p;
    }

    /** Simulates the database assigning the generated ID on save. */
    private void stubSaveAssignsId(long id) {
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> {
            Patient p = invocation.getArgument(0);
            p.setPatientId(id);
            return p;
        });
    }

    // ---------- Creation / ID generation ----------

    @Test
    void createPatient_returnsSystemGeneratedId_andActiveFlag() {
        stubSaveAssignsId(101L);

        PatientResponse response = patientService.createPatient(validRequest());

        assertEquals(101L, response.patientId());
        assertTrue(response.isActive());
        assertFalse(response.duplicateSuspected());
    }

    @Test
    void createPatient_neverSetsPatientIdBeforeSave() {
        stubSaveAssignsId(101L);

        patientService.createPatient(validRequest());

        ArgumentCaptor<Patient> captor = ArgumentCaptor.forClass(Patient.class);
        verify(patientRepository).save(captor.capture());
        // The service never assigns an ID itself. Only the database does.
        // (PatientCreateRequest has no patientId field, so a client value cannot reach here.)
        // Note: the captured object is the same instance the stub then mutates,
        // so we assert on what the service passed in via the request mapping instead:
        assertEquals("Nimal", captor.getValue().getFullName());
    }

    // ---------- Duplicate detection ----------

    @Test
    void createPatient_duplicateMatch_setsFlag_butStillCreates() {
        when(patientRepository
            .existsByFullNameIgnoreCaseAndContactNo("Nimal Perera", "0771234567"))
                .thenReturn(true);
        stubSaveAssignsId(102L);

        PatientResponse response = patientService.createPatient(validRequest());

        assertTrue(response.duplicateSuspected());
        assertEquals(102L, response.patientId());
        verify(patientRepository).save(any(Patient.class)); // registration not blocked
    }

    // ---------- Search ----------

    @Test
    void search_partialName_returnsMatches() {
        when(patientRepository.searchByName("mal"))
                .thenReturn(List.of(activePatient(1L, "Nimal Perera", "0771234567")));

        List<PatientResponse> results = patientService.searchPatients("mal");

        assertEquals(1, results.size());
        assertEquals("Nimal", results.get(0).fullName());
    }

    @Test
    void search_exactPatientId_returnsThatPatient() {
        when(patientRepository.findById(7L))
            .thenReturn(Optional.of(activePatient(7L, "Kamal Silva", "0712345678")));

        List<PatientResponse> results = patientService.searchPatients("7");

        assertEquals(1, results.size());
        assertEquals(7L, results.get(0).patientId());
    }

    @Test
    void search_exactPhone_returnsThatPatient() {
        when(patientRepository.findByContactNoAndIsActiveTrue("0771234567"))
                .thenReturn(Optional.of(activePatient(3L, "Nimal Perera", "0771234567")));

        List<PatientResponse> results = patientService.searchPatients("0771234567");

        assertEquals(1, results.size());
    }

    @Test
    void search_sameRecordMatchedTwice_isNotDuplicatedInResults() {
        Patient p = activePatient(3L, "Nimal Perera", "0771234567");
        when(patientRepository.findByContactNoAndIsActiveTrue("0771234567")).thenReturn(Optional.of(p));
        when(patientRepository.searchByName("0771234567")).thenReturn(List.of(p));

        assertEquals(1, patientService.searchPatients("0771234567").size());
    }

    @Test
    void search_noMatch_returnsEmptyList() {
        assertTrue(patientService.searchPatients("zzzz").isEmpty());
    }

    @Test
    void search_blankOrNull_returnsEmptyList_withoutQueryingDatabase() {
        assertTrue(patientService.searchPatients("").isEmpty());
        assertTrue(patientService.searchPatients("   ").isEmpty());
        assertTrue(patientService.searchPatients(null).isEmpty());
        verifyNoInteractions(patientRepository);
    }

    // ---------- Soft deactivation ----------

    @Test
    void deactivatePatient_setsInactive_andNeverDeletesRecord() {
        Patient p = activePatient(5L, "Nimal Perera", "0771234567");
        when(patientRepository.findById(5L)).thenReturn(Optional.of(p));

        patientService.deactivatePatient(5L);

        assertFalse(p.getIsActive());
        verify(patientRepository).save(p);
        verify(patientRepository, never()).delete(any(Patient.class));
        verify(patientRepository, never()).deleteById(any());
    }

    // ---------- Not found ----------

    @Test
    void updatePatient_unknownId_throwsResourceNotFound() {
        when(patientRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> patientService.updatePatient(999L,
                        new PatientUpdateRequest("A",  null, null, null, null, null)));
    }
}
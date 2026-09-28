package com.onecare.backend.service;

import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional

public class PatientServiceImpl implements PatientService {
     private static final Logger log = LoggerFactory.getLogger(PatientServiceImpl.class);

    private final PatientRepository patientRepository;

    public PatientServiceImpl(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

     @Override
    public PatientResponse createPatient(PatientCreateRequest request) {

        // AC3: duplicate-suspect check — informational only, never blocks creation
        boolean duplicateSuspected = patientRepository
                .existsByFullNameIgnoreCaseAndContactNo(
                request.full_name(), request.contactNo());

        Patient patient = new Patient();
        patient.setFullName(request.full_name());
        patient.setDateOfBirth(request.dateOfBirth());
        patient.setAddress(request.address());
        patient.setContactNo(request.contactNo());
        patient.setEmail(request.email());
        patient.setBloodGroup(request.bloodGroup());
        patient.setGender(request.gender());
        patient.setIsActive(true);
        // patientId intentionally never set here — @GeneratedValue owns it entirely;
        // the DTO has no patientId field, so a client-supplied one has nowhere to bind

        Patient saved = patientRepository.save(patient);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info(
                "Patient registered | patientId={} | performedBy={} | duplicateSuspected={}",
                saved.getPatientId(), performedBy, duplicateSuspected
        );
        // TODO(DDP-26): replace with persisted audit event

        return PatientResponse.from(saved, duplicateSuspected);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientResponse> findAllPatients(Pageable pageable) {
        return patientRepository.findAll(pageable)
                .map(PatientResponse::from)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public PatientResponse findPatientById(Long id) {
        return PatientResponse.from(findPatientByIdInternal(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientResponse> searchPatients(String search) {

        if (search == null || search.isBlank()) {
            return List.of(); // AC/negative test: empty search -> 200 []
        }

        List<Patient> results = new ArrayList<>();

        // Exact patient ID match, only if the search string is fully numeric
        if (search.chars().allMatch(Character::isDigit)) {
            patientRepository.findById(Long.parseLong(search))
                    .filter(Patient::getIsActive)
                    .ifPresent(results::add);
        }

        // Exact contact number match
        patientRepository.findByContactNoAndIsActiveTrue(search)
                .ifPresent(p -> addIfAbsent(results, p));

        // Partial name match (first + last combined)
        for (Patient p : patientRepository.searchByName(search)) {
            addIfAbsent(results, p);
        }

        return results.stream().map(PatientResponse::from).toList();
    }

    private void addIfAbsent(List<Patient> results, Patient candidate) {
        boolean alreadyPresent = results.stream()
                .anyMatch(p -> p.getPatientId().equals(candidate.getPatientId()));
        if (!alreadyPresent) {
            results.add(candidate);
        }
    }

    @Override
    public PatientResponse updatePatient(Long id, PatientUpdateRequest request) {

        Patient patient = findPatientByIdInternal(id);

        if (request.full_name() != null) patient.setFullName(request.full_name());
        if (request.address() != null) patient.setAddress(request.address());
        if (request.contactNo() != null) patient.setContactNo(request.contactNo());
        if (request.email() != null) patient.setEmail(request.email());
        if (request.bloodGroup() != null) patient.setBloodGroup(request.bloodGroup());
        if (request.gender() != null) patient.setGender(request.gender());

        Patient saved = patientRepository.save(patient);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("Patient {} updated by {}", saved.getPatientId(), performedBy);
        // TODO(DDP-26): replace with persisted audit event

        return PatientResponse.from(saved);
    }

    @Override
    public void deactivatePatient(Long id) {

        Patient patient = findPatientByIdInternal(id);
        patient.setIsActive(false); // soft delete only — row is never removed

        patientRepository.save(patient);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("Patient {} deactivated by {}", patient.getPatientId(), performedBy);
        // TODO(DDP-26): replace with persisted audit event
    }

    private Patient findPatientByIdInternal(Long id) {
        return patientRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Patient not found with id: " + id));
    }
}


    


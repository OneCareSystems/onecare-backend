package com.onecare.backend.service;

import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.PatientHistoryPrescriptionResponse;
import com.onecare.backend.dto.response.PatientHistoryResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.security.Permission;
import com.onecare.backend.security.SecurityUtil;
import com.onecare.backend.util.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;

@Service
@Transactional

public class PatientServiceImpl implements PatientService {
     private static final Logger log = LoggerFactory.getLogger(PatientServiceImpl.class);

    private final PatientRepository patientRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;

    public PatientServiceImpl(PatientRepository patientRepository,
                              AppointmentRepository appointmentRepository,
                              PrescriptionRepository prescriptionRepository) {
        this.patientRepository = patientRepository;
        this.appointmentRepository = appointmentRepository;
        this.prescriptionRepository = prescriptionRepository;
    }

     @Override
    public PatientResponse createPatient(PatientCreateRequest request) {

        String rawContactNo = request.contactNo() != null ? request.contactNo().trim() : "";
        String normalizedContactNo = PhoneNumberUtil.isValidSriLankanPhone(rawContactNo)
                ? PhoneNumberUtil.normalizeSriLankanPhone(rawContactNo)
                : rawContactNo;

        // AC3: duplicate-suspect check — informational only, never blocks creation.
        // Requires both matching full name AND contact number.
        boolean duplicateSuspected = patientRepository
                .existsByFullNameIgnoreCaseAndContactNo(
                request.full_name(), normalizedContactNo)
                || (!normalizedContactNo.equals(rawContactNo)
                && patientRepository.existsByFullNameIgnoreCaseAndContactNo(
                request.full_name(), rawContactNo));

        Patient patient = new Patient();
        patient.setFullName(request.full_name());
        patient.setDateOfBirth(request.dateOfBirth());
        patient.setAddress(request.address());
        patient.setContactNo(normalizedContactNo);
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
    public List<PatientResponse> findAllPatients(String search, Pageable pageable) {

        if (search == null) {
            return patientRepository.findAll(pageable)
                    .map(PatientResponse::from)
                    .getContent();
        }

        if (search.isBlank()) {
            return List.of(); // AC/negative test: empty search -> 200 []
        }

        // name (partial), contact number (exact) and patient ID (exact) in one
        // DB query, so page/size/sort apply to the filtered set
        return patientRepository.search(search, pageable).stream()
                .map(PatientResponse::from)
                .toList();
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

        String query = search.trim();
        List<Patient> results = new ArrayList<>();

        // Exact patient ID match, only if the search string is fully numeric
        if (query.chars().allMatch(Character::isDigit)) {
            patientRepository.findById(Long.parseLong(query))
                    .filter(Patient::getIsActive)
                    .ifPresent(results::add);
        }

        // Exact contact number match as given
        for (Patient p : patientRepository.findByContactNoAndIsActiveTrue(query)) {
            addIfAbsent(results, p);
        }

        // Contact number match using normalized format (+94 XXX XXX XXX)
        String normalizedSearch = PhoneNumberUtil.normalizeSriLankanPhone(query);
        if (normalizedSearch != null && !normalizedSearch.equals(query)) {
            for (Patient p : patientRepository.findByContactNoAndIsActiveTrue(normalizedSearch)) {
                addIfAbsent(results, p);
            }
        }

        // Contact number match using local 10-digit format (0XXXXXXXXX)
        String nineDigits = PhoneNumberUtil.extractNineDigits(query);
        if (nineDigits != null) {
            String localFormat = "0" + nineDigits;
            if (!localFormat.equals(query) && !localFormat.equals(normalizedSearch)) {
                for (Patient p : patientRepository.findByContactNoAndIsActiveTrue(localFormat)) {
                    addIfAbsent(results, p);
                }
            }
        }

        // Partial name match (first + last combined)
        for (Patient p : patientRepository.searchByName(query)) {
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
    @Transactional(readOnly = true)
    public PatientHistoryResponse getPatientHistory(Long patientId) {
        Patient patient = findPatientByIdInternal(patientId);
        LocalDate today = LocalDate.now();
        boolean includeClinicalNotes = SecurityUtil.hasPermission(Permission.APPOINTMENT_READ_CLINICAL_NOTES);

        List<AppointmentResponse> previousAppointments = appointmentRepository
                .findByPatient_PatientIdOrderByAppointmentDateDescTimeSlotDesc(patient.getPatientId())
                .stream()
                .filter(appointment -> isPreviousEncounter(appointment, today))
                .map(appointment -> AppointmentResponse.from(appointment, includeClinicalNotes))
                .toList();

        List<PatientHistoryPrescriptionResponse> previousPrescriptions = prescriptionRepository
                .findByPatient_PatientId(patient.getPatientId())
                .stream()
                .filter(prescription -> prescription.getDate().isBefore(today)
                        || prescription.getStatus() != PrescriptionStatus.ISSUED)
                .sorted(Comparator.comparing(Prescription::getDate).reversed())
                .map(prescription -> PatientHistoryPrescriptionResponse.from(
                        prescription, includeClinicalNotes))
                .toList();

        return new PatientHistoryResponse(
                PatientResponse.from(patient), previousAppointments, previousPrescriptions);
    }

    private static boolean isPreviousEncounter(Appointment appointment, LocalDate today) {
        return appointment.getAppointmentDate().isBefore(today)
                || appointment.getStatus() == AppointmentStatus.COMPLETED
                || appointment.getStatus() == AppointmentStatus.CANCELLED;
    }

    @Override
    public PatientResponse updatePatient(Long id, PatientUpdateRequest request) {

        Patient patient = findPatientByIdInternal(id);

        if (request.full_name() != null) patient.setFullName(request.full_name());
        if (request.address() != null) patient.setAddress(request.address());
        if (request.contactNo() != null) {
            String raw = request.contactNo().trim();
            String normalized = PhoneNumberUtil.isValidSriLankanPhone(raw)
                    ? PhoneNumberUtil.normalizeSriLankanPhone(raw)
                    : raw;
            patient.setContactNo(normalized);
        }
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


    


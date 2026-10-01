package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreatePrescriptionRequest;
import com.onecare.backend.dto.request.PrescriptionItemRequest;
import com.onecare.backend.dto.response.PrescriptionDetailResponse;
import com.onecare.backend.dto.response.PrescriptionResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.PrescriptionItemType;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.MedicineRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.Permission;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class PrescriptionServiceImpl implements PrescriptionService {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionServiceImpl.class);

    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final MedicineRepository medicineRepository;
    private final AppointmentRepository appointmentRepository;

    public PrescriptionServiceImpl(PrescriptionRepository prescriptionRepository,
                                   PatientRepository patientRepository,
                                   UserRepository userRepository,
                                   MedicineRepository medicineRepository,
                                   AppointmentRepository appointmentRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.medicineRepository = medicineRepository;
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public PrescriptionResponse createPrescription(CreatePrescriptionRequest request) {

        // 1. Doctor: always the authenticated user, persisted role must be DOCTOR
        User doctor = resolveAuthenticatedDoctor();

        // 2. Patient must exist and be active
        Patient patient = patientRepository.findById(request.patientId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Patient not found with id: " + request.patientId()));

        if (!Boolean.TRUE.equals(patient.getIsActive())) {
            throw new BusinessRuleException(
                    "Patient is not active with id: " + request.patientId());
        }

        // 3. Appointment is required and must exist
        if (request.appointmentId() == null) {
            throw new BusinessRuleException("appointmentId is required");
        }

        Appointment appointment = appointmentRepository.findById(request.appointmentId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment not found with id: " + request.appointmentId()));

        if (prescriptionRepository.existsByAppointmentAppointmentId(request.appointmentId())) {
            throw new BusinessRuleException(
                    "A prescription already exists for appointment id: "
                            + request.appointmentId());
        }

        // 4. Validate EVERY in-house medicine BEFORE any prescription data is written
        //    (EXTERNAL_PURCHASE items reference no catalog medicine, so nothing to validate)
        Map<Long, Medicine> medicines = loadAndValidateMedicines(request.items());

        // 5. Build prescription + items; status is set server-side only
        Prescription prescription = new Prescription();
        prescription.setDoctor(doctor);
        prescription.setPatient(patient);
        prescription.setAppointment(appointment);
        prescription.setDate(LocalDate.now());
        prescription.setStatus(PrescriptionStatus.ISSUED);
        prescription.setClinicalNotes(request.clinicalNotes());

        for (PrescriptionItemRequest itemRequest : request.items()) {
            PrescriptionItem item = new PrescriptionItem();
            item.setPrescription(prescription);
            item.setItemType(itemRequest.itemType());
            if (itemRequest.itemType() == PrescriptionItemType.IN_HOUSE) {
                item.setMedicine(medicines.get(itemRequest.medicineId()));
            } else {
                // EXTERNAL_PURCHASE: free text only, no catalog reference
                item.setMedicineName(itemRequest.medicineName());
            }
            item.setDosage(itemRequest.dosage());
            item.setFrequency(itemRequest.frequency());
            item.setDurationDays(itemRequest.durationDays());
            item.setQuantity(itemRequest.quantity());
            prescription.getItems().add(item);
        }

        // Single save cascades prescription + all items inside this transaction;
        // any later failure rolls the whole thing back (zero rows on both tables)
        Prescription saved = prescriptionRepository.save(prescription);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("Prescription created | prescriptionId={} | patientId={} | doctorId={} | items={} | performedBy={}",
                saved.getPrescriptionId(), patient.getPatientId(),
                doctor.getUserId(), saved.getItems().size(), performedBy);
        // TODO(DDP-26): replace with persisted audit event

        return PrescriptionResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionResponse> findAllPrescriptions(String status) {

        PrescriptionStatus filter = parseStatus(status);

        List<Prescription> prescriptions = filter == null
                ? prescriptionRepository.findAll()
                : prescriptionRepository.findByStatus(filter);

        // PrescriptionResponse has no clinicalNotes field — nothing to strip here
        return prescriptions.stream()
                .map(PrescriptionResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PrescriptionDetailResponse findPrescriptionById(Long id) {

        Prescription prescription = findPrescriptionByIdInternal(id);

        boolean includeClinicalNotes =
                SecurityUtil.hasPermission(Permission.PRESCRIPTION_READ_CLINICAL_NOTES);

        return PrescriptionDetailResponse.from(prescription, includeClinicalNotes);
    }

    @Override
    public void cancelPrescription(Long id) {

        Prescription prescription = findPrescriptionByIdInternal(id);

        if (prescription.getStatus() == PrescriptionStatus.DISPENSED) {
            throw new BusinessRuleException(
                    "Dispensed prescription cannot be cancelled with id: " + id);
        }

        if (prescription.getStatus() == PrescriptionStatus.CANCELLED) {
            throw new BusinessRuleException(
                    "Prescription is already cancelled with id: " + id);
        }

        // ISSUED -> CANCELLED (only reachable transition for this endpoint)
        prescription.setStatus(PrescriptionStatus.CANCELLED);
        prescriptionRepository.save(prescription);

        String performedBy = SecurityUtil.getCurrentUsername().orElse("SYSTEM");
        log.info("Prescription cancelled | prescriptionId={} | performedBy={}", id, performedBy);
        // TODO(DDP-26): replace with persisted audit event
    }

    /**
     * The prescribing doctor is ALWAYS the authenticated user — there is no
     * doctorId in the request. The persisted account is re-read so the role
     * check uses the database value, not a claim from the token.
     */
    private User resolveAuthenticatedDoctor() {

        Long userId = SecurityUtil.getCurrentUserId().orElse(null);

        Optional<User> user = userId != null
                ? userRepository.findById(userId)
                : SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername);

        User doctor = user.orElseThrow(() -> new BusinessRuleException(
                "Authenticated user could not be resolved to a system user"));

        if (doctor.getRole() != Role.DOCTOR) {
            throw new BusinessRuleException(
                    "Only users with DOCTOR role can create prescriptions");
        }

        return doctor;
    }

    /**
     * Loads every referenced IN_HOUSE medicine and applies the DDP-21 rules:
     * must exist, must not be quarantined, must not be expired.
     * EXTERNAL_PURCHASE items are skipped — they are not in the catalog.
     * Called BEFORE anything is saved, so an invalid item anywhere in the
     * request rejects the entire prescription with zero rows written.
     */
    private Map<Long, Medicine> loadAndValidateMedicines(List<PrescriptionItemRequest> items) {

        List<Long> medicineIds = items.stream()
                .filter(item -> item.itemType() == PrescriptionItemType.IN_HOUSE)
                .map(PrescriptionItemRequest::medicineId)
                .distinct()
                .toList();

        if (medicineIds.isEmpty()) {
            return Map.of(); // no in-house items — nothing to look up or validate
        }

        Map<Long, Medicine> medicines = new HashMap<>();
        medicineRepository.findAllById(medicineIds)
                .forEach(medicine -> medicines.put(medicine.getMedicineId(), medicine));

        LocalDate today = LocalDate.now();

        for (Long medicineId : medicineIds) {
            Medicine medicine = medicines.get(medicineId);

            if (medicine == null) {
                throw new BusinessRuleException(
                        "Medicine not found with id: " + medicineId);
            }
            if (Boolean.TRUE.equals(medicine.getIsQuarantined())) {
                throw new BusinessRuleException(
                        "Medicine is quarantined with id: " + medicineId);
            }
            if (medicine.getExpiryDate() != null && medicine.getExpiryDate().isBefore(today)) {
                throw new BusinessRuleException(
                        "Medicine is expired with id: " + medicineId);
            }
        }

        return medicines;
    }

    private PrescriptionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PrescriptionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException(
                    "Invalid status filter: '" + status
                            + "'. Allowed values: ISSUED, DISPENSED, CANCELLED");
        }
    }

    private Prescription findPrescriptionByIdInternal(Long id) {
        return prescriptionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Prescription not found with id: " + id));
    }
}

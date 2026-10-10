package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateExternalDispensingRequest;
import com.onecare.backend.dto.request.ExternalDispensingItemRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.request.VerifyExternalDispensingRequest;
import com.onecare.backend.dto.response.ExternalDispensingResponse;
import com.onecare.backend.entity.*;
import com.onecare.backend.enums.DeliveryMethod;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.enums.Status;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.*;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class ExternalDispensingServiceImpl implements ExternalDispensingService {

    private static final Logger log = LoggerFactory.getLogger(ExternalDispensingServiceImpl.class);

    private final ExternalDispensingRepository externalDispensingRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final MedicineRepository medicineRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final MedicineService medicineService;

    public ExternalDispensingServiceImpl(
            ExternalDispensingRepository externalDispensingRepository,
            PrescriptionRepository prescriptionRepository,
            MedicineRepository medicineRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository,
            MedicineService medicineService) {

        this.externalDispensingRepository = externalDispensingRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.medicineRepository = medicineRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.medicineService = medicineService;
    }

    @Override
    public ExternalDispensingResponse markPrescriptionExternal(Long prescriptionId) {

        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Prescription not found with id: " + prescriptionId));

        if (prescription.getStatus() != PrescriptionStatus.ISSUED) {
            throw new BusinessRuleException(
                    "Only ISSUED prescriptions can be marked for external dispensing. "
                            + "Prescription id: " + prescriptionId
                            + " has status: " + prescription.getStatus());
        }

        ExternalDispensing event = new ExternalDispensing();

        event.setPrescription(prescription);

        if (prescription.getPatient() != null) {
            event.setPatientId(prescription.getPatient().getPatientId());
        }

        event.setStatus(Status.PENDING);
        event.setVerificationStatus("PENDING");
        event.setRetryCount(0);
        event.setVerifiedAt(null);

        event.setVerificationMethod("PHONE");
        event.setDeliveryMethod(DeliveryMethod.PICK_UP);

        LocalDateTime now = LocalDateTime.now();
        event.setDispenseDate(now);
        event.setDispensedAt(now);

        ExternalDispensing saved = externalDispensingRepository.save(event);

        log.info(
                "Prescription marked for external dispensing | prescriptionId={} | dispenseId={}",
                prescriptionId,
                saved.getDispenseId());

        return ExternalDispensingResponse.from(saved);
    }

    @Override
    public ExternalDispensingResponse createExternalDispensing(CreateExternalDispensingRequest request) {
        if (request == null) {
            throw new BusinessRuleException("External dispensing request is required");
        }

        Prescription prescription = null;
        if (request.prescriptionId() != null) {
            prescription = prescriptionRepository.findById(request.prescriptionId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Prescription not found with id: " + request.prescriptionId()));
        }

        Long patientId = request.patientId();
        if (patientId == null && prescription != null && prescription.getPatient() != null) {
            patientId = prescription.getPatient().getPatientId();
        }

        if (patientId != null) {
            final Long patientLookupId = patientId;
            Patient patient = patientRepository.findById(patientLookupId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Patient not found with id: " + patientLookupId));
            if (!Boolean.TRUE.equals(patient.getIsActive())) {
                throw new BusinessRuleException("Patient is not active with id: " + patientLookupId);
            }
        }

        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessRuleException("At least one dispensed item is required");
        }

        String verificationMethod = normalizeVerificationMethod(request.verificationMethod());
        DeliveryMethod deliveryMethod = resolveDeliveryMethod(request.deliveryMethod());

        ExternalDispensing event = new ExternalDispensing();
        event.setPrescription(prescription);
        event.setPatientId(patientId);
        event.setVerificationMethod(verificationMethod);
        event.setVerificationStatus("PENDING");
        event.setStatus(Status.PENDING);
        event.setDeliveryMethod(deliveryMethod);
        LocalDateTime now = LocalDateTime.now();
        event.setDispenseDate(now);
        event.setDispensedAt(now);
        event.setRetryCount(0);
        event.setVerifiedAt(null);

        List<DispensingItem> items = new ArrayList<>();
        final Prescription prescriptionForItems = prescription;
        for (ExternalDispensingItemRequest itemRequest : request.items()) {
            if (itemRequest == null) {
                continue;
            }

            int quantity = itemRequest.quantity() == null ? 0 : itemRequest.quantity();
            if (quantity <= 0) {
                throw new BusinessRuleException("Quantity must be greater than zero");
            }

            DispensingItem item = new DispensingItem();
            item.setDispensing(event);
            item.setQuantityDispensed(quantity);

            if (itemRequest.prescriptionItemId() != null) {
                PrescriptionItem prescriptionItem = prescriptionForItems != null
                        ? prescriptionForItems.getItems().stream()
                                .filter(pi -> Objects.equals(pi.getItemId(), itemRequest.prescriptionItemId()))
                                .findFirst().orElseThrow(() -> new ResourceNotFoundException(
                                        "Prescription item not found with id: " + itemRequest.prescriptionItemId()))
                        : null;

                if (prescriptionItem != null) {
                    item.setPrescriptionItem(prescriptionItem);
                    item.setMedicine(prescriptionItem.getMedicine());
                    if (item.getMedicine() == null && itemRequest.medicineId() != null) {
                        item.setMedicine(medicineRepository.findById(itemRequest.medicineId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Medicine not found with id: " + itemRequest.medicineId())));
                    }
                }
            }

            if (item.getMedicine() == null && itemRequest.medicineId() != null) {
                Medicine medicine = medicineRepository.findById(itemRequest.medicineId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Medicine not found with id: " + itemRequest.medicineId()));
                item.setMedicine(medicine);
            }

            if (item.getMedicine() == null && itemRequest.medicineName() != null) {
                throw new BusinessRuleException("Medicine identifier is required for dispensed products");
            }

            items.add(item);
        }

        event.setItems(items);
        ExternalDispensing saved = externalDispensingRepository.save(event);

        Optional<User> actor = resolveCurrentUser();
        if (actor.isPresent()) {
            recordAudit(actor.get(), "EXTERNAL_DISPENSING_CREATED", "ExternalDispensing",
                    String.valueOf(saved.getDispenseId()),
                    "Created pending external dispensing event");
        }

        log.info("External dispensing created | dispenseId={} | patientId={} | prescriptionId={} | items={}",
                saved.getDispenseId(), patientId, prescription != null ? prescription.getPrescriptionId() : null,
                saved.getItems().size());
        return ExternalDispensingResponse.from(saved);
    }

    @Override
    public ExternalDispensingResponse verifyExternalDispensing(Long dispenseId,
            VerifyExternalDispensingRequest request) {
        ExternalDispensing event = findByIdInternal(dispenseId);
        if (event.getStatus() == Status.CANCELLED || event.getStatus() == Status.DISPENSED) {
            throw new BusinessRuleException("Only pending external dispensing events can be verified");
        }

        if (request != null && request.verificationMethod() != null && !request.verificationMethod().isBlank()) {
            event.setVerificationMethod(normalizeVerificationMethod(request.verificationMethod()));
        }

        if (request != null && request.verified() != null && !request.verified()) {
            throw new BusinessRuleException("External dispensing verification rejected");
        }

        event.setVerificationStatus("VERIFIED");
        event.setVerifiedAt(LocalDateTime.now());
        event.setRetryCount(event.getRetryCount() == null ? 1 : event.getRetryCount() + 1);

        ExternalDispensing saved = externalDispensingRepository.save(event);

        Optional<User> actor = resolveCurrentUser();
        if (actor.isPresent()) {
            recordAudit(actor.get(), "EXTERNAL_DISPENSING_VERIFIED", "ExternalDispensing",
                    String.valueOf(saved.getDispenseId()),
                    "External dispensing identity verification completed");
        }

        return ExternalDispensingResponse.from(saved);
    }

    @Override
    public ExternalDispensingResponse completeExternalDispensing(Long dispenseId) {
        ExternalDispensing event = findByIdInternal(dispenseId);
        if (event.getStatus() == Status.DISPENSED) {
            throw new BusinessRuleException("External dispensing has already been completed");
        }
        if (event.getStatus() == Status.CANCELLED) {
            throw new BusinessRuleException("Cancelled external dispensing cannot be completed");
        }
        if (event.getVerificationStatus() == null || !"VERIFIED".equalsIgnoreCase(event.getVerificationStatus())) {
            throw new BusinessRuleException("External dispensing must be verified before completion");
        }

        boolean partiallyDispensed = false;

        for (DispensingItem item : event.getItems()) {
            if (item == null) {
                continue;
            }

            Medicine medicine = item.getMedicine();
            if (medicine == null && item.getPrescriptionItem() != null
                    && item.getPrescriptionItem().getMedicine() != null) {
                medicine = item.getPrescriptionItem().getMedicine();
            }

            if (medicine == null) {
                throw new BusinessRuleException("Medicine is required for each dispensed item");
            }

            int requestedQuantity = Math.max(0, item.getQuantityDispensed());
            int availableQuantity = Math.max(0, medicine.getStockQuantity());

            int quantityToDispense = Math.min(requestedQuantity, availableQuantity);

            if (quantityToDispense > 0) {
                medicineService.updateStock(
                        medicine.getMedicineId(),
                        new StockUpdateRequest(-quantityToDispense));
            }

            item.setQuantityDispensed(quantityToDispense);

            if (quantityToDispense < requestedQuantity) {
                partiallyDispensed = true;
            }
        }

        if (event.getPrescription() != null && !partiallyDispensed) {
            Prescription prescription = event.getPrescription();

            if (prescription.getStatus() != PrescriptionStatus.DISPENSED) {
                prescription.setStatus(PrescriptionStatus.DISPENSED);
                prescriptionRepository.save(prescription);
            }
        }

        event.setStatus(
                partiallyDispensed
                        ? Status.PARTIALLY_DISPENSED
                        : Status.DISPENSED);
        event.setDispensedAt(LocalDateTime.now());
        event.setVerificationStatus("VERIFIED");
        event = externalDispensingRepository.save(event);

        Optional<User> actor = resolveCurrentUser();
        if (actor.isPresent()) {
            recordAudit(actor.get(), "EXTERNAL_DISPENSING_COMPLETED", "ExternalDispensing",
                    String.valueOf(event.getDispenseId()),
                    "External dispensing completed and stock decremented");
        }

        log.info("External dispensing completed | dispenseId={} | prescriptionId={} | stockUpdated={}",
                event.getDispenseId(),
                event.getPrescription() != null ? event.getPrescription().getPrescriptionId() : null,
                event.getItems().size());

        return ExternalDispensingResponse.from(event);
    }

    @Override
    public List<ExternalDispensingResponse> findAllExternalDispensing(String status) {
        List<ExternalDispensing> items;
        if (status == null || status.isBlank()) {
            items = externalDispensingRepository.findAll();
        } else {
            try {
                Status filter = Status.valueOf(status.trim().toUpperCase());
                items = externalDispensingRepository.findAll().stream()
                        .filter(event -> event.getStatus() == filter)
                        .toList();
            } catch (IllegalArgumentException ex) {
                throw new BusinessRuleException(
                        "Invalid status filter: " + status
                                + ". Allowed values: PENDING, DISPENSED, PARTIALLY_DISPENSED, CANCELLED");
            }
        }
        return items.stream().map(ExternalDispensingResponse::from).toList();
    }

    @Override
    public ExternalDispensingResponse findExternalDispensingById(Long dispenseId) {
        return ExternalDispensingResponse.from(findByIdInternal(dispenseId));
    }

    private ExternalDispensing findByIdInternal(Long dispenseId) {
        return externalDispensingRepository.findById(dispenseId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("External dispensing not found with id: " + dispenseId));
    }

    private Optional<User> resolveCurrentUser() {
        Long userId = SecurityUtil.getCurrentUserId().orElse(null);
        if (userId != null) {
            return userRepository.findById(userId);
        }

        String username = SecurityUtil.getCurrentUsername().orElse(null);
        if (username != null) {
            return userRepository.findByUsername(username);
        }

        return Optional.empty();
    }

    private void recordAudit(User actor, String action, String entityType, String entityId, String description) {
        if (actor == null || actor.getUserId() == null) {
            return;
        }

        AuditLog audit = new AuditLog();
        audit.setUser(actor);
        audit.setAction(action);
        audit.setActionType("DOMAIN_EVENT");
        audit.setEntityType(entityType);
        audit.setEntityId(entityId);
        audit.setDescription(description);
        audit.setTimestamp(LocalDateTime.now());
        audit.setIpAddress(null);
        AuditLog savedAudit = auditLogRepository.save(audit);

        Optional<ExternalDispensing> event = externalDispensingRepository.findById(Long.valueOf(entityId));
        event.ifPresent(existing -> {
            existing.setAuditLog(savedAudit);
            externalDispensingRepository.save(existing);
        });
    }

    private String normalizeVerificationMethod(String method) {
        if (method == null || method.isBlank()) {
            return "PHONE";
        }
        return method.trim().toUpperCase();
    }

    private DeliveryMethod resolveDeliveryMethod(String deliveryMethod) {
        if (deliveryMethod == null || deliveryMethod.isBlank()) {
            return DeliveryMethod.PICK_UP;
        }
        try {
            return DeliveryMethod.valueOf(deliveryMethod.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleException(
                    "Invalid deliveryMethod: " + deliveryMethod + ". Allowed values: PICK_UP, DELIVERY");
        }
    }
}

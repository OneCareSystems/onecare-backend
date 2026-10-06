package com.onecare.backend.service;

import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.dto.request.RecordPaymentRequest;
import com.onecare.backend.dto.request.UpdateInvoiceLineRequest;
import com.onecare.backend.dto.request.UpdateInvoiceRequest;
import com.onecare.backend.dto.response.InvoiceResponse;
import com.onecare.backend.dto.response.InvoiceSummaryResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.DispensingItem;
import com.onecare.backend.entity.ExternalDispensing;
import com.onecare.backend.entity.Invoice;
import com.onecare.backend.entity.InvoiceItem;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Payment;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.InvoiceStatus;
import com.onecare.backend.enums.PrescriptionItemType;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Status;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ConflictException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.*;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class InvoiceServiceImpl implements InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceServiceImpl.class);

    /** System-managed line: never sent by a client, never editable. */
    static final String CONSULTATION_DESCRIPTION = "Consultation charge";

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceRepository invoiceRepository;
    private final AppointmentRepository appointmentRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final ExternalDispensingRepository externalDispensingRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final BigDecimal consultationFee;

    public InvoiceServiceImpl(InvoiceRepository invoiceRepository,
                              AppointmentRepository appointmentRepository,
                              PrescriptionRepository prescriptionRepository,
                              ExternalDispensingRepository externalDispensingRepository,
                              PatientRepository patientRepository,
                              UserRepository userRepository,
                              @Value("${billing.consultation-fee:500.00}") BigDecimal consultationFee) {
        this.invoiceRepository = invoiceRepository;
        this.appointmentRepository = appointmentRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.externalDispensingRepository = externalDispensingRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.consultationFee = consultationFee;
    }

    // ------------------------------------------------------------------
    // Generate (FR035, UC22)
    // ------------------------------------------------------------------

    @Override
    public InvoiceResponse generateInvoice(GenerateInvoiceRequest request) {

        boolean hasAppointment = request != null && request.appointmentId() != null;
        boolean hasDispense = request != null && request.dispenseId() != null;

        if (hasAppointment == hasDispense) { // both or neither
            throw new BusinessRuleException(
                    "Exactly one of appointmentId or dispenseId is required");
        }

        return hasDispense
                ? generateDispenseInvoice(request.dispenseId())
                : generateAppointmentInvoice(request.appointmentId());
    }

    private InvoiceResponse generateAppointmentInvoice(Long appointmentId) {

        User billedBy = resolveAuthenticatedUser();

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment not found with id: " + appointmentId));

        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            log.warn("Invoice generation rejected | appointmentId={} | appointmentStatus={} "
                            + "| reason=CONSULTATION_NOT_COMPLETED | performedBy={}",
                    appointment.getAppointmentId(), appointment.getStatus(), billedBy.getUsername());
            throw new BusinessRuleException("Consultation for appointment id: "
                    + appointment.getAppointmentId()
                    + " is not completed; only completed consultations can be invoiced");
        }

        if (invoiceRepository.existsByAppointmentAppointmentId(appointment.getAppointmentId())) {
            log.warn("Invoice generation rejected | appointmentId={} | reason=ALREADY_INVOICED "
                            + "| performedBy={}",
                    appointment.getAppointmentId(), billedBy.getUsername());
            throw new ConflictException("An invoice already exists for appointment id: "
                    + appointment.getAppointmentId());
        }

        Prescription prescription = prescriptionRepository
                .findByAppointmentAppointmentId(appointment.getAppointmentId())
                .orElse(null);

        Invoice invoice = new Invoice();
        invoice.setPatient(appointment.getPatient());
        invoice.setAppointment(appointment);
        invoice.setStatus(InvoiceStatus.UNPAID);
        invoice.setAmountPaid(money(BigDecimal.ZERO));
        invoice.setCreatedBy(billedBy);
        invoice.setItems(buildLines(invoice, prescription));
        invoice.setTotal(sumOfLines(invoice));

        // The invoice number is derived from the generated id, so it must be
        // written after the row exists: a unique placeholder satisfies the
        // unique index inside the transaction and is replaced before commit.
        Invoice saved = persistInvoice(invoice,
                "An invoice already exists for appointment id: " + appointmentId);

        saved.setInvoiceNumber(invoiceNumber(saved.getInvoiceId()));
        saved = invoiceRepository.save(saved);

        log.info("Invoice generated | invoiceId={} | invoiceNumber={} | patientId={} "
                        + "| appointmentId={} | total={} | lines={} | performedBy={}",
                saved.getInvoiceId(), saved.getInvoiceNumber(),
                appointment.getPatient().getPatientId(), appointment.getAppointmentId(),
                saved.getTotal(), saved.getItems().size(), billedBy.getUsername());
        // TODO(DDP-25): replace with persisted audit event, same transaction

        return InvoiceResponse.from(saved);
    }

    /**
     * External dispensing invoice: medicines only (no consultation line), one
     * invoice per dispense event, patient optional for unknown walk-ins.
     */
    private InvoiceResponse generateDispenseInvoice(Long dispenseId) {

        User billedBy = resolveAuthenticatedUser();

        ExternalDispensing event = externalDispensingRepository.findById(dispenseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Dispense event not found with id: " + dispenseId));

        if (invoiceRepository.existsByDispenseDispenseId(dispenseId)) {
            log.warn("Invoice generation rejected | dispenseId={} | reason=ALREADY_INVOICED "
                            + "| performedBy={}",
                    dispenseId, billedBy.getUsername());
            throw new ConflictException("An invoice already exists for dispense event id: "
                    + dispenseId);
        }

        // Medicines are billed with the consultation invoice when that invoice
        // includes medicine lines. A consultation-only invoice (patient
        // collects later) does not block: each dispense event then carries
        // its own invoice, so every unit is still charged exactly once (AC7).
        Prescription prescription = event.getPrescription();
        if (prescription != null && prescription.getAppointment() != null) {
            Long appointmentId = prescription.getAppointment().getAppointmentId();
            Invoice consultationInvoice = invoiceRepository
                    .findByAppointmentAppointmentId(appointmentId).orElse(null);

            boolean billedWithConsultation = consultationInvoice != null
                    && consultationInvoice.getItems().stream()
                            .anyMatch(item -> item.getMedicine() != null);
            if (billedWithConsultation) {
                log.warn("Invoice generation rejected | dispenseId={} | appointmentId={} "
                                + "| reason=MEDICINES_BILLED_WITH_CONSULTATION | performedBy={}",
                        dispenseId, appointmentId, billedBy.getUsername());
                throw new ConflictException("Dispense event id: " + dispenseId
                        + " belongs to appointment id: " + appointmentId
                        + " whose invoice already includes medicine lines");
            }
        }

        Invoice invoice = new Invoice();
        invoice.setPatient(resolveDispensePatient(event));
        invoice.setDispense(event);
        invoice.setStatus(InvoiceStatus.UNPAID);
        invoice.setAmountPaid(money(BigDecimal.ZERO));
        invoice.setCreatedBy(billedBy);
        invoice.setItems(buildDispenseLines(invoice, event));
        invoice.setTotal(sumOfLines(invoice));

        Invoice saved = persistInvoice(invoice,
                "An invoice already exists for dispense event id: " + dispenseId);

        saved.setInvoiceNumber(invoiceNumber(saved.getInvoiceId()));
        saved = invoiceRepository.save(saved);

        log.info("Invoice generated | invoiceId={} | invoiceNumber={} | dispenseId={} "
                        + "| patientId={} | total={} | lines={} | performedBy={}",
                saved.getInvoiceId(), saved.getInvoiceNumber(), event.getDispenseId(),
                invoice.getPatient() != null ? invoice.getPatient().getPatientId() : null,
                saved.getTotal(), saved.getItems().size(), billedBy.getUsername());
        // TODO(DDP-25): replace with persisted audit event, same transaction

        return InvoiceResponse.from(saved);
    }

    /** Prescription patient, else the recorded pickup patient, else null (OTC walk-in). */
    private Patient resolveDispensePatient(ExternalDispensing event) {

        if (event.getPrescription() != null && event.getPrescription().getPatient() != null) {
            return event.getPrescription().getPatient();
        }
        if (event.getPatientId() != null) {
            return patientRepository.findById(event.getPatientId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Patient not found with id: " + event.getPatientId()));
        }
        return null;
    }

    /**
     * One medicine line per handed-over item: the prescribed item's catalog
     * medicine, or the item's own medicine for OTC sales. Never a
     * consultation line - no appointment happened.
     */
    private List<InvoiceItem> buildDispenseLines(Invoice invoice, ExternalDispensing event) {

        List<InvoiceItem> lines = new ArrayList<>();
        for (DispensingItem dispensed : event.getItems()) {

            Medicine medicine = dispenseMedicine(dispensed);
            if (medicine == null) {
                log.warn("Dispense line skipped | dispenseId={} | dispensingItemId={} "
                                + "| reason=EXTERNAL_PURCHASE",
                        event.getDispenseId(), dispensed.getDispensingItemId());
                continue;
            }

            int quantity = dispensed.getQuantityDispensed();
            // Never bill more than prescribed (AC7) for item-linked lines.
            if (dispensed.getPrescriptionItem() != null
                    && dispensed.getPrescriptionItem().getQuantity() != null) {
                quantity = Math.min(quantity, dispensed.getPrescriptionItem().getQuantity());
            }
            if (quantity <= 0) {
                log.warn("Dispense line skipped | dispenseId={} | dispensingItemId={} "
                                + "| quantity={} | reason=NOTHING_BILLABLE",
                        event.getDispenseId(), dispensed.getDispensingItemId(), quantity);
                continue;
            }

            lines.add(newLine(invoice, medicine, medicine.getName(),
                    quantity, medicine.getPrice()));
        }

        if (lines.isEmpty()) {
            log.warn("Invoice generation rejected | dispenseId={} "
                            + "| reason=NO_BILLABLE_MEDICINE", event.getDispenseId());
            throw new BusinessRuleException("Dispense event id: " + event.getDispenseId()
                    + " has no billable medicine (EXTERNAL_PURCHASE items are never invoiced)");
        }
        return lines;
    }

    private Medicine dispenseMedicine(DispensingItem dispensed) {
        if (dispensed.getPrescriptionItem() != null
                && dispensed.getPrescriptionItem().getMedicine() != null) {
            return dispensed.getPrescriptionItem().getMedicine(); // in-house prescribed
        }
        return dispensed.getMedicine();                           // OTC / direct product
    }

    // ------------------------------------------------------------------
    // List (FR038, UC23)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceSummaryResponse> listInvoices(Long patientId, String invoiceNumber,
                                                     String status, LocalDate dateFrom,
                                                     LocalDate dateTo, Integer page, Integer size) {

        InvoiceStatus filterStatus = parseStatus(status);

        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new BusinessRuleException("dateFrom must not be after dateTo");
        }

        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null
                ? DEFAULT_PAGE_SIZE
                : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // Newest first, always - clients cannot re-sort the financial log.
        Pageable pageable = PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<Invoice> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (patientId != null) {
                predicates.add(cb.equal(root.get("patient").get("patientId"), patientId));
            }
            if (invoiceNumber != null && !invoiceNumber.isBlank()) {
                predicates.add(cb.like(cb.upper(root.get("invoiceNumber")),
                        "%" + invoiceNumber.trim().toUpperCase() + "%"));
            }
            if (filterStatus != null) {
                predicates.add(cb.equal(root.get("status"), filterStatus));
            }
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), dateFrom.atStartOfDay()));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThan(
                        root.get("createdAt"), dateTo.plusDays(1).atStartOfDay()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return invoiceRepository.findAll(specification, pageable)
                .map(InvoiceSummaryResponse::from);
    }

    // ------------------------------------------------------------------
    // Detail (FR038, UC23)
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse findInvoiceById(Long id) {
        return InvoiceResponse.from(findInvoiceByIdInternal(id));
    }

    // ------------------------------------------------------------------
    // Line correction (UC22 alt flow)
    // ------------------------------------------------------------------

    @Override
    public InvoiceResponse updateInvoiceLines(Long id, UpdateInvoiceRequest request) {

        User editor = resolveAuthenticatedUser();
        Invoice invoice = findInvoiceByIdInternal(id);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            log.warn("Invoice line edit rejected | invoiceId={} | invoiceNumber={} "
                            + "| status=PAID | reason=PAID_INVOICE | performedBy={}",
                    invoice.getInvoiceId(), invoice.getInvoiceNumber(), editor.getUsername());
            throw new BusinessRuleException("Invoice " + invoice.getInvoiceNumber()
                    + " is paid and cannot be edited");
        }

        // Editable = medicine lines only; the consultation line is system-managed.
        Map<Long, InvoiceItem> editable = invoice.getItems().stream()
                .filter(item -> item.getMedicine() != null)
                .collect(Collectors.toMap(InvoiceItem::getInvoiceItemId, item -> item, (a, b) -> a,
                        LinkedHashMap::new));

        // Validate the whole request BEFORE mutating anything, so a rejected
        // request leaves the invoice exactly as it was (AC3).
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (UpdateInvoiceLineRequest line : request.items()) {
            if (!editable.containsKey(line.invoiceItemId())) {
                log.warn("Invoice line edit rejected | invoiceId={} | invoiceItemId={} "
                                + "| reason=LINE_NOT_EDITABLE | performedBy={}",
                        invoice.getInvoiceId(), line.invoiceItemId(), editor.getUsername());
                throw new BusinessRuleException("Invoice line id: " + line.invoiceItemId()
                        + " does not belong to invoice id: " + id
                        + " or is not editable");
            }
            if (quantities.put(line.invoiceItemId(), line.quantity()) != null) {
                throw new BusinessRuleException(
                        "Duplicate invoice line id: " + line.invoiceItemId());
            }
        }

        // Prospective total of the request: consultation line(s) are kept as they
        // are, medicine lines are the ones listed with their new quantity.
        BigDecimal prospectiveTotal = money(invoice.getItems().stream()
                .filter(item -> item.getMedicine() == null)
                .map(InvoiceItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            InvoiceItem item = editable.get(entry.getKey());
            prospectiveTotal = money(prospectiveTotal.add(
                    item.getUnitPrice().multiply(BigDecimal.valueOf(entry.getValue()))));
        }

        if (prospectiveTotal.compareTo(invoice.getAmountPaid()) < 0) {
            log.warn("Invoice line edit rejected | invoiceId={} | invoiceNumber={} | total={} "
                            + "| amountPaid={} | reason=TOTAL_BELOW_AMOUNT_PAID | performedBy={}",
                    invoice.getInvoiceId(), invoice.getInvoiceNumber(), prospectiveTotal,
                    invoice.getAmountPaid(), editor.getUsername());
            throw new BusinessRuleException("New total " + prospectiveTotal
                    + " would be less than the amount already paid " + invoice.getAmountPaid());
        }

        quantities.forEach((itemId, quantity) -> {
            InvoiceItem item = editable.get(itemId);
            item.setQuantity(quantity);
            item.setLineTotal(money(item.getUnitPrice().multiply(BigDecimal.valueOf(quantity))));
        });

        // Medicine lines omitted from the request are dropped (orphanRemoval);
        // the consultation line is kept.
        Set<Long> kept = quantities.keySet();
        invoice.getItems().removeIf(item ->
                item.getMedicine() != null && !kept.contains(item.getInvoiceItemId()));

        invoice.setTotal(sumOfLines(invoice));

        Invoice saved = invoiceRepository.saveAndFlush(invoice);

        log.info("Invoice line edited | invoiceId={} | invoiceNumber={} | total={} "
                        + "| lines={} | performedBy={}",
                saved.getInvoiceId(), saved.getInvoiceNumber(), saved.getTotal(),
                saved.getItems().size(), editor.getUsername());
        // TODO(DDP-25): replace with persisted audit event, same transaction

        return InvoiceResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // Cash payment (FR036, FR037, UC23)
    // ------------------------------------------------------------------

    @Override
    public InvoiceResponse recordPayment(Long id, RecordPaymentRequest request) {

        User cashier = resolveAuthenticatedUser();
        Invoice invoice = findInvoiceByIdInternal(id);

        if (invoice.getStatus() == InvoiceStatus.PAID) {
            log.warn("Payment rejected | invoiceId={} | invoiceNumber={} "
                            + "| reason=ALREADY_PAID | performedBy={}",
                    invoice.getInvoiceId(), invoice.getInvoiceNumber(), cashier.getUsername());
            throw new BusinessRuleException("Invoice " + invoice.getInvoiceNumber()
                    + " is already paid");
        }

        BigDecimal amount = request == null ? null : request.amount();
        if (amount == null || amount.signum() <= 0) {
            log.warn("Payment rejected | invoiceId={} | amount={} | reason=NON_POSITIVE "
                            + "| performedBy={}",
                    invoice.getInvoiceId(), amount, cashier.getUsername());
            throw new BusinessRuleException("Payment amount must be greater than zero");
        }
        if (amount.scale() > MONEY_SCALE) {
            log.warn("Payment rejected | invoiceId={} | amount={} | reason=TOO_MANY_DECIMALS "
                            + "| performedBy={}",
                    invoice.getInvoiceId(), amount, cashier.getUsername());
            throw new BusinessRuleException(
                    "Payment amount must have at most 2 decimal places");
        }

        BigDecimal outstanding = invoice.getOutstandingBalance();
        if (amount.compareTo(outstanding) > 0) {
            log.warn("Payment rejected | invoiceId={} | amount={} | outstanding={} "
                            + "| reason=OVERPAYMENT | performedBy={}",
                    invoice.getInvoiceId(), amount, outstanding, cashier.getUsername());
            throw new BusinessRuleException("Payment amount " + amount
                    + " exceeds the outstanding balance of " + outstanding);
        }

        Payment payment = new Payment();
        payment.setInvoice(invoice);
        payment.setAmount(money(amount));
        payment.setPaymentMethod(request.paymentMethod());
        payment.setRecordedBy(cashier);
        invoice.getPayments().add(payment);

        invoice.setAmountPaid(money(invoice.getAmountPaid().add(amount)));
        if (invoice.getAmountPaid().compareTo(invoice.getTotal()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        }

        // Flushes the payment insert and the invoice update (version bump)
        // together: a concurrent conflicting payment loses the optimistic lock
        // here and rolls the whole transaction back - the invoice is never
        // overpaid.
        Invoice saved = invoiceRepository.saveAndFlush(invoice);

        log.info("Payment recorded | invoiceId={} | invoiceNumber={} | amount={} "
                        + "| amountPaid={} | outstanding={} | status={} | performedBy={}",
                saved.getInvoiceId(), saved.getInvoiceNumber(), payment.getAmount(),
                saved.getAmountPaid(), saved.getOutstandingBalance(), saved.getStatus(),
                cashier.getUsername());
        // TODO(DDP-25): replace with persisted audit event, same transaction

        return InvoiceResponse.from(saved);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Consultation charge + one line per dispensed in-house item.
     * EXTERNAL_PURCHASE items are excluded (D4) and each unit price is copied
     * from the catalog as a snapshot - it is never recalculated on view.
     *
     * While the prescription has not been collected yet (ISSUED) or never
     * will be (CANCELLED), the invoice is consultation-only: the medicine is
     * billed later on per-event dispense invoices, never twice (AC7).
     */
    private List<InvoiceItem> buildLines(Invoice invoice, Prescription prescription) {

        List<InvoiceItem> lines = new ArrayList<>();
        lines.add(newLine(invoice, null, CONSULTATION_DESCRIPTION, 1, consultationFee));

        if (prescription == null) {
            return lines; // consultation only: nothing was prescribed
        }

        if (prescription.getStatus() != PrescriptionStatus.DISPENSED) {
            log.info("Consultation invoice without medicine lines | prescriptionId={} "
                            + "| status={} | reason=NOT_COLLECTED_YET",
                    prescription.getPrescriptionId(), prescription.getStatus());
            return lines;
        }

        DispensedTotals totals = dispensedTotals(prescription);

        for (PrescriptionItem item : prescription.getItems()) {
            if (item.getItemType() != PrescriptionItemType.IN_HOUSE
                    || item.getMedicine() == null) {
                continue;
            }

            int billable = billableQuantity(item, totals);
            if (billable <= 0) {
                log.warn("Invoice line skipped | prescriptionId={} | itemId={} "
                                + "| reason=NOT_DISPENSED_OR_ALREADY_BILLED",
                        prescription.getPrescriptionId(), item.getItemId());
                continue;
            }

            lines.add(newLine(invoice, item.getMedicine(), item.getMedicine().getName(),
                    billable, item.getMedicine().getPrice()));
        }

        return lines;
    }

    /**
     * Per-item dispensed totals for one prescription, split into everything
     * ever dispensed and the part already billed on standalone dispense
     * invoices, so each unit is charged exactly once (AC7).
     */
    private record DispensedTotals(Map<Long, Integer> dispensed,
                                   Map<Long, Integer> alreadyInvoiced) {
    }

    /**
     * Only dispensed quantities are billable (AC7): a per-item dispensing
     * record wins (a partially dispensed prescription bills exactly what was
     * handed out); without any per-item record the prescribed quantity was
     * handed out whole. Units already billed on their own dispense invoice
     * are subtracted so they are never charged twice.
     */
    private int billableQuantity(PrescriptionItem item, DispensedTotals totals) {

        int prescribed = item.getQuantity() == null ? 0 : item.getQuantity();

        Integer dispensed = totals.dispensed().get(item.getItemId());
        int base = dispensed == null ? prescribed : Math.min(dispensed, prescribed);

        int alreadyInvoiced = totals.alreadyInvoiced().getOrDefault(item.getItemId(), 0);
        return Math.max(base - alreadyInvoiced, 0);
    }

    private DispensedTotals dispensedTotals(Prescription prescription) {

        Map<Long, Integer> dispensed = new HashMap<>();
        Map<Long, Integer> alreadyInvoiced = new HashMap<>();

        List<ExternalDispensing> events = externalDispensingRepository
                .findByPrescription_PrescriptionIdAndStatus(
                        prescription.getPrescriptionId(), Status.DISPENSED);

        for (ExternalDispensing event : events) {
            if (event.getItems().isEmpty()) {
                continue; // whole-prescription handover: prescribed quantities apply
            }

            boolean invoiced = invoiceRepository.existsByDispenseDispenseId(event.getDispenseId());
            for (DispensingItem handover : event.getItems()) {
                if (handover.getPrescriptionItem() == null
                        || handover.getQuantityDispensed() <= 0) {
                    continue;
                }
                Long itemId = handover.getPrescriptionItem().getItemId();
                dispensed.merge(itemId, handover.getQuantityDispensed(), Integer::sum);

                if (invoiced) {
                    alreadyInvoiced.merge(itemId, handover.getQuantityDispensed(), Integer::sum);
                    log.info("Dispense event billed separately, excluded from consultation invoice "
                                    + "| dispenseId={} | itemId={}",
                            event.getDispenseId(), itemId);
                }
            }
        }

        return new DispensedTotals(dispensed, alreadyInvoiced);
    }

    private InvoiceItem newLine(Invoice invoice, Medicine medicine, String description,
                                int quantity, BigDecimal unitPrice) {

        InvoiceItem line = new InvoiceItem();
        line.setInvoice(invoice);
        line.setMedicine(medicine);
        line.setDescription(description);
        line.setQuantity(quantity);
        line.setUnitPrice(money(unitPrice));
        line.setLineTotal(money(unitPrice.multiply(BigDecimal.valueOf(quantity))));
        return line;
    }

    private BigDecimal sumOfLines(Invoice invoice) {
        return money(invoice.getItems().stream()
                .map(InvoiceItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * Writes the invoice with a unique placeholder number: the real number is
     * derived from the generated id, so it can only be set after the row
     * exists. A racing duplicate (unique appointment/dispense guard) surfaces
     * here as a 409.
     */
    private Invoice persistInvoice(Invoice invoice, String conflictMessage) {
        try {
            invoice.setInvoiceNumber(temporaryInvoiceNumber());
            return invoiceRepository.saveAndFlush(invoice);
        } catch (DataIntegrityViolationException exception) {
            log.warn("Invoice generation rejected | source={} | reason=ALREADY_INVOICED",
                    invoice.getAppointment() != null
                            ? "appointment:" + invoice.getAppointment().getAppointmentId()
                            : "dispense:" + invoice.getDispense().getDispenseId());
            throw new ConflictException(conflictMessage);
        }
    }

    /** Unique, system-generated: INV-<year>-<invoice id padded to 6 digits>. */
    private String invoiceNumber(Long invoiceId) {
        return String.format("INV-%d-%06d", Year.now().getValue(), invoiceId);
    }

    /** Unique placeholder that satisfies the unique index until the id exists. */
    private String temporaryInvoiceNumber() {
        return "TMP-" + UUID.randomUUID();
    }

    /** All money is stored and compared with 2-decimal HALF_UP precision. */
    private BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    private InvoiceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return InvoiceStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("Invalid status filter: '" + status
                    + "'. Allowed values: UNPAID, PAID");
        }
    }

    private Invoice findInvoiceByIdInternal(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invoice not found with id: " + id));
    }

    /** The caller is always an authenticated Admin/Super Admin (SDS Table 9). */
    private User resolveAuthenticatedUser() {
        Long userId = SecurityUtil.getCurrentUserId().orElse(null);

        Optional<User> user = userId != null
                ? userRepository.findById(userId)
                : SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername);

        return user.orElseThrow(() -> new BusinessRuleException(
                "Authenticated user could not be resolved to a system user"));
    }
}

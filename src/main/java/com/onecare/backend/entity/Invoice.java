package com.onecare.backend.entity;

import com.onecare.backend.enums.InvoiceStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Financial record of a billed consultation and its dispensed items, or of a
 * single external dispensing event (DDP-23).
 *
 * Exactly one source per invoice: appointment (consultation + prescription
 * lines) XOR dispense (external/OTC medicines only). The patient is optional
 * because a walk-in OTC sale has none.
 *
 * Immutable in spirit: lines are snapshotted at generation time (unit price is
 * stored on the item, never re-read on view), status is
 * derived from the payments, and the outstanding balance is always
 * total - amount_paid. Optimistic locking ({@code @Version}) protects concurrent
 * cash payments so an invoice can never be overpaid.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_invoices_number", columnNames = "invoice_number"),
                @UniqueConstraint(name = "uq_invoices_appointment", columnNames = "appointment_id"),
                @UniqueConstraint(name = "uq_invoices_dispense", columnNames = "dispense_id")
        })
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long invoiceId;

    /** Unique, system-generated (e.g. INV-2026-000123). Never client-supplied. */
    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    /** Null only for unknown walk-ins (OTC external sales). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    /** Set XOR dispense: billed consultation (FR035). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    /** Set XOR appointment: billed external dispensing event (one per event). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispense_id")
    private ExternalDispensing dispense;

    /** Derived - never set by a client. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status;

    /** Sum of the line totals, 2-decimal HALF_UP precision. */
    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    /** Sum of the recorded payments. Outstanding balance = total - amountPaid. */
    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountPaid;

    /** The Admin/Super Admin that generated the invoice. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Optimistic lock (api-standards 942): conflicting concurrent payments -> 409. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Payment> payments = new ArrayList<>();

    /** Derived balance the client is expected to settle. */
    @Transient
    public BigDecimal getOutstandingBalance() {
        return total == null || amountPaid == null
                ? total
                : total.subtract(amountPaid);
    }
}

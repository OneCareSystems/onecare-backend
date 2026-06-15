package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.onecare.backend.enums.PaymentStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Long invoiceId;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name = "prescription_id", nullable = true)
    private  Prescription  prescription;

    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="dispense_id" ,nullable=true)
    private ExternalDispensing externalDispensing;

    @ManyToOne
    @JoinColumn(name = "billed_by", nullable = false)
    private User billedBy;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Transient
    public boolean isExternalCustomer(){
        return this.externalDispensing!=null;
    }

    @Transient
    public boolean isInternalPatient(){
        return this.prescription!=null;
    }

    @PrePersist  
    @PreUpdate 
    private void validateSource() {
    boolean hasPrescription = (prescription != null);
    boolean hasDispense     = (externalDispensing != null);

    if (hasPrescription == hasDispense) {
        throw new IllegalStateException("Invoice must link to ONE source only...");
    }
}
  
}
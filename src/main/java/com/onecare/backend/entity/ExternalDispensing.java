package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.onecare.backend.enums.DeliveryMethod;
import com.onecare.backend.enums.Status;
import java.time.LocalDateTime;
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "external_dispensing")
public class ExternalDispensing {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column( name="dispense_id")
    private Long dispenseId;

    @ManyToOne
    @JoinColumn(name="prescription_id",nullable = false)
    private Prescription  prescription;

    @Column(name = "verification_method", nullable = false, length = 100)
    private String verificationMethod;

   @Column(name = "dispensed_at", nullable = false)
    private LocalDateTime dispensedAt;

   @Column(name = "patient_id", nullable = false, length = 10)
     private String patientId;

   @Column(name = "dispense_date", nullable = false)
     private LocalDateTime dispenseDate;

   @Column(name = "quantity_dispensed", nullable = false)
     private Integer quantityDispensed;

   @Enumerated(EnumType.STRING)
     @Column(name = "status", nullable = false)
     private Status status;

   @Enumerated(EnumType.STRING)
      @Column(name = "delivery_method", nullable = false)
      private DeliveryMethod deliveryMethod;

}

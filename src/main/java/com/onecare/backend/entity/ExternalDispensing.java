package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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

}

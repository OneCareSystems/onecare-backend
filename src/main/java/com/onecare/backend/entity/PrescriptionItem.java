package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.onecare.backend.enums.PrescriptionItemType;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "prescription_items")
public class PrescriptionItem{

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne
    @JoinColumn(name = "prescription_id", nullable = false)
    private Prescription prescription;

    @ManyToOne(optional = true)
    @JoinColumn(name = "medicine_id")            // NULL for EXTERNAL_PURCHASE
    private Medicine medicine;

    @Column(name = "medicine_name", length = 150) // free text for EXTERNAL_PURCHASE
    private String medicineName;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private PrescriptionItemType itemType;

    @Column(name = "dosage", nullable = false, length = 50)
    private String dosage;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "frequency", nullable = false, length = 100)
    private String frequency;


    @Column(name = "duration_days", nullable = false)
    private Integer durationDays;

    @Column(name = "instruction", length = 255)
    private String instruction;
}

package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One medicine handed over in one external-dispensing event: the product of
 * an OTC sale, or the prescribed item handed to a patient, plus the number
 * of units that left the pharmacy. One event may carry many of these.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dispensing_items")
public class DispensingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dispensing_item_id")
    private Long dispensingItemId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "dispense_id", nullable = false)
    private ExternalDispensing dispensing;

    @ManyToOne
    @JoinColumn(name = "prescription_item_id")   // NULL: OTC product
    private PrescriptionItem prescriptionItem;

    @ManyToOne
    @JoinColumn(name = "medicine_id")            // NULL: EXTERNAL_PURCHASE item
    private Medicine medicine;

    @Column(name = "quantity_dispensed", nullable = false)
    private int quantityDispensed;
}

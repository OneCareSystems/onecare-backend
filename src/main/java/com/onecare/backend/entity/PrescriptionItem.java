package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @ManyToOne
    @JoinColumn(name = "medicine_id", nullable = false)
    private  Medicine medicine;

    @Column(name = "dosage", nullable = false, length = 50)
    private String dosage;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "frequency", nullable = false, length = 100)
    private String frequency;


    @Column(name = "duration", nullable = false, length = 50)
    private String duration;

    @Column(name = "instruction", length = 255)
    private String instruction;
}
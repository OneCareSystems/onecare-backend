package com.onecare.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.onecare.backend.enums.Gender;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "patients")
public class Patient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name="patient_id")
    private Long patientId;

    @Column(name = "full_name", nullable = false, length = 100)
    private String full_name;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;
    
    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "contact_no", nullable = false, length = 20)
    private String contactNo;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name="blood_group" , length=10)
    private String bloodGroup;

    @Column(name="created_at",nullable=false ,updatable=false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name="gender",length=10)
    private Gender gender;

    @PrePersist
    protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
    updatedAt = LocalDateTime.now();
    }

}


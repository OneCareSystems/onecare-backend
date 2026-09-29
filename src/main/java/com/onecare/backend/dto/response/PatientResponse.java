package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Patient;
import com.onecare.backend.enums.Gender;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PatientResponse (
    long patientId,String fullName,LocalDate dateOfBirth, String address, String contactNo, String email,String bloodGroup,Gender gender,Boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt, Boolean duplicateSuspected
) {
    public static PatientResponse from(Patient patient, boolean duplicateSuspected) {
        return new PatientResponse(
                patient.getPatientId(), patient.getFullName(),
                patient.getDateOfBirth(), patient.getAddress(), patient.getContactNo(),
                patient.getEmail(), patient.getBloodGroup(), patient.getGender(),
                patient.getIsActive(), patient.getCreatedAt(), patient.getUpdatedAt(),
                duplicateSuspected
        );
    }
    public static PatientResponse from(Patient patient) {
        return from(patient, false);
    }
}
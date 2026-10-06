package com.onecare.backend.dto.request;

import com.onecare.backend.enums.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record PatientCreateRequest (
    @NotBlank @Size(max = 100) String full_name,
    @NotNull LocalDate dateOfBirth,
    @Size(max = 255) String address,
    @NotBlank @Size(max = 20) String contactNo,
    @Email @Size(max = 100) String email,
    @Size(max = 10) String bloodGroup,
    Gender gender
){

}

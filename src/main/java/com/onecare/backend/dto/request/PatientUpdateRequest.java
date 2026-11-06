package com.onecare.backend.dto.request;

import com.onecare.backend.enums.Gender;
import jakarta.validation.constraints.Email;

public record PatientUpdateRequest (
    String full_name,
    String address,
    String contactNo,
    @Email String email,
    String bloodGroup,
    Gender gender
){

}

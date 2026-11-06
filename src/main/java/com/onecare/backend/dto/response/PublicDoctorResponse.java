package com.onecare.backend.dto.response;

import com.onecare.backend.entity.User;

/**
 * Minimal doctor identity exposed on the unauthenticated booking surface:
 * only the id the patient POSTs back and the display name they pick from.
 * Deliberately excludes every other staff field (email, account state,
 * login metadata) — this DTO leaves the public API without auth.
 */
public record PublicDoctorResponse(Long doctorId, String displayName) {

    public static PublicDoctorResponse from(User doctor) {
        if (doctor == null) {
            return null;
        }
        return new PublicDoctorResponse(doctor.getUserId(), doctor.getUsername());
    }
}

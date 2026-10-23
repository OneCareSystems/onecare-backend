package com.onecare.backend.repository;

import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC1 unit-level proof: the doctor_id = self scoping of appointments and
 * prescriptions happens in the repository query itself (derived Spring Data
 * queries on doctor.user_id), never by filtering a full fetch afterwards.
 */
@DataJpaTest
class DoctorScopedRepositoryQueryTest {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Test
    void appointmentQueries_selectOnlyDoctorsOwnRows() {
        User doctorA = createDoctor("repo-doctor-a");
        User doctorB = createDoctor("repo-doctor-b");
        Patient patient = createPatient();

        Appointment own = seedAppointment(doctorA, patient, LocalTime.of(16, 0));
        seedAppointment(doctorA, patient, LocalTime.of(17, 0));
        seedAppointment(doctorB, patient, LocalTime.of(18, 0));

        List<Appointment> scoped = appointmentRepository.findByDoctor_UserId(doctorA.getUserId());

        assertThat(scoped).hasSize(2)
                .allSatisfy(appointment -> assertThat(appointment.getDoctor().getUserId())
                        .isEqualTo(doctorA.getUserId()))
                .extracting(Appointment::getAppointmentId)
                .contains(own.getAppointmentId());
    }

    @Test
    void prescriptionQueries_selectOnlyDoctorsOwnRows() {
        User doctorA = createDoctor("repo-doc-a2");
        User doctorB = createDoctor("repo-doc-b2");
        Patient patient = createPatient();

        Appointment appointmentA = seedAppointment(doctorA, patient, LocalTime.of(16, 0));
        Appointment appointmentB = seedAppointment(doctorB, patient, LocalTime.of(16, 30));

        Prescription ownIssued = seedPrescription(doctorA, patient, appointmentA,
                PrescriptionStatus.ISSUED, LocalDate.now());
        seedPrescription(doctorA, patient, appointmentA,
                PrescriptionStatus.DISPENSED, LocalDate.now());
        seedPrescription(doctorA, patient, appointmentA,
                PrescriptionStatus.CANCELLED, LocalDate.now().minusDays(1));
        seedPrescription(doctorB, patient, appointmentB,
                PrescriptionStatus.ISSUED, LocalDate.now());

        // Plain doctor_id scope
        List<Prescription> scoped = prescriptionRepository.findByDoctor_UserId(doctorA.getUserId());
        assertThat(scoped).hasSize(3)
                .allSatisfy(prescription -> assertThat(prescription.getDoctor().getUserId())
                        .isEqualTo(doctorA.getUserId()));

        // doctor_id + status
        List<Prescription> issued = prescriptionRepository
                .findByDoctor_UserIdAndStatus(doctorA.getUserId(), PrescriptionStatus.ISSUED);
        assertThat(issued).hasSize(1)
                .extracting(Prescription::getPrescriptionId)
                .containsExactly(ownIssued.getPrescriptionId());

        // doctor_id + today's date + not cancelled (the /today query)
        List<Prescription> today = prescriptionRepository
                .findByDoctor_UserIdAndDateAndStatusNot(doctorA.getUserId(), LocalDate.now(),
                        PrescriptionStatus.CANCELLED);
        assertThat(today).hasSize(2)
                .allSatisfy(prescription -> assertThat(prescription.getDoctor().getUserId())
                        .isEqualTo(doctorA.getUserId()));
    }

    // ------------------------------------------------------------------

    private User createDoctor(String username) {
        User doctor = new User();
        doctor.setUsername(username);
        doctor.setEmail(username + "@onecare.com");
        doctor.setPasswordHash("hash");
        doctor.setRole(Role.DOCTOR);
        doctor.setIsActive(true);
        doctor.setFailedAttempts(0);
        return userRepository.save(doctor);
    }

    private Patient createPatient() {
        Patient patient = new Patient();
        patient.setFullName("Repo Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0770001111");
        patient.setEmail("repo-patient@onecare.com");
        patient.setGender(Gender.FEMALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private Appointment seedAppointment(User doctor, Patient patient, LocalTime slot) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(slot);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setReason("Repo scoping probe");
        return appointmentRepository.save(appointment);
    }

    private Prescription seedPrescription(User doctor, Patient patient, Appointment appointment,
            PrescriptionStatus status, LocalDate date) {
        Prescription prescription = new Prescription();
        prescription.setDoctor(doctor);
        prescription.setPatient(patient);
        prescription.setAppointment(appointment);
        prescription.setDate(date);
        prescription.setStatus(status);
        return prescriptionRepository.save(prescription);
    }
}

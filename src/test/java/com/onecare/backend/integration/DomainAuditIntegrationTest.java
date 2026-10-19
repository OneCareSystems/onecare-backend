package com.onecare.backend.integration;

import com.onecare.backend.dto.request.MedicineRequest;
import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.response.MedicineResponse;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.MedicineRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.service.MedicineService;
import com.onecare.backend.service.PatientService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FR041 - domain mutations must produce audit trail entries (AC2): success
 * records the plain action type, failures record the {@code _FAILED} variant,
 * and {@code entityType} carries the module used by the module filter.
 */
@SpringBootTest
class DomainAuditIntegrationTest {

    @Autowired
    private PatientService patientService;

    @Autowired
    private MedicineService medicineService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private MedicineRepository medicineRepository;

    private PatientResponse createdPatient;
    private MedicineResponse createdMedicine;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        if (createdMedicine != null) {
            medicineRepository.findById(createdMedicine.medicineId())
                    .ifPresent(medicineRepository::delete);
            createdMedicine = null;
        }
        if (createdPatient != null) {
            patientRepository.findById(createdPatient.patientId())
                    .ifPresent(patientRepository::delete);
            createdPatient = null;
        }
        auditLogRepository.deleteAll();
    }

    private List<AuditLog> rows(String actionType) {
        return auditLogRepository.findAll().stream()
                .filter(row -> actionType.equals(row.getActionType()))
                .toList();
    }

    @Test
    void patientCreate_isAuditedWithEntityIdAndModule() {
        createdPatient = patientService.createPatient(new PatientCreateRequest(
                "Audit Trail",
                LocalDate.of(1990, 1, 1),
                "1 Test Street",
                "0900000001",
                "audit-trail@example.com",
                "O+",
                Gender.OTHER
        ));

        List<AuditLog> creates = rows("PATIENT_CREATE");
        assertThat(creates).hasSize(1);

        AuditLog row = creates.get(0);
        assertThat(row.getEntityType()).isEqualTo("PATIENT");
        assertThat(row.getEntityId()).isEqualTo(String.valueOf(createdPatient.patientId()));
        assertThat(row.getDescription())
                .contains("outcome=SUCCESS")
                .contains("createPatient");
    }

    @Test
    void medicineStockUpdate_isAuditedWithEntityIdAndModule() {
        createdMedicine = medicineService.createMedicine(new MedicineRequest(
                "Audit Medicine",
                "audit-generic",
                "Analgesic",
                "box",
                new BigDecimal("9.99"),
                LocalDate.now().plusYears(1),
                5,
                false
        ));

        medicineService.updateStock(createdMedicine.medicineId(), new StockUpdateRequest(10));

        List<AuditLog> stockUpdates = rows("MEDICINE_STOCK_UPDATE");
        assertThat(stockUpdates).hasSize(1);

        AuditLog row = stockUpdates.get(0);
        assertThat(row.getEntityType()).isEqualTo("MEDICINE");
        assertThat(row.getEntityId()).isEqualTo(String.valueOf(createdMedicine.medicineId()));
        assertThat(row.getDescription()).contains("outcome=SUCCESS");
    }

    @Test
    void failedDeactivate_isAuditedAsFailedActionType() {
        assertThrows(Exception.class, () -> patientService.deactivatePatient(999_999L));

        List<AuditLog> failures = rows("PATIENT_DEACTIVATE_FAILED");
        assertThat(failures).hasSize(1);

        AuditLog row = failures.get(0);
        assertThat(row.getEntityType()).isEqualTo("PATIENT");
        assertThat(row.getEntityId()).isEqualTo("999999");
        assertThat(row.getDescription()).contains("outcome=FAILURE");
    }
}

insert into users
(user_id,username, email, password_hash, role, is_active, failed_attempts, created_at, updated_at)
VALUES 
    (1, 'superadmin', 'superadmin@onecare.test',
     '$argon2id$v=19$m=65536,t=2,p=1$jlsjtJFalPPdu3LS0fRTyA$6S/XFulvNuWNoqpIEX2+uHDESgQ1s7XfUSh5BX3yLo0',
     'SUPER_ADMIN', TRUE, 0, NOW(), NOW()),
    (2, 'admin', 'admin@onecare.test',
     '$argon2id$v=19$m=65536,t=2,p=1$jlsjtJFalPPdu3LS0fRTyA$6S/XFulvNuWNoqpIEX2+uHDESgQ1s7XfUSh5BX3yLo0',
     'ADMIN', TRUE, 0, NOW(), NOW()),
     (3, 'Dr.Kuruparan', 'kuruparanv@gmail.com',
     '$argon2id$v=19$m=65536,t=2,p=1$jlsjtJFalPPdu3LS0fRTyA$6S/XFulvNuWNoqpIEX2+uHDESgQ1s7XfUSh5BX3yLo0',
     'DOCTOR', TRUE, 0, NOW(), NOW()),

    (4, 'pharmacist1', 'pharmacist1@onecare.test',
     '$argon2id$v=19$m=65536,t=2,p=1$jlsjtJFalPPdu3LS0fRTyA$6S/XFulvNuWNoqpIEX2+uHDESgQ1s7XfUSh5BX3yLo0',
     'PHARMACIST', TRUE, 0, NOW(), NOW());

-- ---------------------------------------------------------------------
-- 2. PATIENTS  (AC3) — clearly fictional, no real PII
-- ---------------------------------------------------------------------
INSERT INTO patients
    (patient_id, full_name, date_of_birth, address, contact_no, email, blood_group, gender, created_at, updated_at)
VALUES
    (1, 'Kasun Fernando', '1990-05-14', '12 Lake Road, Colombo', '0771234567', 'kasun.f@example.test', 'O+', 'MALE', NOW(), NOW()),
    (2, 'Nimasha Perera', '1985-11-02', '45 Temple Lane, Kandy', '0779876543', 'nimasha.p@example.test', 'A+', 'FEMALE', NOW(), NOW()),
    (3, 'Ruwan Silva', '2001-02-20', '78 Galle Road, Galle', '0712223344', 'ruwan.s@example.test', 'B+', 'MALE', NOW(), NOW());
-- ---------------------------------------------------------------------
-- 3. MEDICINES  (AC4)
-- ---------------------------------------------------------------------
INSERT INTO medicines
    (medicine_id, name, generic_name, category, unit, price, unit_price, stock_quantity, reorder_level, expiry_date, is_quarantined, created_at, updated_at)
VALUES
    (1, 'Paracetamol 500mg', 'Paracetamol', 'Analgesic', 'Tablet', 5.00, 5.00, 500, 50, '2027-06-30', FALSE, NOW(), NOW()),
    (2, 'Amoxicillin 250mg', 'Amoxicillin', 'Antibiotic', 'Capsule', 12.50, 12.50, 300, 40, '2027-01-15', FALSE, NOW(), NOW()),
    (3, 'Ibuprofen 400mg', 'Ibuprofen', 'NSAID', 'Tablet', 8.00, 8.00, 200, 30, '2026-12-01', FALSE, NOW(), NOW()),
    (4, 'Metformin 1000mg', 'Metformin', 'Antidiabetic', 'Tablet', 15.00, 15.00, 150, 25, '2027-03-10', FALSE, NOW(), NOW()),
    (5, 'Atorvastatin 20mg', 'Atorvastatin', 'Statin', 'Tablet', 20.00, 20.00, 100, 20, '2026-11-20', FALSE, NOW(), NOW());

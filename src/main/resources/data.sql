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
    (1, 'Kasun Fernando', '1990-05-14', '12 Lake Road, Colombo', '+94771234567', 'kasun.f@example.test', 'O+', 'MALE', NOW(), NOW()),
    (2, 'Nimasha Perera', '1985-11-02', '45 Temple Lane, Kandy', '+94779876543', 'nimasha.p@example.test', 'A+', 'FEMALE', NOW(), NOW()),
    (3, 'Ruwan Silva', '2001-02-20', '78 Galle Road, Galle', '+94712223344', 'ruwan.s@example.test', 'B+', 'MALE', NOW(), NOW());
-- ---------------------------------------------------------------------
-- 3. MEDICINES  (AC4)
-- ---------------------------------------------------------------------
INSERT INTO medicines
    (medicine_id, name, generic_name, category, unit, price, stock_quantity, reorder_level, expiry_date, is_quarantined, created_at, updated_at)
VALUES
    (1, 'Paracetamol 500mg', 'Paracetamol', 'Analgesic', 'Tablet', 5.00,  500, 50, '2027-06-30', FALSE, NOW(), NOW()),
    (2, 'Amoxicillin 250mg', 'Amoxicillin', 'Antibiotic', 'Capsule', 12.50,  300, 40, '2027-01-15', FALSE, NOW(), NOW()),
    (3, 'Ibuprofen 400mg', 'Ibuprofen', 'NSAID', 'Tablet', 8.00,  200, 30, '2026-12-01', FALSE, NOW(), NOW()),
    (4, 'Metformin 1000mg', 'Metformin', 'Antidiabetic', 'Tablet', 15.00,  150, 25, '2027-03-10', FALSE, NOW(), NOW()),
    (5, 'Atorvastatin 20mg', 'Atorvastatin', 'Statin', 'Tablet', 20.00,  100, 20, '2026-11-20', FALSE, NOW(), NOW());
-- ---------------------------------------------------------------------
-- 4. APPOINTMENTS  (status must match the DDL enum!)
--    DDL uses: ENUM('SCHEDULED','COMPLETED','CANCELLED')
--    1-2 SCHEDULED : POST /api/invoices -> 400 (consultation not completed)
--    3   COMPLETED : partial dispensing -> consultation invoice 577.50
--                    (or 500.00 consultation-only if dispense 1 invoiced first)
--    4   COMPLETED : prescription ISSUED -> deferred collection (500.00 first,
--                    medicines 450.00 later on dispense 4)
--    5   COMPLETED : no prescription -> consultation-only 500.00
--    6   COMPLETED : DISPENSED prescription with NO dispensing rows -> fallback
--                    bills full prescribed quantities (598.00)
--    7   COMPLETED : already invoiced (invoice 1) -> POST -> 409, PUT/pay here
--    8   COMPLETED : already invoiced & PAID (invoice 2) -> 409, immutability
-- ---------------------------------------------------------------------
-- ---------------------------------------------------------------------
-- 4. APPOINTMENTS
-- ---------------------------------------------------------------------
INSERT INTO appointments
    (appointment_id, patient_id, doctor_id, appointment_date,
     time_slot, status, created_at, updated_at, reason, clinical_notes)
VALUES
    (1, 1, 3, '2026-10-02', '09:00:00', 'SCHEDULED',
     NOW(), NOW(), 'Routine medical consultation', NULL),
    (2, 2, 3, '2026-10-03', '10:30:00', 'SCHEDULED',
     NOW(), NOW(), 'Follow-up consultation', NULL),
    (3, 1, 3, '2026-10-04', '09:00:00', 'COMPLETED',
     NOW(), NOW(), 'Chest pain review', 'Viral fever, supportive care'),
    (4, 2, 3, '2026-10-04', '10:30:00', 'COMPLETED',
     NOW(), NOW(), 'Diabetes follow-up', 'Type-2 diabetes follow-up'),
    (5, 3, 3, '2026-10-05', '09:00:00', 'COMPLETED',
     NOW(), NOW(), 'General check-up, no prescription', NULL),
    (6, 3, 3, '2026-10-05', '10:30:00', 'COMPLETED',
     NOW(), NOW(), 'Post-op pain review', 'Post-op pain management'),
    (7, 1, 3, '2026-10-01', '11:00:00', 'COMPLETED',
     NOW(), NOW(), 'Already invoiced (UNPAID)', NULL),
    (8, 2, 3, '2026-10-01', '12:00:00', 'COMPLETED',
     NOW(), NOW(), 'Already invoiced (PAID)', NULL),

    (9, 1, 3, CURDATE(), '09:00:00', 'SCHEDULED',
     NOW(), NOW(), 'Routine medical consultation', NULL),
    (10, 2, 3, CURDATE(), '10:00:00', 'SCHEDULED',
     NOW(), NOW(), 'Follow-up consultation', NULL),
    (11, 3, 3, CURDATE(), '11:00:00', 'COMPLETED',
     NOW(), NOW(), 'Chest pain review', NULL);

-- ---------------------------------------------------------------------
-- 5. PRESCRIPTIONS
--    1 DISPENSED : items 1-3, dispensing event 1 (partial handover)
--    2 ISSUED    : item 4, dispensing event 4 (deferred collection)
--    3 DISPENSED : items 5-6, NO dispensing rows (full-quantity fallback)
--    Clinical notes live on the appointments table, not on prescriptions.
-- ---------------------------------------------------------------------
INSERT INTO prescriptions
    (prescription_id, appointment_id, doctor_id, patient_id, date, status, created_at, updated_at)
VALUES
    (1, 3, 3, 1, '2026-10-04', 'DISPENSED', NOW(), NOW()),
    (2, 4, 3, 2, '2026-10-04', 'ISSUED', NOW(), NOW()),
    (3, 6, 3, 3, '2026-10-05', 'DISPENSED', NOW(), NOW());

-- ---------------------------------------------------------------------
-- 6. PRESCRIPTION_ITEMS
--    Item 3 is EXTERNAL_PURCHASE (medicine_id NULL) - never invoiced
-- ---------------------------------------------------------------------
INSERT INTO prescription_items
    (item_id, prescription_id, medicine_id, medicine_name, item_type, dosage, duration_days, instruction, quantity, frequency)
VALUES
    (1, 1, 1, NULL, 'IN_HOUSE',         '1 tablet',   5, 'After food',   10, 'TDS'),
    (2, 1, 2, NULL, 'IN_HOUSE',         '1 capsule',  5, 'Every 8 hours', 5, 'TDS'),
    (3, 1, NULL, 'Elastic Bandage',     'EXTERNAL_PURCHASE', '1 roll', 1, NULL, 1, 'AS NEEDED'),
    (4, 2, 4, NULL, 'IN_HOUSE',         '1 tablet',  30, 'After meals',  30, 'OD'),
    (5, 3, 1, NULL, 'IN_HOUSE',         '1 tablet',   5, NULL,          10, 'TDS'),
    (6, 3, 3, NULL, 'IN_HOUSE',         '1 tablet',   5, 'After food',   6, 'BD');

-- ---------------------------------------------------------------------
-- 7. EXTERNAL_DISPENSING  (one row = one handover event)
--    1 : prescription 1 - 3 of 10 Paracetamol + 5 of 5 Amoxicillin
--        -> POST dispenseId=1 = 77.50 (3x5.00 + 5x12.50)
--    2 : OTC, no prescription, no patient (unknown walk-in)
--        -> POST dispenseId=2 = 32.00 (4x8.00), patientId null
--    3 : OTC basket, 2 medicines in ONE event
--        -> POST dispenseId=3 = 40.00 (4x5.00 + 1x20.00), one invoice
--    4 : prescription 2 - Metformin collected after consultation was billed
--        -> POST dispenseId=4 = 450.00 (30x15.00)
-- ---------------------------------------------------------------------
INSERT INTO external_dispensing
    (dispense_id, prescription_id, patient_id, verification_method, dispense_date, status, delivery_method, dispensed_at)
VALUES
    (1, 1, 1, 'PRESCRIPTION_SHEET', NOW(), 'DISPENSED', 'PICK_UP', NOW()),
    (2, NULL, NULL, 'COMMON',       NOW(), 'DISPENSED', 'PICK_UP', NOW()),
    (3, NULL, 2, 'COMMON',          NOW(), 'DISPENSED', 'PICK_UP', NOW()),
    (4, 2, 2, 'PRESCRIPTION_SHEET', NOW(), 'DISPENSED', 'PICK_UP', NOW());

-- ---------------------------------------------------------------------
-- 8. DISPENSING_ITEMS  (medicines handed over in each event)
-- ---------------------------------------------------------------------
INSERT INTO dispensing_items
    (dispensing_item_id, dispense_id, prescription_item_id, medicine_id, quantity_dispensed)
VALUES
    (1, 1, 1, 1, 3),   -- Paracetamol: partial, 3 of 10
    (2, 1, 2, 2, 5),   -- Amoxicillin: full
    (3, 2, NULL, 3, 4),   -- OTC single: Ibuprofen x4
    (4, 3, NULL, 1, 4),   -- OTC basket line 1: Paracetamol x4
    (5, 3, NULL, 5, 1),   -- OTC basket line 2: Atorvastatin x1
    (6, 4, 4, 4, 30);  -- Metformin: full, collected later

-- ---------------------------------------------------------------------
-- 9. INVOICES / INVOICE_ITEMS / PAYMENTS  (pre-generated for read/edit/pay)
--    invoice numbers follow INV-<year>-<invoiceId>, same as the API
--    invoice 1 UNPAID 510.00 -> PUT line correction, partial payment,
--                               overpayment -> 400, regenerating appt 7 -> 409
--    invoice 2 PAID   500.00 -> GET with payments, PUT -> 400 (immutable),
--                               payment again -> 400, list ?status=PAID
-- ---------------------------------------------------------------------
INSERT INTO invoices
    (invoice_id, invoice_number, patient_id, appointment_id, dispense_id, status, total, amount_paid, created_by, created_at, version)
VALUES
    (1, 'INV-2026-000001', 1, 7, NULL, 'UNPAID', 510.00,   0.00, 1, NOW(), 0),
    (2, 'INV-2026-000002', 2, 8, NULL, 'PAID',   500.00, 500.00, 1, NOW(), 0);

INSERT INTO invoice_items
    (invoice_item_id, invoice_id, medicine_id, description, quantity, unit_price, line_total)
VALUES
    (1, 1, NULL, 'Consultation charge', 1, 500.00, 500.00),
    (2, 1, 1,    'Paracetamol 500mg',   2,   5.00,  10.00),
    (3, 2, NULL, 'Consultation charge', 1, 500.00, 500.00);

INSERT INTO payments
    (payment_id, invoice_id, amount, payment_method, recorded_by, paid_at)
VALUES
    (1, 2, 500.00, 'CASH', 1, NOW());



-- =====================================================================
-- 10. ADDITIONAL PATIENTS
-- Fictional test data. Existing patient IDs 1-3 are preserved.
-- INSERT IGNORE helps prevent duplicate primary-key inserts on reruns.
-- =====================================================================

INSERT IGNORE INTO patients
    (patient_id, full_name, date_of_birth, address, contact_no, email,
     blood_group, gender, created_at, updated_at)
VALUES
    (4, 'Amaya Jayasinghe', '1995-03-18', '10 Park Road, Colombo',
     '+94700000004', 'amaya.j@example.test', 'A-', 'FEMALE', NOW(), NOW()),
    (5, 'Tharindu Wijesinghe', '1988-07-22', '22 Hill Street, Kandy',
     '+94700000005', 'tharindu.w@example.test', 'B+', 'MALE', NOW(), NOW()),
    (6, 'Ishara Gunawardena', '1978-09-11', '15 Beach Road, Galle',
     '+94700000006', 'ishara.g@example.test', 'O-', 'FEMALE', NOW(), NOW()),
    (7, 'Nethmi Bandara', '2003-12-05', '8 Garden Lane, Kurunegala',
     '+94700000007', 'nethmi.b@example.test', 'AB+', 'FEMALE', NOW(), NOW()),
    (8, 'Dilan Madushanka', '1969-04-27', '30 Station Road, Matara',
     '+94700000008', 'dilan.m@example.test', 'O+', 'MALE', NOW(), NOW());


-- =====================================================================
-- 11. ADDITIONAL MEDICINES
-- New medicine IDs 6-10; expiry dates are in the future.
-- Prices and stock levels are fictional test values.
-- =====================================================================

INSERT IGNORE INTO medicines
    (medicine_id, name, generic_name, category, unit, price,
     stock_quantity, reorder_level, expiry_date, is_quarantined,
     created_at, updated_at)
VALUES
    (6, 'Cetirizine 10mg', 'Cetirizine', 'Antihistamine',
     'Tablet', 6.00, 250, 30, '2027-08-31', FALSE, NOW(), NOW()),
    (7, 'Omeprazole 20mg', 'Omeprazole', 'Gastrointestinal',
     'Capsule', 10.00, 180, 25, '2027-09-30', FALSE, NOW(), NOW()),
    (8, 'Losartan 50mg', 'Losartan', 'Antihypertensive',
     'Tablet', 14.00, 160, 25, '2027-10-31', FALSE, NOW(), NOW()),
    (9, 'Vitamin C 500mg', 'Ascorbic Acid', 'Vitamin',
     'Tablet', 4.00, 400, 50, '2028-01-31', FALSE, NOW(), NOW()),
    (10, 'Azithromycin 250mg', 'Azithromycin', 'Antibiotic',
     'Tablet', 18.00, 120, 20, '2027-07-31', FALSE, NOW(), NOW());


-- =====================================================================
-- 12. ADDITIONAL APPOINTMENTS
-- Doctor ID 3 already exists.
-- New slots are 30 minutes apart and do not overlap appointments 9-16.
-- Appointments 17-24 are scheduled for today, subject to your
-- application's booking-hour rules.
-- =====================================================================

INSERT IGNORE INTO appointments
    (appointment_id, patient_id, doctor_id, appointment_date,
     time_slot, status, created_at, updated_at, reason, clinical_notes)
VALUES
    (17, 4, 3, CURDATE(), '17:00:00', 'SCHEDULED',
     NOW(), NOW(), 'Seasonal allergy consultation', NULL),

    (18, 5, 3, CURDATE(), '17:30:00', 'COMPLETED',
     NOW(), NOW(), 'Gastric discomfort follow-up',
     'Consultation completed; follow-up advice documented'),

    (19, 6, 3, CURDATE(), '18:00:00', 'COMPLETED',
     NOW(), NOW(), 'Blood pressure review',
     'Blood pressure reviewed; routine follow-up planned'),

    (20, 7, 3, CURDATE(), '18:30:00', 'COMPLETED',
     NOW(), NOW(), 'Vitamin deficiency review',
     'Consultation completed; nutritional advice discussed'),

    (21, 8, 3, CURDATE(), '19:00:00', 'COMPLETED',
     NOW(), NOW(), 'General health review',
     'General assessment completed'),

    (22, 4, 3, CURDATE(), '19:30:00', 'CANCELLED',
     NOW(), NOW(), 'Rescheduled allergy consultation', NULL),

    (23, 5, 3, CURDATE(), '20:00:00', 'COMPLETED',
     NOW(), NOW(), 'Medication follow-up',
     'Follow-up consultation completed'),

    (24, 6, 3, CURDATE(), '20:30:00', 'SCHEDULED',
     NOW(), NOW(), 'Routine follow-up appointment', NULL);


-- =====================================================================
-- 13. ADDITIONAL PRESCRIPTIONS
-- Only completed appointments receive prescriptions.
-- Existing prescriptions 1-3 remain unchanged.
-- =====================================================================

INSERT IGNORE INTO prescriptions
    (prescription_id, appointment_id, doctor_id, patient_id,
     date, status, created_at, updated_at)
VALUES
    (4, 18, 3, 5, CURDATE(), 'DISPENSED', NOW(), NOW()),
    (5, 19, 3, 6, CURDATE(), 'ISSUED', NOW(), NOW()),
    (6, 20, 3, 7, CURDATE(), 'DISPENSED', NOW(), NOW()),
    (7, 21, 3, 8, CURDATE(), 'ISSUED', NOW(), NOW()),
    (8, 23, 3, 5, CURDATE(), 'DISPENSED', NOW(), NOW()),
    (9, 18, 3, 5, CURDATE(), 'ISSUED', NOW(), NOW());


-- =====================================================================
-- 14. ADDITIONAL PRESCRIPTION ITEMS
-- Medicine IDs 6-10 refer to the medicines inserted above.
-- Prescription 9 intentionally represents a second history entry for
-- the same appointment; remove it if your schema allows only one
-- prescription per appointment.
-- =====================================================================

INSERT IGNORE INTO prescription_items
    (item_id, prescription_id, medicine_id, medicine_name, item_type,
     dosage, duration_days, instruction, quantity, frequency)
VALUES
    (7, 4, 7, NULL, 'IN_HOUSE',
     '1 capsule', 7, 'Follow medical instructions', 7, 'OD'),

    (8, 4, 6, NULL, 'IN_HOUSE',
     '1 tablet', 5, 'Follow medical instructions', 5, 'OD'),

    (9, 5, 8, NULL, 'IN_HOUSE',
     'As prescribed', 30, 'Follow medical instructions', 30, 'OD'),

    (10, 6, 9, NULL, 'IN_HOUSE',
     'As prescribed', 10, 'Follow medical instructions', 10, 'OD'),

    (11, 7, 8, NULL, 'IN_HOUSE',
     'As prescribed', 30, 'Follow medical instructions', 30, 'OD'),

    (12, 8, 6, NULL, 'IN_HOUSE',
     'As prescribed', 5, 'Follow medical instructions', 5, 'OD');

-- =====================================================================
-- 15. ADDITIONAL DISPENSING HISTORY
-- Existing dispense IDs 1-4 are preserved.
-- =====================================================================

INSERT IGNORE INTO external_dispensing
    (dispense_id, prescription_id, patient_id, verification_method,
     dispense_date, status, delivery_method, dispensed_at)
VALUES
    (5, 4, 5, 'PRESCRIPTION_SHEET', NOW(),
     'DISPENSED', 'PICK_UP', NOW()),

    (6, 6, 7, 'PRESCRIPTION_SHEET', NOW(),
     'DISPENSED', 'PICK_UP', NOW()),

    (7, 8, 5, 'PRESCRIPTION_SHEET', NOW(),
     'DISPENSED', 'PICK_UP', NOW());


-- =====================================================================
-- 16. ADDITIONAL DISPENSING ITEMS
-- Prescription item IDs must match the rows above.
-- =====================================================================

INSERT IGNORE INTO dispensing_items
    (dispensing_item_id, dispense_id, prescription_item_id,
     medicine_id, quantity_dispensed)
VALUES
    (7, 5, 7, 7, 7),
    (8, 5, 8, 6, 5),
    (9, 6, 10, 9, 10),
    (10, 7, 12, 6, 5);

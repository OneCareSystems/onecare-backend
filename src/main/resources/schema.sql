-- DROP ORDER: children first, parents last

DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS invoice_items;
DROP TABLE IF EXISTS dispensing_items;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS invoices;
DROP TABLE IF EXISTS external_dispensing;
DROP TABLE IF EXISTS prescription_items;
DROP TABLE IF EXISTS prescriptions;
DROP TABLE IF EXISTS appointments;
DROP TABLE IF EXISTS medicines;
DROP TABLE IF EXISTS patients;
DROP TABLE IF EXISTS password_reset_tokens;
DROP TABLE IF EXISTS users;

-- CREATE ORDER: parents first, children last

-- 1. users
CREATE TABLE users (
    user_id         BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(50)  NOT NULL,
    email           VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    role            ENUM('SUPER_ADMIN','ADMIN','DOCTOR','PHARMACIST') NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login      DATETIME     NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_users       PRIMARY KEY (user_id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_uname UNIQUE (username)
) ENGINE=InnoDB;

-- 1b. password_reset_tokens (needs: users)
CREATE TABLE password_reset_tokens (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  DATETIME    NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at     DATETIME    NULL,

    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT uq_prt_token_hash        UNIQUE (token_hash),
    CONSTRAINT fk_prt_user              FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 2. patients
CREATE TABLE patients (
    patient_id    BIGINT       NOT NULL AUTO_INCREMENT,
    full_name     VARCHAR(500) NOT NULL,
    date_of_birth DATE         NOT NULL,
    address       VARCHAR(255) NULL,
    contact_no    VARCHAR(20)  NOT NULL,
    email         VARCHAR(100) NULL,
    blood_group   VARCHAR(10)  NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    gender        ENUM('MALE','FEMALE') NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_patients PRIMARY KEY (patient_id)
);

-- 3. medicines
CREATE TABLE medicines (
    medicine_id    BIGINT        NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100)  NOT NULL,
    price          DECIMAL(10,2) NOT NULL,
    generic_name   VARCHAR(150)  NULL,
    category       VARCHAR(100)  NOT NULL,
    unit           VARCHAR(50)   NOT NULL,
    reorder_level  INT           NOT NULL,
    is_quarantined BOOLEAN       NOT NULL DEFAULT FALSE,
    stock_quantity INT           NOT NULL DEFAULT 0,
    expiry_date    DATE          NOT NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_medicines PRIMARY KEY (medicine_id),
    CONSTRAINT chk_stock    CHECK (stock_quantity >= 0),
    CONSTRAINT chk_price    CHECK (price > 0)
);

-- 4. appointments (needs: users, patients)
CREATE TABLE appointments (
    appointment_id   BIGINT       NOT NULL AUTO_INCREMENT,
    patient_id       BIGINT       NOT NULL,
    doctor_id        BIGINT       NOT NULL,
    appointment_date DATE         NOT NULL,
    time_slot        TIME         NOT NULL,
    reason           VARCHAR(500) NULL,
    status           ENUM('SCHEDULED','COMPLETED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_appointments PRIMARY KEY (appointment_id),
    CONSTRAINT fk_appt_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id),
    CONSTRAINT fk_appt_doctor  FOREIGN KEY (doctor_id)  REFERENCES users(user_id),
    CONSTRAINT uq_appointments_doctor_date_slot UNIQUE (doctor_id, appointment_date, time_slot)
) ENGINE=InnoDB;

-- 5. prescriptions (needs: appointments, users, patients)
CREATE TABLE prescriptions (
    prescription_id BIGINT   NOT NULL AUTO_INCREMENT,
    appointment_id  BIGINT   NOT NULL,
    doctor_id       BIGINT   NOT NULL,
    patient_id      BIGINT   NOT NULL,
    date            DATE     NOT NULL,
    clinical_notes  VARCHAR(2000) NULL,
    status          ENUM('ISSUED','DISPENSED','CANCELLED') NOT NULL DEFAULT 'ISSUED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_prescriptions     PRIMARY KEY (prescription_id),
    CONSTRAINT fk_presc_appointment FOREIGN KEY (appointment_id) REFERENCES appointments(appointment_id),
    CONSTRAINT fk_presc_doctor      FOREIGN KEY (doctor_id)      REFERENCES users(user_id),
    CONSTRAINT fk_presc_patient     FOREIGN KEY (patient_id)     REFERENCES patients(patient_id)
);

-- 6. prescription_items (needs: prescriptions, medicines)
CREATE TABLE prescription_items (
    item_id         BIGINT       NOT NULL AUTO_INCREMENT,
    prescription_id BIGINT       NOT NULL,
    medicine_id     BIGINT       NULL,             -- NULL for EXTERNAL_PURCHASE
    medicine_name   VARCHAR(150) NULL,             -- free text for EXTERNAL_PURCHASE
    item_type       VARCHAR(20)  NOT NULL DEFAULT 'IN_HOUSE',
    dosage          VARCHAR(50)  NOT NULL,
    duration_days   INT          NOT NULL,
    instruction     VARCHAR(255) NULL,
    quantity        INT          NOT NULL,
    frequency       VARCHAR(100) NOT NULL,

    CONSTRAINT pk_prescription_items PRIMARY KEY (item_id),
    CONSTRAINT fk_item_prescription  FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id),
    CONSTRAINT fk_item_medicine      FOREIGN KEY (medicine_id)     REFERENCES medicines(medicine_id),
    CONSTRAINT chk_item_type_ref CHECK (
        (item_type = 'IN_HOUSE'         AND medicine_id IS NOT NULL AND medicine_name IS NULL)
        OR
        (item_type = 'EXTERNAL_PURCHASE' AND medicine_id IS NULL     AND medicine_name IS NOT NULL)
    )
);

-- 7. external_dispensing (needs: prescriptions, medicines, prescription_items)
CREATE TABLE external_dispensing (
    dispense_id         BIGINT       NOT NULL AUTO_INCREMENT,
    prescription_id     BIGINT  NULL,             -- NULL: OTC / no prescription sheet
    patient_id          BIGINT  NULL,
    verification_method VARCHAR(100) NOT NULL,
    dispense_date       DATETIME     NOT NULL,
    status              ENUM('PENDING','DISPENSED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    delivery_method     ENUM('PICK_UP','DELIVERY')              NOT NULL,
    dispensed_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_external_dispensing   PRIMARY KEY (dispense_id),
    CONSTRAINT fk_ext_disp_prescription FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id)
);

-- 7b. dispensing_items — one handover event may carry many medicines
CREATE TABLE dispensing_items (
    dispensing_item_id   BIGINT NOT NULL AUTO_INCREMENT,
    dispense_id          BIGINT NOT NULL,
    prescription_item_id BIGINT NULL,   -- NULL: OTC product
    medicine_id          BIGINT NULL,   -- NULL: EXTERNAL_PURCHASE item (no catalog medicine)
    quantity_dispensed   INT    NOT NULL,

    CONSTRAINT pk_dispensing_items    PRIMARY KEY (dispensing_item_id),
    CONSTRAINT fk_dispitem_dispense   FOREIGN KEY (dispense_id)         REFERENCES external_dispensing(dispense_id),
    CONSTRAINT fk_dispitem_presc_item FOREIGN KEY (prescription_item_id) REFERENCES prescription_items(item_id),
    CONSTRAINT fk_dispitem_medicine   FOREIGN KEY (medicine_id)         REFERENCES medicines(medicine_id),
    CONSTRAINT chk_dispitem_source CHECK (
        (prescription_item_id IS NOT NULL) OR (medicine_id IS NOT NULL)
    )
);

-- 8. invoices (needs: patients, appointments, external_dispensing, users) — DDP-23 / SRS D3
CREATE TABLE invoices (
    invoice_id      BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_number  VARCHAR(50)   NOT NULL,
    patient_id      BIGINT        NULL,             -- NULL: unknown walk-in (OTC)
    appointment_id  BIGINT        NULL,             -- XOR with dispense_id
    dispense_id     BIGINT        NULL,             -- XOR with appointment_id
    status          ENUM('UNPAID','PAID') NOT NULL DEFAULT 'UNPAID',
    total           DECIMAL(12,2) NOT NULL,
    amount_paid     DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    created_by      BIGINT        NOT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_invoices            PRIMARY KEY (invoice_id),
    CONSTRAINT uq_invoices_number     UNIQUE (invoice_number),
    CONSTRAINT uq_invoices_appointment UNIQUE (appointment_id),   -- no double billing (AC7)
    CONSTRAINT uq_invoices_dispense    UNIQUE (dispense_id),      -- no double billing per dispense event
    CONSTRAINT chk_inv_source CHECK (
        (appointment_id IS NOT NULL AND dispense_id IS NULL) OR
        (appointment_id IS NULL AND dispense_id IS NOT NULL)
    ),
    CONSTRAINT fk_inv_patient         FOREIGN KEY (patient_id)     REFERENCES patients(patient_id),
    CONSTRAINT fk_inv_appointment     FOREIGN KEY (appointment_id) REFERENCES appointments(appointment_id),
    CONSTRAINT fk_inv_dispense        FOREIGN KEY (dispense_id)    REFERENCES external_dispensing(dispense_id),
    CONSTRAINT fk_inv_created_by      FOREIGN KEY (created_by)     REFERENCES users(user_id),
    CONSTRAINT chk_inv_amount_paid    CHECK (amount_paid >= 0)
);

-- 8b. invoice_items (needs: invoices, medicines) — quantity x unit_price per line
CREATE TABLE invoice_items (
    invoice_item_id BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id      BIGINT        NOT NULL,
    medicine_id     BIGINT        NULL,             -- NULL for the consultation charge line
    description     VARCHAR(255)  NOT NULL,
    quantity        INT           NOT NULL,
    unit_price      DECIMAL(10,2) NOT NULL,         -- price snapshot, never re-read on view
    line_total      DECIMAL(12,2) NOT NULL,

    CONSTRAINT pk_invoice_items    PRIMARY KEY (invoice_item_id),
    CONSTRAINT fk_ii_invoice       FOREIGN KEY (invoice_id)  REFERENCES invoices(invoice_id),
    CONSTRAINT fk_ii_medicine      FOREIGN KEY (medicine_id) REFERENCES medicines(medicine_id),
    CONSTRAINT chk_ii_quantity     CHECK (quantity > 0)
);

-- 8c. payments (needs: invoices, users) — cash-only, append-only
CREATE TABLE payments (
    payment_id     BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id     BIGINT        NOT NULL,
    amount         DECIMAL(12,2) NOT NULL,
    payment_method VARCHAR(20)   NOT NULL,
    recorded_by    BIGINT        NOT NULL,
    paid_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_payments        PRIMARY KEY (payment_id),
    CONSTRAINT fk_pay_invoice     FOREIGN KEY (invoice_id)    REFERENCES invoices(invoice_id),
    CONSTRAINT fk_pay_recorded_by FOREIGN KEY (recorded_by)   REFERENCES users(user_id),
    CONSTRAINT chk_pay_amount     CHECK (amount > 0)
);

-- 9. audit_log (needs: users) — LAST
CREATE TABLE audit_log (
    log_id      BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    action      VARCHAR(100) NOT NULL,
    timestamp   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address  VARCHAR(45)  NULL,
    action_type VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NULL,
    entity_id   VARCHAR(50)  NULL,
    description TEXT         NULL,

    CONSTRAINT pk_audit_log  PRIMARY KEY (log_id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);
DROP TABLE IF EXISTS external_dispensing;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS prescription_items;
DROP TABLE IF EXISTS invoices;
DROP TABLE IF EXISTS prescriptions;
DROP TABLE IF EXISTS appointments;
DROP TABLE IF EXISTS medicines;
DROP TABLE IF EXISTS patients;
DROP TABLE IF EXISTS users;

CREATE TABLE users(
    user_id       BIGINT          NOT NULL AUTO_INCREMENT,
    username      VARCHAR(50)     NOT NULL,
    email         VARCHAR(100)    NOT NULL,
    password_hash VARCHAR(255)    NOT NULL,
    role       ENUM('SUPER_ADMIN','ADMIN','DOCTOR','PHARMACIST') NOT NULL,
    is_active     BOOLEAN         NOT NULL DEFAULT TRUE,
    last_login     DATETIME        NULL,
    failed_attempts INT           NOT NULL DEFAULT 0,
    locked_until  DATETIME        NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_users        PRIMARY KEY (user_id),
    CONSTRAINT uq_users_email  UNIQUE      (email),
    CONSTRAINT uq_users_uname  UNIQUE      (username)
);

CREATE TABLE patients (
    patient_id    BIGINT          NOT NULL AUTO_INCREMENT,
    full_name          VARCHAR(500)    NOT NULL,
    date_of_birth        DATE            NOT NULL,
    address       VARCHAR(255)    NULL,
    contact_no         VARCHAR(20)     NOT NULL,
    email         VARCHAR(100)    NULL,
    blood_group    VARCHAR(10)     NULL,
    gender     ENUM('MALE','FEMALE') NOT NULL, 
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_patients PRIMARY KEY (patient_id)
);

CREATE TABLE appointments (
    appointment_id BIGINT         NOT NULL AUTO_INCREMENT,
    patient_id     BIGINT         NOT NULL,
    doctor_id      BIGINT         NOT NULL,
    appointment_date DATE         NOT NULL,
    time_slot      TIME           NOT NULL,
    reason         VARCHAR(500)    NULL,
    status         ENUM('SCHEDULED','COMPLETED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
    created_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_appointments  PRIMARY KEY (appointment_id),
    CONSTRAINT fk_appt_patient  FOREIGN KEY (patient_id) REFERENCES patients(patient_id),
    CONSTRAINT fk_appt_doctor   FOREIGN KEY (doctor_id)  REFERENCES users(user_id)
);

CREATE TABLE medicines (
    medicine_id    BIGINT         NOT NULL AUTO_INCREMENT,
    name           VARCHAR(100)   NOT NULL,
    price          DECIMAL(10,2)  NOT NULL,
    generic_name   VARCHAR(150)   NULL,
    category       VARCHAR(100)   NOT NULL,
    unit           VARCHAR(50)   NOT NULL,
    reorder_level     INT        NOT NULL,
    unit_price      DECIMAL(10,2)    NOT NULL,
    is_quarantined   BOOLEAN        NOT NULL DEFAULT FALSE,
    stock_quantity INT            NOT NULL DEFAULT 0,
    expiry_date    DATE           NOT NULL,
    created_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_medicines  PRIMARY KEY (medicine_id),
    CONSTRAINT chk_stock     CHECK (stock_quantity >= 0),
    CONSTRAINT chk_price     CHECK (price > 0)
);

CREATE TABLE prescriptions (
    prescription_id BIGINT        NOT NULL AUTO_INCREMENT,
    appointment_id  BIGINT        NOT NULL,
    doctor_id       BIGINT        NOT NULL,
    patient_id      BIGINT    NOT NULL,
    date            DATE          NOT NULL,
    status          ENUM('PENDING','DISPENSED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
   
    CONSTRAINT pk_prescriptions      PRIMARY KEY (prescription_id),
    CONSTRAINT fk_presc_appointment  FOREIGN KEY (appointment_id) REFERENCES appointments(appointment_id),
    CONSTRAINT fk_presc_doctor       FOREIGN KEY (doctor_id)      REFERENCES users(user_id),
    CONSTRAINT fk_presc_patient       FOREIGN KEY (patient_id)      REFERENCES users(user_id)

);

CREATE TABLE prescription_items (
    item_id         BIGINT        NOT NULL AUTO_INCREMENT,
    prescription_id BIGINT        NOT NULL,
    medicine_id     BIGINT        NOT NULL,
    dosage          VARCHAR(50)   NOT NULL,
    duration        VARCHAR(50)   NOT NULL,
    instruction     VARCHAR(255)  NULL,
    quantity        INT           NOT NULL ,
    frequency      VARCHAR(100)  NOT NULL,

    CONSTRAINT pk_prescription_items  PRIMARY KEY (item_id),
    CONSTRAINT fk_item_prescription   FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id),
    CONSTRAINT fk_item_medicine       FOREIGN KEY (medicine_id)     REFERENCES medicines(medicine_id)
);

CREATE TABLE invoices (
    invoice_id      BIGINT        NOT NULL AUTO_INCREMENT,
    prescription_id BIGINT        NOT NULL,
    billed_by       BIGINT        NOT NULL,
    total_amount    DECIMAL(10,2) NOT NULL,
    payment_status  ENUM('PENDING','PAID','CANCELLED') NOT NULL DEFAULT 'PENDING',
    date            DATE          NOT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_invoices          PRIMARY KEY (invoice_id),
    CONSTRAINT fk_inv_prescription  FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id),
    CONSTRAINT fk_inv_billed_by     FOREIGN KEY (billed_by)       REFERENCES users(user_id)
);

CREATE TABLE audit_log (
    log_id      BIGINT          NOT NULL AUTO_INCREMENT,
    user_id     BIGINT          NOT NULL,
    action      VARCHAR(100)    NOT NULL,
    timestamp   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address  VARCHAR(45)     NULL,
    action_type  VARCHAR(100)    NOT NULL,
    entity_type     VARCHAR(100)     NULL,
    entity_id        VARCHAR(50)     NULL,
    description      TEXT           NULL,

    CONSTRAINT pk_audit_log   PRIMARY KEY (log_id),
    CONSTRAINT fk_audit_user  FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE external_dispensing (
    dispense_id         BIGINT        NOT NULL AUTO_INCREMENT,
    prescription_id     BIGINT        NOT NULL,
    patient_id          VARCHAR(10)  NOT NULL,
    verification_method VARCHAR(100)  NOT NULL,
    dispense_date      DATETIME       NOT NULL,
    quantity_dispensed   INT          NOT NULL,
    status          ENUM('PENDING','DISPENSED','CANCELLED') NOT NULL DEFAULT 'PENDING',
    delivery_method   ENUM('PICK-UP','DELIVERY') NOT NULL,            
    dispensed_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_external_dispensing    PRIMARY KEY (dispense_id),
    CONSTRAINT fk_ext_disp_prescription  FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id)

);
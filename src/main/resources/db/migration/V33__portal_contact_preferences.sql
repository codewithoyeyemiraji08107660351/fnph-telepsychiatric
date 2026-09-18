CREATE TABLE patient_portal_profiles (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(26) UNIQUE,
 created_at DATETIME(6) NOT NULL,
 created_by VARCHAR(100),
 updated_at DATETIME(6),
 updated_by VARCHAR(100),
 patient_id BIGINT NOT NULL UNIQUE,
 phone VARCHAR(30), email VARCHAR(254), location VARCHAR(300),
 CONSTRAINT fk_portal_profiles_patient FOREIGN KEY (patient_id) REFERENCES patients(id)
);

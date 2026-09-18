CREATE TABLE patient_intakes (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 public_id VARCHAR(26) UNIQUE,
 created_at DATETIME(6) NOT NULL,
 created_by VARCHAR(100),
 updated_at DATETIME(6),
 updated_by VARCHAR(100),
 patient_id BIGINT NOT NULL,
 payload TEXT NOT NULL,
 appointment_public_id VARCHAR(26) UNIQUE,
 CONSTRAINT fk_patient_intakes_patient FOREIGN KEY (patient_id) REFERENCES patients(id),
 INDEX idx_patient_intakes_patient (patient_id)
);
ALTER TABLE consent_acceptances ADD COLUMN typed_signature VARCHAR(150),
 ADD COLUMN declarations_json TEXT;

ALTER TABLE patient_verification_requests
    MODIFY COLUMN date_of_birth DATE NULL,
    MODIFY COLUMN phone_number VARCHAR(20) NULL;
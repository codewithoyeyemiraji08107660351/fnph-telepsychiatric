
    create table account_tokens (
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        invalidated_at datetime(6),
        updated_at datetime(6),
        used_at datetime(6),
        user_id bigint not null,
        public_id varchar(26),
        issued_ip varchar(45),
        used_ip varchar(45),
        token_hash varchar(64) not null,
        created_by varchar(100),
        issued_by varchar(100),
        updated_by varchar(100),
        purpose enum ('ACTIVATION','PASSWORD_RESET') not null,
        primary key (id)
    ) engine=InnoDB;

    create table appointment_status_history (
        appointment_id bigint,
        centre_appointment_id bigint,
        changed_at datetime(6) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        public_id varchar(26),
        from_status varchar(30),
        to_status varchar(30) not null,
        changed_by varchar(100),
        created_by varchar(100),
        reason varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table appointments (
        duration_minutes integer not null,
        appointment_date datetime(6) not null,
        approved_at datetime(6),
        cancelled_at datetime(6),
        created_at datetime(6) not null,
        doctor_id bigint,
        held_until datetime(6),
        him_completed_at datetime(6),
        him_officer_id bigint,
        id bigint not null auto_increment,
        join_window_opens_at datetime(6),
        laboratory_technician_id bigint,
        no_show_at datetime(6),
        nurse_id bigint,
        nursing_completed_at datetime(6),
        patient_id bigint not null,
        payment_id bigint,
        pharmacist_id bigint,
        room_id bigint,
        scheduled_end_at datetime(6),
        slot_id bigint,
        updated_at datetime(6),
        version bigint not null,
        wallet_debited_at datetime(6),
        public_id varchar(26),
        reference varchar(50) not null,
        room varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        approved_by varchar(255),
        cancellation_reason TEXT,
        cancelled_by varchar(255),
        join_url varchar(255),
        meeting_id varchar(255),
        no_show_reason TEXT,
        notes TEXT,
        reason TEXT,
        rejected_reason TEXT,
        status enum ('APPROVED','AWAITING_APPROVAL','CANCELLED','COMPLETED','EXPIRED','IN_PROGRESS','NO_SHOW','REJECTED','RESCHEDULED','SLOT_HELD') not null,
        primary key (id)
    ) engine=InnoDB;

    create table attendance_events (
        centre_consultation_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        occurred_at datetime(6) not null,
        modality varchar(20),
        public_id varchar(26),
        created_by varchar(100),
        provider_event_id varchar(150),
        provider_session_id varchar(150),
        details varchar(500),
        event_type enum ('IDENTITY_CONFIRMED','JOINED','LEFT','MODALITY_CHANGED','RECONNECTED','ROOM_CLOSED','TERMINATED','WARNING_SENT') not null,
        participant_role enum ('CENTRE','DOCTOR','PATIENT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table audit_logs (
        is_system bit not null,
        centre_id bigint,
        created_at datetime(6) not null,
        effective_principal_id bigint,
        entity_id bigint,
        id bigint not null auto_increment,
        performed_at datetime(6) not null,
        user_id bigint,
        view_as_session_id bigint,
        outcome varchar(20) not null,
        public_id varchar(26),
        ip_address varchar(45),
        entity_type varchar(50),
        username varchar(50),
        after_hash varchar(64),
        before_hash varchar(64),
        chain_hash varchar(64),
        previous_chain_hash varchar(64),
        action varchar(100) not null,
        created_by varchar(100),
        reason varchar(500),
        details TEXT,
        user_agent TEXT,
        primary key (id)
    ) engine=InnoDB;

    create table cancellation_requests (
        hours_notice integer,
        appointment_id bigint not null,
        created_at datetime(6) not null,
        decided_at datetime(6),
        id bigint not null auto_increment,
        proposed_slot_id bigint,
        requested_at datetime(6) not null,
        updated_at datetime(6),
        request_type varchar(20) not null,
        status varchar(20) not null,
        public_id varchar(26),
        created_by varchar(100),
        decided_by varchar(100),
        requested_by varchar(100) not null,
        updated_by varchar(100),
        decision_notes varchar(500),
        reason varchar(500) not null,
        primary key (id)
    ) engine=InnoDB;

    create table centre_appointments (
        duration_minutes integer not null,
        appointment_date datetime(6) not null,
        approved_at datetime(6),
        centre_id bigint not null,
        centre_patient_id bigint not null,
        created_at datetime(6) not null,
        doctor_id bigint,
        him_id bigint,
        id bigint not null auto_increment,
        join_window_opens_at datetime(6),
        laboratory_id bigint,
        no_show_at datetime(6),
        pharmacy_id bigint,
        referral_id bigint,
        room_id bigint,
        scheduled_end_at datetime(6),
        slot_id bigint,
        updated_at datetime(6),
        version bigint not null,
        wallet_debited_at datetime(6),
        wallet_transaction_id bigint,
        public_id varchar(26),
        reference varchar(50) not null,
        room varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        approved_by varchar(255),
        join_url varchar(255),
        meeting_id varchar(255),
        no_show_reason TEXT,
        postponed_reason TEXT,
        returned_reason TEXT,
        status enum ('APPROVED','AWAITING_APPROVAL','CANCELLED','COMPLETED','EXPIRED','IN_PROGRESS','NO_SHOW','REJECTED','RESCHEDULED','SLOT_HELD') not null,
        primary key (id)
    ) engine=InnoDB;

    create table centre_bundle_receipts (
        bundle_id bigint not null,
        centre_appointment_id bigint not null,
        centre_id bigint not null,
        centre_patient_id bigint not null,
        created_at datetime(6) not null,
        delivered_at datetime(6) not null,
        first_opened_at datetime(6),
        id bigint not null auto_increment,
        treated_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        first_opened_by varchar(100),
        treated_by varchar(100),
        updated_by varchar(100),
        treatment_notes TEXT,
        primary key (id)
    ) engine=InnoDB;

    create table centre_capabilities (
        is_enabled bit not null,
        centre_id bigint not null,
        created_at datetime(6) not null,
        disabled_at datetime(6),
        enabled_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        disabled_by varchar(100),
        enabled_by varchar(100),
        updated_by varchar(100),
        disable_reason varchar(500),
        enable_reason varchar(500),
        capability enum ('HIM','LABORATORY','PHARMACY') not null,
        primary key (id)
    ) engine=InnoDB;

    create table centre_consultation_notes (
        is_signed bit not null,
        centre_consultation_id bigint not null,
        centre_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        signed_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        follow_up_timeline varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        clinical_note LONGTEXT not null,
        follow_up_recommendation TEXT,
        signed_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table centre_consultations (
        connection_issues integer not null,
        has_audio_fallback bit not null,
        identity_confirmed bit not null,
        remaining_seconds integer,
        centre_appointment_id bigint not null,
        centre_id bigint not null,
        centre_patient_id bigint not null,
        created_at datetime(6) not null,
        doctor_id bigint not null,
        ended_at datetime(6),
        id bigint not null auto_increment,
        room_created_at datetime(6),
        room_deleted_at datetime(6),
        room_expires_at datetime(6),
        scheduled_end_at datetime(6),
        scheduled_start_at datetime(6),
        started_at datetime(6),
        terminated_by_id bigint,
        updated_at datetime(6),
        warning_one_sent_at datetime(6),
        warning_two_sent_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        room_provider_id varchar(100),
        updated_by varchar(100),
        room_name varchar(120),
        room_url varchar(300),
        escalation_instruction TEXT,
        safety_action_taken TEXT,
        termination_note TEXT,
        modality enum ('AUDIO','PHONE_FALLBACK','VIDEO') not null,
        outcome enum ('ABANDONED','COMPLETED','NO_SHOW','TERMINATED_EARLY'),
        termination_reason enum ('ABUSE','ACUTE_CLINICAL_UNSUITABILITY','EMERGENCY','FAILED_IDENTITY_VERIFICATION','OTHER','PERSISTENT_DISRUPTION','UNACCEPTABLE_PRIVACY','UNSAFE_CONNECTIVITY'),
        primary key (id)
    ) engine=InnoDB;

    create table centre_patients (
        date_of_birth date not null,
        deleted bit not null,
        is_active bit not null,
        centre_id bigint not null,
        created_at datetime(6) not null,
        deleted_at datetime(6),
        id bigint not null auto_increment,
        updated_at datetime(6),
        gender varchar(10),
        phone_number varchar(20),
        public_id varchar(26),
        centre_patient_id varchar(50) not null,
        first_name varchar(50) not null,
        fnph_ehr_number varchar(50),
        last_name varchar(50) not null,
        middle_name varchar(50),
        created_by varchar(100),
        deleted_by varchar(100),
        email varchar(100),
        updated_by varchar(100),
        deleted_reason varchar(500),
        address varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table centre_referrals (
        centre_id bigint not null,
        centre_patient_id bigint not null,
        consent_accepted_at datetime(6),
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        submitted_at datetime(6),
        updated_at datetime(6),
        consent_version varchar(20),
        public_id varchar(26),
        reference varchar(50) not null,
        created_by varchar(100),
        submitted_by varchar(100),
        updated_by varchar(100),
        consent_accepted_by varchar(150),
        assessment TEXT,
        current_condition TEXT,
        previous_results TEXT,
        referral_reason TEXT not null,
        relevant_medicines TEXT,
        status enum ('COMPLETED','DRAFT','RETURNED','SCHEDULED','SUBMITTED','WITHDRAWN') not null,
        urgency enum ('ROUTINE','SOON') not null,
        primary key (id)
    ) engine=InnoDB;

    create table centre_staff (
        is_active bit not null,
        is_primary bit not null,
        activated_at datetime(6),
        centre_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        user_id bigint not null,
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        activated_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table centre_vitals (
        blood_glucose float(53),
        blood_oxygen integer,
        blood_pressure_diastolic integer,
        blood_pressure_systolic integer,
        bmi float(53),
        heart_rate integer,
        height_cm float(53),
        is_self_reported bit not null,
        respiratory_rate integer,
        temperature float(53),
        weight_kg float(53),
        centre_appointment_id bigint,
        centre_id bigint not null,
        centre_patient_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        measured_at datetime(6) not null,
        updated_at datetime(6),
        public_id varchar(26),
        measurement_source varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        notes TEXT,
        primary key (id)
    ) engine=InnoDB;

    create table centres (
        default_consultation_minutes integer not null,
        deleted bit not null,
        is_active bit not null,
        activated_at datetime(6),
        created_at datetime(6) not null,
        deleted_at datetime(6),
        id bigint not null auto_increment,
        suspended_at datetime(6),
        updated_at datetime(6),
        code varchar(20) not null,
        phone_number varchar(20),
        public_id varchar(26),
        lga varchar(50),
        state varchar(50),
        activated_by varchar(100),
        created_by varchar(100),
        deleted_by varchar(100),
        email varchar(100),
        name varchar(100) not null,
        suspended_by varchar(100),
        updated_by varchar(100),
        deleted_reason varchar(500),
        suspend_reason varchar(500),
        address varchar(255),
        status enum ('ACTIVE','SETUP','SUSPENDED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table configuration_changes (
        changed_at datetime(6) not null,
        configuration_id bigint not null,
        created_at datetime(6) not null,
        effective_from datetime(6) not null,
        id bigint not null auto_increment,
        public_id varchar(26),
        ip_address varchar(45),
        changed_by varchar(100) not null,
        config_key varchar(100) not null,
        created_by varchar(100),
        reason varchar(500) not null,
        new_value varchar(1000) not null,
        previous_value varchar(1000),
        primary key (id)
    ) engine=InnoDB;

    create table connection_quality_events (
        packet_loss_percent decimal(5,2),
        round_trip_ms integer,
        centre_consultation_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        recorded_at datetime(6) not null,
        video_receive_quality varchar(20),
        public_id varchar(26),
        created_by varchar(100),
        participant_role enum ('CENTRE','DOCTOR','PATIENT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table consent_acceptances (
        accepted_at datetime(6) not null,
        centre_id bigint,
        centre_patient_id bigint,
        consent_document_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        patient_id bigint,
        public_id varchar(26),
        consent_version varchar(30) not null,
        ip_address varchar(45),
        created_by varchar(100),
        accepted_by varchar(150) not null,
        witnessed_by varchar(150),
        user_agent varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table consent_documents (
        created_at datetime(6) not null,
        effective_from datetime(6),
        id bigint not null auto_increment,
        retired_at datetime(6),
        updated_at datetime(6),
        audience varchar(20) not null,
        status varchar(20) not null,
        public_id varchar(26),
        version varchar(30) not null,
        created_by varchar(100),
        published_by varchar(100),
        updated_by varchar(100),
        title varchar(200) not null,
        body LONGTEXT not null,
        primary key (id)
    ) engine=InnoDB;

    create table consultation_notes (
        is_authoritative bit not null,
        is_signed bit not null,
        version integer not null,
        consultation_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        signed_at datetime(6),
        superseded_at datetime(6),
        supersedes_id bigint,
        updated_at datetime(6),
        public_id varchar(26),
        follow_up_timeline varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        amendment_reason varchar(500),
        clinical_note LONGTEXT,
        follow_up_recommendation TEXT,
        signed_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table consultations (
        connection_issues integer not null,
        has_audio_fallback bit not null,
        identity_confirmed bit not null,
        recording_consent_given bit not null,
        remaining_seconds integer,
        appointment_id bigint not null,
        created_at datetime(6) not null,
        doctor_id bigint not null,
        doctor_joined_at datetime(6),
        ended_at datetime(6),
        id bigint not null auto_increment,
        patient_id bigint not null,
        patient_joined_at datetime(6),
        room_created_at datetime(6),
        room_deleted_at datetime(6),
        room_expires_at datetime(6),
        scheduled_end_at datetime(6),
        scheduled_start_at datetime(6),
        started_at datetime(6),
        terminated_by_id bigint,
        updated_at datetime(6),
        warning_one_sent_at datetime(6),
        warning_two_sent_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        room_provider_id varchar(100),
        updated_by varchar(100),
        room_name varchar(120),
        room_url varchar(300),
        escalation_instruction TEXT,
        safety_action_taken TEXT,
        termination_note TEXT,
        modality enum ('AUDIO','PHONE_FALLBACK','VIDEO') not null,
        outcome enum ('ABANDONED','COMPLETED','NO_SHOW','TERMINATED_EARLY'),
        termination_reason enum ('ABUSE','ACUTE_CLINICAL_UNSUITABILITY','EMERGENCY','FAILED_IDENTITY_VERIFICATION','OTHER','PERSISTENT_DISRUPTION','UNACCEPTABLE_PRIVACY','UNSAFE_CONNECTIVITY'),
        primary key (id)
    ) engine=InnoDB;

    create table contact_verifications (
        attempts integer not null,
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        invalidated_at datetime(6),
        patient_id bigint,
        released_to_staff_at datetime(6),
        updated_at datetime(6),
        verified_at datetime(6),
        delivery_route varchar(20) not null,
        public_id varchar(26),
        ip_address varchar(45),
        destination_masked varchar(50) not null,
        ehr_number varchar(50) not null,
        code_hash varchar(64) not null,
        created_by varchar(100),
        released_by varchar(100),
        updated_by varchar(100),
        channel enum ('EMAIL','SMS') not null,
        primary key (id)
    ) engine=InnoDB;

    create table doctor_availability (
        is_available bit not null,
        service_date date not null,
        created_at datetime(6) not null,
        doctor_id bigint not null,
        end_at datetime(6) not null,
        id bigint not null auto_increment,
        start_at datetime(6) not null,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        set_by varchar(100),
        updated_by varchar(100),
        reason varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table document_download_events (
        created_at datetime(6) not null,
        downloaded_at datetime(6) not null,
        id bigint not null auto_increment,
        issued_document_id bigint not null,
        public_id varchar(26),
        ip_address varchar(45),
        created_by varchar(100),
        downloaded_by varchar(100),
        user_agent varchar(500),
        outcome enum ('ALLOWED','REFUSED_EXPIRED','REFUSED_LIMIT_REACHED','REFUSED_NOT_OWNER','REFUSED_REVOKED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table document_verifications (
        is_valid bit not null,
        verification_count integer not null,
        created_at datetime(6) not null,
        document_id bigint not null,
        id bigint not null auto_increment,
        issued_document_id bigint,
        last_verified_at datetime(6),
        revoked_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        last_verified_ip varchar(45),
        document_type varchar(50) not null,
        issue_number varchar(50) not null,
        verification_token varchar(64) not null,
        created_by varchar(100),
        updated_by varchar(100),
        revoked_reason varchar(500),
        verification_url varchar(500) not null,
        primary key (id)
    ) engine=InnoDB;

    create table ehr_lookup_attempts (
        attempted_at datetime(6) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        public_id varchar(26),
        ip_address varchar(45),
        ehr_number_attempted varchar(50) not null,
        created_by varchar(100),
        user_agent varchar(500),
        outcome enum ('ALREADY_ENROLLED','CORROBORATION_FAILED','MATCHED','NOT_FOUND','NO_ACTIVE_IMPORT','RATE_LIMITED','RECORD_INACTIVE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table ehr_verification_imports (
        drift_detected_count integer not null,
        rejected_row_count integer not null,
        row_count integer not null,
        source_as_at date not null,
        valid_row_count integer not null,
        activated_at datetime(6),
        created_at datetime(6) not null,
        file_size_bytes bigint not null,
        id bigint not null auto_increment,
        superseded_at datetime(6),
        superseded_by_import_id bigint,
        updated_at datetime(6),
        uploaded_at datetime(6) not null,
        uploaded_by_id bigint,
        public_id varchar(26),
        file_checksum varchar(64) not null,
        activated_by varchar(100),
        created_by varchar(100),
        updated_by varchar(100),
        storage_path varchar(500),
        file_name varchar(255) not null,
        validation_report LONGTEXT,
        status enum ('ACTIVE','REJECTED','SUPERSEDED','UPLOADED','VALIDATED','VALIDATING') not null,
        storage_area enum ('BACKUPS','DOCUMENTS','EHR_IMPORTS','RECORDINGS','UPLOADS'),
        primary key (id)
    ) engine=InnoDB;

    create table ehr_verification_records (
        is_active_record bit not null,
        created_at datetime(6) not null,
        ehr_import_id bigint not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        date_of_birth_masked varchar(20) not null,
        phone_masked varchar(20),
        public_id varchar(26),
        ehr_number varchar(50) not null,
        patient_status varchar(50),
        date_of_birth_hash varchar(64) not null,
        phone_hash varchar(64),
        clinic varchar(100),
        created_by varchar(100),
        updated_by varchar(100),
        full_name varchar(150) not null,
        primary key (id)
    ) engine=InnoDB;

    create table file_uploads (
        deleted bit not null,
        centre_id bigint,
        centre_patient_id bigint,
        created_at datetime(6) not null,
        deleted_at datetime(6),
        file_size bigint not null,
        id bigint not null auto_increment,
        patient_id bigint,
        quarantined_at datetime(6),
        scanned_at datetime(6),
        updated_at datetime(6),
        uploaded_at datetime(6) not null,
        public_id varchar(26),
        reference_id varchar(50),
        checksum varchar(64) not null,
        created_by varchar(100),
        mime_type varchar(100) not null,
        updated_by varchar(100),
        uploaded_by varchar(100),
        quarantine_reason varchar(500),
        storage_path varchar(500) not null,
        deleted_by varchar(255),
        deleted_reason varchar(255),
        description TEXT,
        original_file_name varchar(255) not null,
        scan_result TEXT,
        category enum ('EHR_VERIFICATION_IMPORT','ISSUED_DOCUMENT','LABORATORY_RESULT','OTHER','RECORDING','REFERRAL_ATTACHMENT','SUPPORTING_DOCUMENT','TRANSCRIPT','VITALS_EVIDENCE') not null,
        scan_status enum ('CLEAN','QUARANTINED','REJECTED','SCANNING','UPLOADED') not null,
        storage_area enum ('BACKUPS','DOCUMENTS','EHR_IMPORTS','RECORDINGS','UPLOADS') not null,
        primary key (id)
    ) engine=InnoDB;

    create table follow_ups (
        completed_date date,
        preferred_date date,
        preferred_time time(6),
        scheduled_date date,
        bundle_id bigint,
        centre_consultation_id bigint,
        centre_id bigint,
        centre_patient_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        patient_id bigint,
        updated_at datetime(6),
        public_id varchar(26),
        expected_timeframe varchar(50),
        review_interval varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        notes TEXT,
        recommendation TEXT,
        consultation_mode enum ('AUDIO','PHONE_FALLBACK','VIDEO'),
        status enum ('CANCELLED','COMPLETED','NOT_REQUIRED','RECOMMENDED','SCHEDULED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table investigation_items (
        sequence integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        investigation_id bigint not null,
        updated_at datetime(6),
        public_id varchar(26),
        panel_code varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        panel_name varchar(150) not null,
        notes TEXT,
        primary key (id)
    ) engine=InnoDB;

    create table investigations (
        expiry_date date,
        issue_date date,
        not_required bit not null,
        validity_days integer not null,
        bundle_id bigint,
        centre_consultation_id bigint,
        centre_id bigint,
        centre_patient_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        doctor_id bigint not null,
        id bigint not null auto_increment,
        patient_id bigint,
        supersedes_id bigint,
        updated_at datetime(6),
        public_id varchar(26),
        issue_number varchar(50) not null,
        created_by varchar(100),
        updated_by varchar(100),
        not_required_reason varchar(500),
        clinical_information TEXT,
        status enum ('DRAFT','EXPIRED','NOT_REQUIRED','PENDING_REVIEW','RELEASED','REVIEWED','REVOKED','SUPERSEDED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table issued_documents (
        download_count integer not null,
        is_view_only bit not null,
        max_downloads integer not null,
        bundle_id bigint,
        centre_id bigint,
        centre_patient_id bigint,
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        file_size_bytes bigint,
        id bigint not null auto_increment,
        issued_at datetime(6) not null,
        patient_id bigint,
        revoked_at datetime(6),
        source_id bigint not null,
        superseded_by_id bigint,
        updated_at datetime(6),
        public_id varchar(26),
        issue_number varchar(50) not null,
        file_checksum varchar(64),
        content_type varchar(100),
        created_by varchar(100),
        revoked_by varchar(100),
        updated_by varchar(100),
        revoked_reason varchar(500),
        storage_path varchar(500),
        document_type enum ('CLINICAL_SUMMARY','FOLLOW_UP_RECOMMENDATION','INVESTIGATION_REQUEST','PRESCRIPTION') not null,
        status enum ('ACTIVE','EXPIRED','REVOKED','SUPERSEDED') not null,
        storage_area enum ('BACKUPS','DOCUMENTS','EHR_IMPORTS','RECORDINGS','UPLOADS'),
        primary key (id)
    ) engine=InnoDB;

    create table login_attempts (
        attempted_at datetime(6) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        user_id bigint,
        public_id varchar(26),
        ip_address varchar(45),
        created_by varchar(100),
        username_attempted varchar(150) not null,
        failure_reason varchar(200),
        user_agent varchar(500),
        outcome enum ('ACCOUNT_INACTIVE','ACCOUNT_LOCKED','ACCOUNT_NOT_ACTIVATED','BAD_CREDENTIALS','MFA_FAILED','MFA_REQUIRED','NO_ROLE_ASSIGNED','RATE_LIMITED','SUCCESS','UNKNOWN_USERNAME') not null,
        primary key (id)
    ) engine=InnoDB;

    create table mfa_factors (
        digits integer not null,
        failed_attempts integer not null,
        is_active bit not null,
        period_seconds integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        last_used_at datetime(6),
        locked_until datetime(6),
        updated_at datetime(6),
        user_id bigint not null,
        verified_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        secret_encrypted varchar(512) not null,
        type enum ('EMAIL','SMS','TOTP') not null,
        primary key (id)
    ) engine=InnoDB;

    create table mfa_recovery_codes (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        used_at datetime(6),
        user_id bigint not null,
        public_id varchar(26),
        used_ip varchar(45),
        code_hash varchar(64) not null,
        created_by varchar(100),
        updated_by varchar(100),
        primary key (id)
    ) engine=InnoDB;

    create table notification_broadcasts (
        recipient_count integer not null,
        centre_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        sent_at datetime(6) not null,
        target_role_id bigint,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        sent_by varchar(100) not null,
        updated_by varchar(100),
        subject varchar(200) not null,
        reason varchar(500) not null,
        body TEXT not null,
        primary key (id)
    ) engine=InnoDB;

    create table notification_preferences (
        appointment_reminders bit not null,
        clinical_updates bit not null,
        email_enabled bit not null,
        in_app_enabled bit not null,
        payment_notifications bit not null,
        push_enabled bit not null,
        sms_enabled bit not null,
        system_alerts bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        user_id bigint not null,
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        primary key (id)
    ) engine=InnoDB;

    create table notification_templates (
        contains_clinical_detail bit not null,
        is_active bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        subject varchar(200) not null,
        updated_reason varchar(500),
        body TEXT not null,
        channel enum ('EMAIL','IN_APP','PUSH','SMS') not null,
        notification_type enum ('APPOINTMENT_APPROVED','APPOINTMENT_ASSIGNED','APPOINTMENT_CANCELLED','APPOINTMENT_REASSIGNED','APPOINTMENT_REJECTED','APPOINTMENT_REMINDER','APPOINTMENT_RESCHEDULED','BOOKING_AWAITING_APPROVAL','BOOKING_SUBMITTED','CENTRE_WALLET_ALERT','CONSULTATION_READY','CREDIT_ISSUED','EHR_VERIFICATION_RESULT','FOLLOW_UP_RECOMMENDED','INVESTIGATION_RELEASED','JOIN_WINDOW_OPEN','NO_SHOW_RECORDED','PAYMENT_AMOUNT_MISMATCH','PAYMENT_FAILURE','PAYMENT_SUCCESS','PRESCRIPTION_RELEASED','SLOT_HOLD_EXPIRED','SUPPORT_TICKET_UPDATE','SYSTEM_ALERT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table notifications (
        contains_clinical_detail bit not null,
        max_retries integer not null,
        priority integer not null,
        retry_count integer not null,
        acknowledged_by_id bigint,
        centre_id bigint,
        created_at datetime(6) not null,
        delivered_at datetime(6),
        dismissed_at datetime(6),
        entity_id bigint,
        id bigint not null auto_increment,
        patient_id bigint,
        read_at datetime(6),
        scheduled_for datetime(6),
        sent_at datetime(6),
        target_role_id bigint,
        updated_at datetime(6),
        user_id bigint,
        public_id varchar(26),
        entity_type varchar(50),
        reference_id varchar(50),
        created_by varchar(100),
        template_code varchar(100),
        updated_by varchar(100),
        action_url varchar(300),
        body TEXT not null,
        failure_reason TEXT,
        subject varchar(255) not null,
        channel enum ('EMAIL','IN_APP','PUSH','SMS') not null,
        status enum ('DELIVERED','FAILED','PENDING','READ','SENT') not null,
        type enum ('APPOINTMENT_APPROVED','APPOINTMENT_ASSIGNED','APPOINTMENT_CANCELLED','APPOINTMENT_REASSIGNED','APPOINTMENT_REJECTED','APPOINTMENT_REMINDER','APPOINTMENT_RESCHEDULED','BOOKING_AWAITING_APPROVAL','BOOKING_SUBMITTED','CENTRE_WALLET_ALERT','CONSULTATION_READY','CREDIT_ISSUED','EHR_VERIFICATION_RESULT','FOLLOW_UP_RECOMMENDED','INVESTIGATION_RELEASED','JOIN_WINDOW_OPEN','NO_SHOW_RECORDED','PAYMENT_AMOUNT_MISMATCH','PAYMENT_FAILURE','PAYMENT_SUCCESS','PRESCRIPTION_RELEASED','SLOT_HOLD_EXPIRED','SUPPORT_TICKET_UPDATE','SYSTEM_ALERT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table outbox_events (
        attempts integer not null,
        aggregate_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        next_attempt_at datetime(6),
        published_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        aggregate_type varchar(50) not null,
        event_type varchar(60) not null,
        created_by varchar(100),
        updated_by varchar(100),
        last_error varchar(500),
        payload LONGTEXT,
        primary key (id)
    ) engine=InnoDB;

    create table participant_tokens (
        centre_consultation_id bigint,
        centre_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        issued_at datetime(6) not null,
        not_before datetime(6) not null,
        patient_id bigint,
        revoked_at datetime(6),
        updated_at datetime(6),
        used_at datetime(6),
        user_id bigint,
        public_id varchar(26),
        issued_ip varchar(45),
        token_hash varchar(64) not null,
        created_by varchar(100),
        updated_by varchar(100),
        display_name varchar(120) not null,
        revoked_reason varchar(200),
        participant_role enum ('CENTRE','DOCTOR','PATIENT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table patient_credits (
        amount decimal(19,2) not null,
        balance_after decimal(19,2) not null,
        applied_payment_id bigint,
        appointment_id bigint,
        created_at datetime(6) not null,
        expires_at datetime(6),
        id bigint not null auto_increment,
        patient_id bigint not null,
        source_payment_id bigint,
        public_id varchar(26),
        created_by varchar(100),
        reason varchar(500) not null,
        direction enum ('CREDIT','DEBIT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table patient_verification_requests (
        date_of_birth date not null,
        assigned_to_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        resolved_at datetime(6),
        resulting_patient_id bigint,
        updated_at datetime(6),
        phone_number varchar(20) not null,
        preferred_contact varchar(20) not null,
        public_id varchar(26),
        ip_address varchar(45),
        ehr_number_claimed varchar(50) not null,
        created_by varchar(100),
        email varchar(100),
        resolved_by varchar(100),
        updated_by varchar(100),
        full_name varchar(150) not null,
        resolution_notes TEXT,
        supporting_note TEXT,
        status enum ('REJECTED','RESOLVED','SUBMITTED','WITH_HIM','WITH_ICT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table patients (
        date_of_birth date not null,
        deleted bit not null,
        drift_flagged bit not null,
        is_active bit not null,
        is_eligible bit not null,
        is_physically_assessed bit not null,
        activated_at datetime(6),
        contact_verified_at datetime(6),
        created_at datetime(6) not null,
        deleted_at datetime(6),
        drift_flagged_at datetime(6),
        eligibility_verified_at datetime(6),
        id bigint not null auto_increment,
        source_import_id bigint,
        updated_at datetime(6),
        gender varchar(10),
        phone_number varchar(20),
        public_id varchar(26),
        ehr_number varchar(50) not null,
        email varchar(50),
        first_name varchar(50) not null,
        last_name varchar(50) not null,
        middle_name varchar(50),
        created_by varchar(100),
        deleted_by varchar(100),
        updated_by varchar(100),
        deleted_reason varchar(500),
        address TEXT,
        drift_details TEXT,
        eligibility_verified_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table payments (
        amount decimal(19,2) not null,
        amount_mismatch bit not null,
        credit_applied decimal(19,2) not null,
        currency varchar(3) not null,
        is_manually_reconciled bit not null,
        reported_amount decimal(19,2),
        verification_attempts integer not null,
        appointment_id bigint,
        created_at datetime(6) not null,
        expires_at datetime(6),
        id bigint not null auto_increment,
        initiated_at datetime(6),
        last_verified_at datetime(6),
        patient_id bigint,
        payment_date datetime(6),
        refunded_at datetime(6),
        updated_at datetime(6),
        verified_at datetime(6),
        version bigint not null,
        reconciliation_status varchar(20),
        public_id varchar(26),
        payment_channel varchar(50),
        reference varchar(50) not null,
        rrr varchar(50),
        created_by varchar(100),
        refund_reference varchar(100),
        remita_reference varchar(100),
        updated_by varchar(100),
        failure_reason TEXT,
        initiation_response TEXT,
        reconciliation_notes TEXT,
        purpose enum ('CENTRE_WALLET_FUNDING','PATIENT_CONSULTATION') not null,
        status enum ('FAILED','PENDING','REFUNDED','REVERSED','SUCCESS','UNMATCHED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table permissions (
        is_mutating bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        public_id varchar(26),
        module varchar(40) not null,
        code varchar(80) not null,
        created_by varchar(100),
        updated_by varchar(100),
        description varchar(500) not null,
        primary key (id)
    ) engine=InnoDB;

    create table prescription_items (
        sequence integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        prescription_id bigint not null,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        duration varchar(100),
        frequency varchar(100) not null,
        strength varchar(100),
        updated_by varchar(100),
        instructions TEXT,
        medication varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

    create table prescriptions (
        expiry_date date,
        issue_date date,
        not_required bit not null,
        validity_days integer not null,
        bundle_id bigint,
        centre_consultation_id bigint,
        centre_id bigint,
        centre_patient_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        doctor_id bigint not null,
        id bigint not null auto_increment,
        patient_id bigint,
        supersedes_id bigint,
        updated_at datetime(6),
        public_id varchar(26),
        issue_number varchar(50) not null,
        created_by varchar(100),
        updated_by varchar(100),
        not_required_reason varchar(500),
        clinical_information TEXT,
        status enum ('DRAFT','EXPIRED','NOT_REQUIRED','PENDING_REVIEW','RELEASED','REVIEWED','REVOKED','SUPERSEDED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table professional_reviews (
        query_raised bit not null,
        assigned_at datetime(6) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        investigation_id bigint,
        opened_at datetime(6),
        prescription_id bigint,
        reviewer_id bigint,
        submitted_at datetime(6),
        submitted_to_hub_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        notes TEXT,
        query_detail TEXT,
        outcome enum ('QUERY_RAISED','VERIFIED'),
        review_type enum ('LABORATORY','PHARMACY') not null,
        primary key (id)
    ) engine=InnoDB;

    create table reconciliation_exceptions (
        expected_amount decimal(19,2),
        reported_amount decimal(19,2),
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        payment_id bigint,
        resolved_at datetime(6),
        run_id bigint not null,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        resolved_by varchar(100),
        updated_by varchar(100),
        details TEXT,
        resolution_notes TEXT,
        exception_type enum ('AMOUNT_MISMATCH','FAILED_AT_PROVIDER','MISSING_AT_PROVIDER','REVERSED_AT_PROVIDER','STILL_PENDING','UNMATCHED_AT_PROVIDER') not null,
        primary key (id)
    ) engine=InnoDB;

    create table reconciliation_runs (
        exception_count integer not null,
        matched_count integer not null,
        transactions_checked integer not null,
        completed_at datetime(6),
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        period_end datetime(6) not null,
        period_start datetime(6) not null,
        started_at datetime(6) not null,
        updated_at datetime(6),
        run_type varchar(20) not null,
        public_id varchar(26),
        created_by varchar(100),
        run_by varchar(100),
        updated_by varchar(100),
        primary key (id)
    ) engine=InnoDB;

    create table recordings (
        all_participants_notified bit not null,
        duration_seconds integer,
        centre_consultation_id bigint,
        consent_acceptance_id bigint,
        consultation_id bigint,
        created_at datetime(6) not null,
        deleted_at datetime(6),
        id bigint not null auto_increment,
        retention_expires_at datetime(6),
        started_at datetime(6),
        stopped_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        deleted_by varchar(100),
        updated_by varchar(100),
        provider_recording_id varchar(150),
        storage_path varchar(500),
        storage_area enum ('BACKUPS','DOCUMENTS','EHR_IMPORTS','RECORDINGS','UPLOADS'),
        primary key (id)
    ) engine=InnoDB;

    create table release_bundle_components (
        is_complete bit not null,
        is_required bit not null,
        not_required bit not null,
        bundle_id bigint not null,
        component_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        blocked_reason varchar(500),
        not_required_reason varchar(500),
        component_type enum ('CLINICAL_NOTE','FOLLOW_UP','INVESTIGATION','PRESCRIPTION') not null,
        primary key (id)
    ) engine=InnoDB;

    create table release_bundles (
        appointment_id bigint,
        centre_appointment_id bigint,
        centre_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        released_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        released_by varchar(100),
        updated_by varchar(100),
        blocked_reason varchar(500),
        release_notes varchar(500),
        status enum ('BLOCKED','INCOMPLETE','READY','RELEASED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table role_permission (
        permission_id bigint not null,
        role_id bigint not null,
        primary key (permission_id, role_id)
    ) engine=InnoDB;

    create table roles (
        is_active bit not null,
        is_system bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        public_id varchar(26),
        code varchar(50) not null,
        created_by varchar(100),
        dashboard_route varchar(100) not null,
        name varchar(100) not null,
        updated_by varchar(100),
        description varchar(500),
        scope enum ('CENTRE','FNPH','PATIENT') not null,
        primary key (id)
    ) engine=InnoDB;

    create table rooms (
        is_active bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        code varchar(20) not null,
        public_id varchar(26),
        created_by varchar(100),
        name varchar(100) not null,
        updated_by varchar(100),
        capacity_notes varchar(255),
        room_type enum ('CENTRE_CONSULTATION','CONTINGENCY','PATIENT_SERVICE') not null,
        primary key (id)
    ) engine=InnoDB;

    create table schedule_publications (
        service_date date not null,
        slot_minutes integer not null,
        slots_generated integer not null,
        window_end time(6) not null,
        window_start time(6) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        published_at datetime(6),
        updated_at datetime(6),
        withdrawn_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        published_by varchar(100),
        updated_by varchar(100),
        withdraw_reason varchar(500),
        audience enum ('CENTRE','FNPH_PATIENT') not null,
        status enum ('DRAFT','PUBLISHED','WITHDRAWN') not null,
        primary key (id)
    ) engine=InnoDB;

    create table slot_holds (
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        held_at datetime(6) not null,
        held_for_centre_id bigint,
        held_for_patient_id bigint,
        id bigint not null auto_increment,
        released_at datetime(6),
        slot_id bigint not null,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        release_reason varchar(200),
        primary key (id)
    ) engine=InnoDB;

    create table slots (
        created_at datetime(6) not null,
        end_at datetime(6) not null,
        id bigint not null auto_increment,
        publication_id bigint not null,
        room_id bigint,
        start_at datetime(6) not null,
        updated_at datetime(6),
        version bigint not null,
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        blocked_reason varchar(255),
        state enum ('AVAILABLE','BLOCKED','BOOKED','HELD') not null,
        primary key (id)
    ) engine=InnoDB;

    create table storage_deletions (
        attempts integer not null,
        created_at datetime(6) not null,
        deleted_at datetime(6),
        eligible_at datetime(6) not null,
        id bigint not null auto_increment,
        requested_at datetime(6) not null,
        updated_at datetime(6),
        public_id varchar(26),
        created_by varchar(100),
        requested_by varchar(100),
        updated_by varchar(100),
        last_error varchar(500),
        reason varchar(500) not null,
        storage_path varchar(500) not null,
        storage_area enum ('BACKUPS','DOCUMENTS','EHR_IMPORTS','RECORDINGS','UPLOADS') not null,
        primary key (id)
    ) engine=InnoDB;

    create table support_tickets (
        first_response_minutes integer,
        assigned_at datetime(6),
        assigned_to_id bigint,
        centre_id bigint,
        closed_at datetime(6),
        created_at datetime(6) not null,
        escalated_at datetime(6),
        first_responded_at datetime(6),
        id bigint not null auto_increment,
        patient_id bigint,
        raised_by_user_id bigint,
        resolved_at datetime(6),
        updated_at datetime(6),
        public_id varchar(26),
        ticket_number varchar(30) not null,
        escalated_to_role varchar(40),
        related_appointment_reference varchar(50),
        related_document_number varchar(50),
        related_payment_reference varchar(50),
        created_by varchar(100),
        resolved_by varchar(100),
        updated_by varchar(100),
        subject varchar(200) not null,
        escalation_reason varchar(500),
        resolution_summary varchar(1000),
        category enum ('ACCESS_AND_SIGN_IN','BOOKING','CLINICAL_CONCERN','DOCUMENT_ACCESS','ENROLMENT','OTHER','PAYMENT','TECHNICAL_FAULT') not null,
        priority enum ('HIGH','LOW','NORMAL','URGENT') not null,
        status enum ('AWAITING_REQUESTER','CLOSED','ESCALATED','IN_PROGRESS','OPEN','RESOLVED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table system_configuration (
        is_sensitive bit not null,
        requires_governance bit not null,
        created_at datetime(6) not null,
        effective_from datetime(6) not null,
        id bigint not null auto_increment,
        updated_at datetime(6),
        public_id varchar(26),
        category varchar(50) not null,
        max_value varchar(50),
        min_value varchar(50),
        config_key varchar(100) not null,
        created_by varchar(100),
        updated_by varchar(100),
        allowed_values varchar(500),
        description varchar(500) not null,
        config_value varchar(1000) not null,
        value_type enum ('BOOLEAN','DECIMAL','DURATION_MINUTES','INTEGER','STRING') not null,
        primary key (id)
    ) engine=InnoDB;

    create table ticket_events (
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        occurred_at datetime(6) not null,
        ticket_id bigint not null,
        public_id varchar(26),
        event_type varchar(30) not null,
        from_value varchar(40),
        to_value varchar(40),
        actor varchar(100),
        created_by varchar(100),
        detail varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table ticket_messages (
        is_internal bit not null,
        author_user_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        sent_at datetime(6) not null,
        ticket_id bigint not null,
        public_id varchar(26),
        author_label varchar(100) not null,
        created_by varchar(100),
        body TEXT not null,
        primary key (id)
    ) engine=InnoDB;

    create table transcripts (
        retained_clinically bit not null,
        centre_consultation_id bigint,
        clinician_reviewed_at datetime(6),
        consultation_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        recording_id bigint,
        updated_at datetime(6),
        status varchar(20) not null,
        public_id varchar(26),
        clinician_reviewed_by varchar(100),
        created_by varchar(100),
        updated_by varchar(100),
        storage_path varchar(500),
        storage_area enum ('BACKUPS','DOCUMENTS','EHR_IMPORTS','RECORDINGS','UPLOADS'),
        primary key (id)
    ) engine=InnoDB;

    create table triage_question_sets (
        created_at datetime(6) not null,
        effective_from datetime(6),
        id bigint not null auto_increment,
        retired_at datetime(6),
        updated_at datetime(6),
        audience varchar(20) not null,
        status varchar(20) not null,
        public_id varchar(26),
        version varchar(30) not null,
        created_by varchar(100),
        updated_by varchar(100),
        primary key (id)
    ) engine=InnoDB;

    create table triage_questions (
        sequence integer not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        question_set_id bigint not null,
        updated_at datetime(6),
        stop_answer varchar(10) not null,
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        stop_reason varchar(200) not null,
        question_text TEXT not null,
        primary key (id)
    ) engine=InnoDB;

    create table triage_responses (
        centre_id bigint,
        centre_patient_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        patient_id bigint,
        question_set_id bigint not null,
        stopped_on_question_id bigint,
        submitted_at datetime(6) not null,
        outcome varchar(20) not null,
        public_id varchar(26),
        triage_version varchar(30) not null,
        ip_address varchar(45),
        created_by varchar(100),
        stop_reason varchar(200),
        answers_json TEXT not null,
        escalation_shown TEXT,
        primary key (id)
    ) engine=InnoDB;

    create table user_role (
        is_primary bit not null,
        created_at datetime(6) not null,
        granted_at datetime(6),
        primary_marker bigint,
        role_id bigint not null,
        user_id bigint not null,
        created_by varchar(100),
        granted_by varchar(100),
        grant_reason varchar(500),
        primary key (role_id, user_id)
    ) engine=InnoDB;

    create table user_sessions (
        created_at datetime(6) not null,
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        issued_at datetime(6) not null,
        last_seen_at datetime(6) not null,
        replaced_at datetime(6),
        replaced_by_session_id bigint,
        revoked_at datetime(6),
        updated_at datetime(6),
        user_id bigint not null,
        family_id varchar(26) not null,
        public_id varchar(26),
        ip_address varchar(45),
        device_fingerprint varchar(64),
        refresh_token_hash varchar(64) not null,
        created_by varchar(100),
        revoked_by varchar(100),
        updated_by varchar(100),
        device_label varchar(120),
        revoked_reason varchar(200),
        user_agent varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table users (
        account_locked bit not null,
        deleted bit not null,
        failed_login_attempts integer not null,
        is_active bit not null,
        mfa_enabled bit not null,
        must_change_password bit not null,
        activated_at datetime(6),
        centre_id bigint,
        created_at datetime(6) not null,
        deactivated_at datetime(6),
        deleted_at datetime(6),
        email_verified_at datetime(6),
        id bigint not null auto_increment,
        invited_at datetime(6),
        last_login_at datetime(6),
        last_password_reset_at datetime(6),
        lock_expiry datetime(6),
        password_changed_at datetime(6),
        patient_id bigint,
        updated_at datetime(6),
        phone_number varchar(20),
        public_id varchar(26),
        first_name varchar(50) not null,
        last_name varchar(50) not null,
        staff_number varchar(50),
        created_by varchar(100),
        deleted_by varchar(100),
        email varchar(100) not null,
        invited_by varchar(100),
        updated_by varchar(100),
        username varchar(100) not null,
        deactivated_reason varchar(500),
        deleted_reason varchar(500),
        password varchar(255) not null,
        login_type enum ('EHR_NUMBER','EMAIL','USERNAME') not null,
        status enum ('ACTIVE','DEACTIVATED','INVITED','SUSPENDED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table verification_attempts (
        attempted_at datetime(6) not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        public_id varchar(26),
        ip_address varchar(45),
        token_presented varchar(64) not null,
        created_by varchar(100),
        user_agent varchar(500),
        outcome enum ('EXPIRED','NOT_FOUND','REVOKED','SUPERSEDED','VALID') not null,
        primary key (id)
    ) engine=InnoDB;

    create table view_as_sessions (
        actions_performed integer not null,
        administrator_id bigint not null,
        created_at datetime(6) not null,
        ended_at datetime(6),
        expires_at datetime(6) not null,
        id bigint not null auto_increment,
        started_at datetime(6) not null,
        target_role_id bigint not null,
        target_user_id bigint not null,
        updated_at datetime(6),
        public_id varchar(26),
        ip_address varchar(45),
        created_by varchar(100),
        end_reason varchar(100),
        updated_by varchar(100),
        reason varchar(500) not null,
        user_agent varchar(500),
        primary key (id)
    ) engine=InnoDB;

    create table vitals (
        blood_glucose float(53),
        blood_oxygen integer,
        blood_pressure_diastolic integer,
        blood_pressure_systolic integer,
        bmi float(53),
        heart_rate integer,
        height_cm float(53),
        is_self_reported bit not null,
        nurse_verified bit not null,
        respiratory_rate integer,
        temperature float(53),
        weight_kg float(53),
        appointment_id bigint,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        measured_at datetime(6) not null,
        patient_id bigint not null,
        updated_at datetime(6),
        verified_at datetime(6),
        public_id varchar(26),
        measurement_source varchar(50),
        created_by varchar(100),
        updated_by varchar(100),
        notes TEXT,
        verified_by varchar(255),
        primary key (id)
    ) engine=InnoDB;

    create table wallet_alerts (
        balance_at_alert decimal(19,2) not null,
        threshold_amount decimal(19,2) not null,
        acknowledged_at datetime(6),
        centre_id bigint not null,
        cleared_at datetime(6),
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        raised_at datetime(6) not null,
        updated_at datetime(6),
        wallet_id bigint not null,
        alert_level varchar(20) not null,
        public_id varchar(26),
        acknowledged_by varchar(100),
        created_by varchar(100),
        updated_by varchar(100),
        primary key (id)
    ) engine=InnoDB;

    create table wallet_transactions (
        amount decimal(19,2) not null,
        balance_after decimal(19,2) not null,
        balance_before decimal(19,2) not null,
        centre_appointment_id bigint,
        centre_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        reverses_transaction_id bigint,
        wallet_id bigint not null,
        public_id varchar(26),
        source varchar(50),
        transaction_reference varchar(50) not null,
        created_by varchar(100),
        description TEXT,
        direction enum ('CREDIT','DEBIT') not null,
        status enum ('POSTED','REVERSED') not null,
        primary key (id)
    ) engine=InnoDB;

    create table wallets (
        balance decimal(19,2) not null,
        critical_balance_warning_sent bit not null,
        critical_threshold decimal(19,2),
        low_balance_warning_sent bit not null,
        warning_threshold decimal(19,2),
        centre_id bigint not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        last_credited_at datetime(6),
        last_debited_at datetime(6),
        last_reconciled_at datetime(6),
        updated_at datetime(6),
        version bigint not null,
        public_id varchar(26),
        created_by varchar(100),
        updated_by varchar(100),
        primary key (id)
    ) engine=InnoDB;

    create table webhook_inbox (
        attempts integer not null,
        signature_valid bit not null,
        created_at datetime(6) not null,
        id bigint not null auto_increment,
        processed_at datetime(6),
        received_at datetime(6) not null,
        updated_at datetime(6),
        public_id varchar(26),
        source_ip varchar(45),
        payload_hash varchar(64) not null,
        created_by varchar(100),
        updated_by varchar(100),
        provider_event_id varchar(150),
        failure_reason varchar(500),
        payload LONGTEXT not null,
        processing_state enum ('FAILED','IGNORED','PROCESSED','RECEIVED') not null,
        provider enum ('REMITA','VIDEO') not null,
        primary key (id)
    ) engine=InnoDB;

    create index idx_appointments_status 
       on appointments (status);

    create index idx_appointments_datetime 
       on appointments (appointment_date);

    create index idx_appointments_doctor 
       on appointments (doctor_id, appointment_date);

    alter table appointments 
       add constraint uk_appointments_reference unique (reference);

    alter table appointments 
       add constraint UK8y6yin1cflvk14414e91mdcwm unique (slot_id);

    create index idx_audit_logs_user_time 
       on audit_logs (user_id, performed_at);

    create index idx_audit_logs_entity 
       on audit_logs (entity_type, entity_id);

    create index idx_audit_logs_centre 
       on audit_logs (centre_id, performed_at);

    create index idx_centre_appointments_centre 
       on centre_appointments (centre_id, appointment_date);

    create index idx_centre_appointments_status 
       on centre_appointments (status);

    alter table centre_appointments 
       add constraint uk_centre_appointments_reference unique (reference);

    alter table centre_appointments 
       add constraint UKtj3sypio7mjqmn8qv0epctlig unique (slot_id);

    alter table centre_bundle_receipts 
       add constraint UK53f3hoi478ay4dd1rkufumfl7 unique (bundle_id);

    alter table centre_consultation_notes 
       add constraint UKpd0sujvvhs4r1c1qjyxnsdg0r unique (centre_consultation_id);

    create index idx_centre_consultations_appt 
       on centre_consultations (centre_appointment_id);

    create index idx_centre_patients_centre 
       on centre_patients (centre_id);

    alter table centre_patients 
       add constraint uk_centre_patients_centre_local_id unique (centre_id, centre_patient_id);

    alter table centres 
       add constraint UK9sihld9m3ssw0o16kh8h83dut unique (code);

    alter table consultation_notes 
       add constraint UKe70y1o8dt9mp28wf6acb5i950 unique (consultation_id);

    create index idx_consultations_appointment 
       on consultations (appointment_id);

    create index idx_consultations_doctor 
       on consultations (doctor_id);

    create index idx_doc_verifications_issue_number 
       on document_verifications (issue_number);

    alter table document_verifications 
       add constraint uk_doc_verifications_token unique (verification_token);

    create index idx_file_uploads_patient 
       on file_uploads (patient_id);

    create index idx_file_uploads_centre 
       on file_uploads (centre_id);

    create index idx_file_uploads_scan 
       on file_uploads (scan_status);

    create index idx_follow_ups_status 
       on follow_ups (status);

    create index idx_investigation_items_parent 
       on investigation_items (investigation_id);

    create index idx_investigations_status 
       on investigations (status);

    create index idx_investigations_patient 
       on investigations (patient_id);

    alter table investigations 
       add constraint uk_investigations_issue_number unique (issue_number);

    alter table notification_preferences 
       add constraint UKn2jopkbm16qv3xelbvoyjkd0g unique (user_id);

    create index idx_notifications_user_status 
       on notifications (user_id, status);

    create index idx_notifications_scheduled 
       on notifications (scheduled_for, status);

    alter table patients 
       add constraint uk_patients_ehr_number unique (ehr_number);

    create index idx_payments_status 
       on payments (status);

    create index idx_payments_patient 
       on payments (patient_id);

    create index idx_payments_remita_ref 
       on payments (remita_reference);

    alter table payments 
       add constraint uk_payments_reference unique (reference);

    create index idx_prescription_items_parent 
       on prescription_items (prescription_id);

    create index idx_prescriptions_status 
       on prescriptions (status);

    create index idx_prescriptions_patient 
       on prescriptions (patient_id);

    alter table prescriptions 
       add constraint uk_prescriptions_issue_number unique (issue_number);

    alter table release_bundles 
       add constraint UKibd08ee6wdmxyjl5cor9jhiqj unique (appointment_id);

    alter table release_bundles 
       add constraint UK81hper401b4vuwvgkuv8xiqmx unique (centre_appointment_id);

    alter table users 
       add constraint uk_users_email unique (email);

    alter table users 
       add constraint uk_users_username unique (username);

    create index idx_wallet_tx_centre 
       on wallet_transactions (centre_id, created_at);

    alter table wallet_transactions 
       add constraint uk_wallet_tx_reference unique (transaction_reference);

    alter table wallets 
       add constraint uk_wallets_centre unique (centre_id);

    alter table account_tokens 
       add constraint FK19h8y4psfcpn5ct8b9mm2o2na 
       foreign key (user_id) 
       references users (id);

    alter table appointments 
       add constraint FKbsma6x4pnujct0e6xkycu9864 
       foreign key (room_id) 
       references rooms (id);

    alter table appointments 
       add constraint FK6u6s6egu60m2cbdjno44jbipa 
       foreign key (doctor_id) 
       references users (id);

    alter table appointments 
       add constraint fk_appointments_him 
       foreign key (him_officer_id) 
       references users (id);

    alter table appointments 
       add constraint fk_appointments_lab 
       foreign key (laboratory_technician_id) 
       references users (id);

    alter table appointments 
       add constraint fk_appointments_nurse 
       foreign key (nurse_id) 
       references users (id);

    alter table appointments 
       add constraint FK8exap5wmg8kmb1g1rx3by21yt 
       foreign key (patient_id) 
       references patients (id);

    alter table appointments 
       add constraint FKbl66iv34dncbgbr3jlph3oo5w 
       foreign key (payment_id) 
       references payments (id);

    alter table appointments 
       add constraint fk_appointments_pharmacist 
       foreign key (pharmacist_id) 
       references users (id);

    alter table appointments 
       add constraint FKf8qrv9g386dae81yfkj1qgs77 
       foreign key (slot_id) 
       references slots (id);

    alter table audit_logs 
       add constraint FKhu2bflh9dlvp5184je7khsjso 
       foreign key (effective_principal_id) 
       references users (id);

    alter table audit_logs 
       add constraint FKjs4iimve3y0xssbtve5ysyef0 
       foreign key (user_id) 
       references users (id);

    alter table cancellation_requests 
       add constraint FKfmpjbd7hawk1kele645dlxo47 
       foreign key (appointment_id) 
       references appointments (id);

    alter table cancellation_requests 
       add constraint FKe3f4wpylhfmr1u467gkjs8a00 
       foreign key (proposed_slot_id) 
       references slots (id);

    alter table centre_appointments 
       add constraint FKcn2vjgog21t3v6b5fp30l7i2v 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_appointments 
       add constraint FKluom2q50jj0334urqv79bv4a5 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table centre_appointments 
       add constraint FKkfs5vitssomp8uo2rnanohqvj 
       foreign key (doctor_id) 
       references users (id);

    alter table centre_appointments 
       add constraint FK1kbi0jyty6qu5ym6mbd250ws4 
       foreign key (him_id) 
       references users (id);

    alter table centre_appointments 
       add constraint FKjfqhuptqqrdbylibtdybi73ll 
       foreign key (laboratory_id) 
       references users (id);

    alter table centre_appointments 
       add constraint FKtkkgcxhnm8vjfwmv1i0j570i 
       foreign key (pharmacy_id) 
       references users (id);

    alter table centre_appointments 
       add constraint FKbqak0b7seobexlnh88h2hvm8 
       foreign key (referral_id) 
       references centre_referrals (id);

    alter table centre_appointments 
       add constraint FKoiwxxq53kl4c3xbpniy46m95b 
       foreign key (room_id) 
       references rooms (id);

    alter table centre_appointments 
       add constraint FK41jlor7x248myv5e79rhwpl8o 
       foreign key (slot_id) 
       references slots (id);

    alter table centre_bundle_receipts 
       add constraint FK736miqbg5a6prkk3yr424qww 
       foreign key (bundle_id) 
       references release_bundles (id);

    alter table centre_bundle_receipts 
       add constraint FKm6tgtiqjhpo4975jiek2hhx2c 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_bundle_receipts 
       add constraint FKmy0onqilatr9ldh2oj805e20h 
       foreign key (centre_appointment_id) 
       references centre_appointments (id);

    alter table centre_bundle_receipts 
       add constraint FK6o4nchiy5h2q9tgbgduhdml0f 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table centre_capabilities 
       add constraint FKn3vsfqia519wv4l8yw351mqlf 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_consultation_notes 
       add constraint FKrs0fpm34x4cmvlppudl2yqj9c 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_consultation_notes 
       add constraint FKcj9sr3qyu7hasf6x7m7nxs1ku 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table centre_consultations 
       add constraint FKs14eyyshi8q80l4i8j5xbq73j 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_consultations 
       add constraint FKjtj5hkmpmklmxfg5mr1yl6pr5 
       foreign key (centre_appointment_id) 
       references centre_appointments (id);

    alter table centre_consultations 
       add constraint FKeln3sy2hos4pherupsfkdox03 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table centre_consultations 
       add constraint FKqww1kyt12d89yafcbmhlhplxv 
       foreign key (doctor_id) 
       references users (id);

    alter table centre_consultations 
       add constraint FKpyvhri9d96javr2i9y9bcyvhd 
       foreign key (terminated_by_id) 
       references users (id);

    alter table centre_patients 
       add constraint FKh1lo75hiyarq2w0ww2vmmfrmd 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_referrals 
       add constraint FKefaddplaaytwsw9cgdwkwi859 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_referrals 
       add constraint FKsxk4kc41yq5kqhheqb0epskmb 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table centre_staff 
       add constraint FKt9229at99td4eg1kfho1v7r1y 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_staff 
       add constraint FKrai4pna49xmo6li6quvno3ey9 
       foreign key (user_id) 
       references users (id);

    alter table centre_vitals 
       add constraint FKaglg9s5l72wyxhinuy0nrpxuc 
       foreign key (centre_id) 
       references centres (id);

    alter table centre_vitals 
       add constraint FK1qidud6hw9j0y9vtcfogt22f7 
       foreign key (centre_appointment_id) 
       references centre_appointments (id);

    alter table centre_vitals 
       add constraint FK2vajabasnruhcsc3ru1lrv3d4 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table configuration_changes 
       add constraint FK6w81xngnv4remxdfk429ixg2l 
       foreign key (configuration_id) 
       references system_configuration (id);

    alter table consent_acceptances 
       add constraint FKglb0i4l2w0hot6ny0t138yaha 
       foreign key (centre_id) 
       references centres (id);

    alter table consent_acceptances 
       add constraint FKaewd7bs1phqyr0udduh06tuw7 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table consent_acceptances 
       add constraint FKiuhdq2cct4h3w9riuih3w6hml 
       foreign key (consent_document_id) 
       references consent_documents (id);

    alter table consent_acceptances 
       add constraint FK5dcvobvc0v40t0iqnyvsocrqf 
       foreign key (patient_id) 
       references patients (id);

    alter table consultation_notes 
       add constraint FKbch9xmmspaf5la0xcyufyyd4s 
       foreign key (consultation_id) 
       references consultations (id);

    alter table consultations 
       add constraint FKp77tpwkqp4e3fxdi9d7eo44cx 
       foreign key (appointment_id) 
       references appointments (id);

    alter table consultations 
       add constraint FKkog17uvjvkkg4bv6l5eu1ysqw 
       foreign key (doctor_id) 
       references users (id);

    alter table consultations 
       add constraint FKdqyibd6w1h5h66xn9aqx7fwv5 
       foreign key (patient_id) 
       references patients (id);

    alter table consultations 
       add constraint fk_consultations_terminated_by 
       foreign key (terminated_by_id) 
       references users (id);

    alter table contact_verifications 
       add constraint FKdkyokglutkb1c2uqgtc4esygn 
       foreign key (patient_id) 
       references patients (id);

    alter table doctor_availability 
       add constraint FK1mfkjo7s5ct426dm5a2hyiur0 
       foreign key (doctor_id) 
       references users (id);

    alter table document_verifications 
       add constraint FKajxcwfim4n6gf6yl4u8n6hvve 
       foreign key (issued_document_id) 
       references issued_documents (id);

    alter table ehr_verification_imports 
       add constraint FKmtvhxnoe5er7lyr436bqji2nq 
       foreign key (uploaded_by_id) 
       references users (id);

    alter table ehr_verification_records 
       add constraint FKqj8g5hgegfmjsl36jesj8nlgk 
       foreign key (ehr_import_id) 
       references ehr_verification_imports (id);

    alter table file_uploads 
       add constraint fk_file_uploads_centre 
       foreign key (centre_id) 
       references centres (id);

    alter table file_uploads 
       add constraint fk_file_uploads_centre_patient 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table file_uploads 
       add constraint fk_file_uploads_patient 
       foreign key (patient_id) 
       references patients (id);

    alter table follow_ups 
       add constraint FK5mvj9rg8v7mmy9bu6rv60qnx0 
       foreign key (bundle_id) 
       references release_bundles (id);

    alter table follow_ups 
       add constraint FKk2p4ixx2manqfdgkxy94p81xq 
       foreign key (centre_id) 
       references centres (id);

    alter table follow_ups 
       add constraint fk_follow_ups_centre_consultation 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table follow_ups 
       add constraint fk_follow_ups_centre_patient 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table follow_ups 
       add constraint fk_follow_ups_consultation 
       foreign key (consultation_id) 
       references consultations (id);

    alter table follow_ups 
       add constraint fk_follow_ups_patient 
       foreign key (patient_id) 
       references patients (id);

    alter table investigation_items 
       add constraint fk_investigation_items_parent 
       foreign key (investigation_id) 
       references investigations (id);

    alter table investigations 
       add constraint FKc4h41bj6td729bql4dfm5n4ug 
       foreign key (bundle_id) 
       references release_bundles (id);

    alter table investigations 
       add constraint FKh9h0wwx51yy9mxrc0mgmmg7ih 
       foreign key (centre_id) 
       references centres (id);

    alter table investigations 
       add constraint fk_investigations_centre_consultation 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table investigations 
       add constraint fk_investigations_centre_patient 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table investigations 
       add constraint fk_investigations_consultation 
       foreign key (consultation_id) 
       references consultations (id);

    alter table investigations 
       add constraint fk_investigations_doctor 
       foreign key (doctor_id) 
       references users (id);

    alter table investigations 
       add constraint fk_investigations_patient 
       foreign key (patient_id) 
       references patients (id);

    alter table issued_documents 
       add constraint FKjoo46oyijiqvcu3fhslrbl7pq 
       foreign key (bundle_id) 
       references release_bundles (id);

    alter table issued_documents 
       add constraint FK8nqhjr84eryis0nwma6qaq2it 
       foreign key (centre_id) 
       references centres (id);

    alter table issued_documents 
       add constraint FKo3dcnii442ttq4srtx6ru3erx 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table issued_documents 
       add constraint FK3duppv4s2rqjwh7w7r5gfk041 
       foreign key (patient_id) 
       references patients (id);

    alter table login_attempts 
       add constraint FKtg9vhke4mlf5vij2rcvfk2dg2 
       foreign key (user_id) 
       references users (id);

    alter table mfa_factors 
       add constraint FK2uhdy52npgyaip4k2keay3ed6 
       foreign key (user_id) 
       references users (id);

    alter table mfa_recovery_codes 
       add constraint FKjnc2k4fk1x89im0mimshlofkk 
       foreign key (user_id) 
       references users (id);

    alter table notification_broadcasts 
       add constraint FKcuqh09ahvq2he8dc9pv5w4d02 
       foreign key (centre_id) 
       references centres (id);

    alter table notification_broadcasts 
       add constraint FKlowxo1l9jyp25rb7s1y85n6f2 
       foreign key (target_role_id) 
       references roles (id);

    alter table notification_preferences 
       add constraint FKt9qjvmcl36i14utm5uptyqg84 
       foreign key (user_id) 
       references users (id);

    alter table notifications 
       add constraint FKmr8b1lewqu0ll7fxig3ryh67l 
       foreign key (acknowledged_by_id) 
       references users (id);

    alter table notifications 
       add constraint FKpkui4q9qwh3610x7wmhiw7jov 
       foreign key (centre_id) 
       references centres (id);

    alter table notifications 
       add constraint FKsxbhag07yf88eve8uuor8tll1 
       foreign key (patient_id) 
       references patients (id);

    alter table notifications 
       add constraint FKrdprswg5dkns7upacs9wrt96 
       foreign key (target_role_id) 
       references roles (id);

    alter table notifications 
       add constraint fk_notifications_user 
       foreign key (user_id) 
       references users (id);

    alter table participant_tokens 
       add constraint FKpcmnqqs93rw2mvbnwmc2kunm8 
       foreign key (centre_id) 
       references centres (id);

    alter table participant_tokens 
       add constraint FKpuoj5hxfo6tati0gqxjhfbjta 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table participant_tokens 
       add constraint FKiooebmskwfj7ivqwf7gc9qadf 
       foreign key (consultation_id) 
       references consultations (id);

    alter table participant_tokens 
       add constraint FKq0skmw11y2tp9ojsvb5dh1r98 
       foreign key (patient_id) 
       references patients (id);

    alter table participant_tokens 
       add constraint FK4ybf6nghtt9llgj2ms615qxra 
       foreign key (user_id) 
       references users (id);

    alter table patient_credits 
       add constraint FK2nhokjvkj93obv69usgbs24vv 
       foreign key (patient_id) 
       references patients (id);

    alter table patient_verification_requests 
       add constraint FK7mwbad1hk79k41o08h9kipob8 
       foreign key (assigned_to_id) 
       references users (id);

    alter table patient_verification_requests 
       add constraint FK5la1jlojx2m9p286guswyamdw 
       foreign key (resulting_patient_id) 
       references patients (id);

    alter table payments 
       add constraint fk_payments_appointment 
       foreign key (appointment_id) 
       references appointments (id);

    alter table payments 
       add constraint fk_payments_patient 
       foreign key (patient_id) 
       references patients (id);

    alter table prescription_items 
       add constraint fk_prescription_items_parent 
       foreign key (prescription_id) 
       references prescriptions (id);

    alter table prescriptions 
       add constraint FK8fyfda9i3wohxynoxyp8yx1yb 
       foreign key (bundle_id) 
       references release_bundles (id);

    alter table prescriptions 
       add constraint FKhpucrl8okvrfb5kuoi1btao4s 
       foreign key (centre_id) 
       references centres (id);

    alter table prescriptions 
       add constraint fk_prescriptions_centre_consultation 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table prescriptions 
       add constraint fk_prescriptions_centre_patient 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table prescriptions 
       add constraint fk_prescriptions_consultation 
       foreign key (consultation_id) 
       references consultations (id);

    alter table prescriptions 
       add constraint fk_prescriptions_doctor 
       foreign key (doctor_id) 
       references users (id);

    alter table prescriptions 
       add constraint fk_prescriptions_patient 
       foreign key (patient_id) 
       references patients (id);

    alter table professional_reviews 
       add constraint FKdeuibvtm9y87jy02rmc8cx7bg 
       foreign key (investigation_id) 
       references investigations (id);

    alter table professional_reviews 
       add constraint FK903rw8926mdewdejsxd0j580p 
       foreign key (prescription_id) 
       references prescriptions (id);

    alter table professional_reviews 
       add constraint FK3ajt9x0edp7172o9lrvofan3l 
       foreign key (reviewer_id) 
       references users (id);

    alter table reconciliation_exceptions 
       add constraint FK1igarkr8f4ewsfxr85apf9w8r 
       foreign key (payment_id) 
       references payments (id);

    alter table reconciliation_exceptions 
       add constraint FKd7hoc4x07t8b4ba1eu9ksfgmu 
       foreign key (run_id) 
       references reconciliation_runs (id);

    alter table recordings 
       add constraint FKfv4irvswbnphtnrsvgf5iqwae 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table recordings 
       add constraint FK2oj0ikxve6l85ed42f5aee0wq 
       foreign key (consultation_id) 
       references consultations (id);

    alter table release_bundle_components 
       add constraint FKr6rfoor7h2evicjt4ne210kkw 
       foreign key (bundle_id) 
       references release_bundles (id);

    alter table release_bundles 
       add constraint FK6qcogafcx2j1ac9wp7af6738w 
       foreign key (appointment_id) 
       references appointments (id);

    alter table release_bundles 
       add constraint FKecd5l4lsyb1e619rkjeuqsi5u 
       foreign key (centre_id) 
       references centres (id);

    alter table release_bundles 
       add constraint FK82xvw1f62p5xx4goakpolegho 
       foreign key (centre_appointment_id) 
       references centre_appointments (id);

    alter table role_permission 
       add constraint FK2xn8qv4vw30i04xdxrpvn3bdi 
       foreign key (permission_id) 
       references permissions (id);

    alter table role_permission 
       add constraint FKtfgq8q9blrp0pt1pvggyli3v9 
       foreign key (role_id) 
       references roles (id);

    alter table slot_holds 
       add constraint FKdbs7qy7sugpusjwkt965diclm 
       foreign key (held_for_centre_id) 
       references centres (id);

    alter table slot_holds 
       add constraint FK517p0s2u3pjuprkvy2x71rd5t 
       foreign key (held_for_patient_id) 
       references patients (id);

    alter table slot_holds 
       add constraint FK6kosmr7spdc153j3iurxgip04 
       foreign key (slot_id) 
       references slots (id);

    alter table slots 
       add constraint FK3e7xq4cotwwdna1qxuikm7x54 
       foreign key (publication_id) 
       references schedule_publications (id);

    alter table slots 
       add constraint FKn2wronpe2efhkkhmrrbhnjyfv 
       foreign key (room_id) 
       references rooms (id);

    alter table support_tickets 
       add constraint FKq26j5vg16vga26rlfibd8c7p5 
       foreign key (assigned_to_id) 
       references users (id);

    alter table support_tickets 
       add constraint FKegj4gha2ty7aycoprhdnykomo 
       foreign key (centre_id) 
       references centres (id);

    alter table support_tickets 
       add constraint FK5ii2imuxej8kamhm432tbj1xl 
       foreign key (patient_id) 
       references patients (id);

    alter table support_tickets 
       add constraint FKqfx9njnwqi4qj977p0mw0bpuk 
       foreign key (raised_by_user_id) 
       references users (id);

    alter table ticket_messages 
       add constraint FKpv5nahkswtsmussya0rof54b9 
       foreign key (author_user_id) 
       references users (id);

    alter table transcripts 
       add constraint FKha5xirvf7risol0b4tyukotuk 
       foreign key (centre_consultation_id) 
       references centre_consultations (id);

    alter table transcripts 
       add constraint FKdvet60ofwp5kfr62wyvh6aj8y 
       foreign key (consultation_id) 
       references consultations (id);

    alter table transcripts 
       add constraint FK5m974jatic4ll2rkvfau2ia4b 
       foreign key (recording_id) 
       references recordings (id);

    alter table triage_questions 
       add constraint FKrbxbqe3f7reivmffyxel8g8wf 
       foreign key (question_set_id) 
       references triage_question_sets (id);

    alter table triage_responses 
       add constraint FKjjc9njnn11mgx78g2g0iwrmtf 
       foreign key (centre_id) 
       references centres (id);

    alter table triage_responses 
       add constraint FK2qxndmbgpf86vaysekapec4js 
       foreign key (centre_patient_id) 
       references centre_patients (id);

    alter table triage_responses 
       add constraint FKebagi5f26bdlopqdyi74a7f5f 
       foreign key (patient_id) 
       references patients (id);

    alter table triage_responses 
       add constraint FKet53d8ouwmqpni7rl41e5wye9 
       foreign key (question_set_id) 
       references triage_question_sets (id);

    alter table user_role 
       add constraint FKt7e7djp752sqn6w22i6ocqy6q 
       foreign key (role_id) 
       references roles (id);

    alter table user_role 
       add constraint FKj345gk1bovqvfame88rcx7yyx 
       foreign key (user_id) 
       references users (id);

    alter table user_sessions 
       add constraint FK8klxsgb8dcjjklmqebqp1twd5 
       foreign key (user_id) 
       references users (id);

    alter table users 
       add constraint fk_users_centre 
       foreign key (centre_id) 
       references centres (id);

    alter table users 
       add constraint fk_users_patient 
       foreign key (patient_id) 
       references patients (id);

    alter table view_as_sessions 
       add constraint FKpym4asy4akus2yg5j3w3n2ha1 
       foreign key (administrator_id) 
       references users (id);

    alter table view_as_sessions 
       add constraint FKi9iv0u1bmlpgiwkphvuqbby06 
       foreign key (target_role_id) 
       references roles (id);

    alter table view_as_sessions 
       add constraint FKbxc5kkpl0ip919s2c0aa02e0x 
       foreign key (target_user_id) 
       references users (id);

    alter table vitals 
       add constraint FK91ykrva6mpftmjqiyg7ojyfno 
       foreign key (appointment_id) 
       references appointments (id);

    alter table vitals 
       add constraint FKbo7ir9l0y6tex79k2bp9fp1tt 
       foreign key (patient_id) 
       references patients (id);

    alter table wallet_alerts 
       add constraint FK48t22711bg0r5fiy6rsgc5vbe 
       foreign key (centre_id) 
       references centres (id);

    alter table wallet_alerts 
       add constraint FKrt9tcqjho9w3rvp2rxgihcp73 
       foreign key (wallet_id) 
       references wallets (id);

    alter table wallet_transactions 
       add constraint fk_wallet_tx_centre 
       foreign key (centre_id) 
       references centres (id);

    alter table wallet_transactions 
       add constraint fk_wallet_tx_appointment 
       foreign key (centre_appointment_id) 
       references centre_appointments (id);

    alter table wallet_transactions 
       add constraint fk_wallet_tx_wallet 
       foreign key (wallet_id) 
       references wallets (id);

    alter table wallets 
       add constraint fk_wallets_centre 
       foreign key (centre_id) 
       references centres (id);

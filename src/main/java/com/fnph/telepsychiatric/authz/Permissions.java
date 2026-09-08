package com.fnph.telepsychiatric.authz;

/**
 * Compile-time constants for the permission catalogue seeded in V5.
 *
 * Authorisation is expressed in permissions, never role names. Code asks
 * "may this principal approve an appointment", not "is this principal a Hub
 * Coordinator". Moving a capability between roles then becomes one row in
 * role_permission rather than a search for every place that named the role.
 *
 * Used with method security:
 *
 *   @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions)"
 *               + ".APPOINTMENT_APPROVE)")
 *
 * PermissionMatrixTest asserts that every constant here exists in the database
 * and that every seeded permission has a constant, so the two cannot drift.
 */
public final class Permissions {

    private Permissions() {
    }

    // --- identity ----------------------------------------------------
    /** View staff and centre user accounts. */
    public static final String USER_READ = "user.read";
    /** Create a user account. */
    public static final String USER_CREATE = "user.create";
    /** Amend a user account. */
    public static final String USER_UPDATE = "user.update";
    /** Deactivate a user without destroying their history. */
    public static final String USER_DEACTIVATE = "user.deactivate";
    /** Force a password reset on another account. */
    public static final String USER_RESET_PASSWORD = "user.reset_password";
    /** View the role and permission matrix. */
    public static final String ROLE_READ = "role.read";
    /** Assign or remove roles on a user account. */
    public static final String ROLE_ASSIGN = "role.assign";
    /** Revoke another user's session or device. */
    public static final String SESSION_REVOKE = "session.revoke";
    /** Reset a user's second factor. */
    public static final String MFA_RESET = "mfa.reset";
    /** View own profile. */
    public static final String PROFILE_READ_OWN = "profile.read_own";
    /** Amend own profile. */
    public static final String PROFILE_UPDATE_OWN = "profile.update_own";

    // --- supervision -------------------------------------------------
    /** Open another user's dashboard as a supervised, logged session. */
    public static final String SUPERVISION_VIEW_AS = "supervision.view_as";
    /** Read the supervised access log. */
    public static final String SUPERVISION_READ_LOG = "supervision.read_log";

    // --- patient -----------------------------------------------------
    /** View FNPH patient records. */
    public static final String PATIENT_READ = "patient.read";
    /** View own patient record. */
    public static final String PATIENT_READ_OWN = "patient.read_own";
    /** Create an FNPH patient record. */
    public static final String PATIENT_CREATE = "patient.create";
    /** Amend an FNPH patient record. */
    public static final String PATIENT_UPDATE = "patient.update";
    /** Decide an unmatched EHR verification request. */
    public static final String PATIENT_VERIFY = "patient.verify";
    /** Activate a verified patient account. */
    public static final String PATIENT_ACTIVATE = "patient.activate";
    /** Review a record whose imported details changed after activation. */
    public static final String PATIENT_FLAG_DRIFT = "patient.flag_drift";

    // --- ehr ---------------------------------------------------------
    /** Upload an EHR verification export. */
    public static final String EHR_IMPORT_UPLOAD = "ehr_import.upload";
    /** Make an uploaded export the active verification source. */
    public static final String EHR_IMPORT_ACTIVATE = "ehr_import.activate";
    /** View import history and validation reports. */
    public static final String EHR_IMPORT_READ = "ehr_import.read";
    /** Work the unmatched verification queue. */
    public static final String EHR_VERIFICATION_RESOLVE = "ehr_verification.resolve";

    // --- triage ------------------------------------------------------
    /** Submit triage answers. */
    public static final String TRIAGE_SUBMIT = "triage.submit";
    /** View submitted triage answers. */
    public static final String TRIAGE_READ = "triage.read";
    /** Accept a consent document version. */
    public static final String CONSENT_ACCEPT = "consent.accept";
    /** View recorded consent evidence. */
    public static final String CONSENT_READ = "consent.read";
    /** Publish and retire consent document versions. */
    public static final String CONSENT_MANAGE_VERSIONS = "consent.manage_versions";

    // --- vitals ------------------------------------------------------
    /** Submit vitals. */
    public static final String VITALS_SUBMIT = "vitals.submit";
    /** View submitted vitals. */
    public static final String VITALS_READ = "vitals.read";
    /** Record submitted vitals in the offline EHR and mark them verified. */
    public static final String VITALS_VERIFY = "vitals.verify";

    // --- upload ------------------------------------------------------
    /** Upload a supporting document or result. */
    public static final String UPLOAD_CREATE = "upload.create";
    /** View uploaded documents. */
    public static final String UPLOAD_READ = "upload.read";
    /** Quarantine or release a scanned upload. */
    public static final String UPLOAD_QUARANTINE = "upload.quarantine";

    // --- schedule ----------------------------------------------------
    /** Publish consultation dates and generate slots. */
    public static final String SCHEDULE_PUBLISH = "schedule.publish";
    /** View published schedules. */
    public static final String SCHEDULE_READ = "schedule.read";
    /** Place a temporary hold on a slot. */
    public static final String SLOT_HOLD = "slot.hold";
    /** View slot availability. */
    public static final String SLOT_READ = "slot.read";
    /** Request an appointment. */
    public static final String APPOINTMENT_REQUEST = "appointment.request";
    /** View appointments across the service. */
    public static final String APPOINTMENT_READ = "appointment.read";
    /** View own appointments. */
    public static final String APPOINTMENT_READ_OWN = "appointment.read_own";
    /** Approve a requested appointment. */
    public static final String APPOINTMENT_APPROVE = "appointment.approve";
    /** Reject a requested appointment. */
    public static final String APPOINTMENT_REJECT = "appointment.reject";
    /** Assign the consulting doctor. */
    public static final String APPOINTMENT_ASSIGN_DOCTOR = "appointment.assign_doctor";
    /** Assign pharmacy, laboratory, nursing and HIM. */
    public static final String APPOINTMENT_ASSIGN_TEAM = "appointment.assign_team";
    /** Assign the consultation room. */
    public static final String APPOINTMENT_ASSIGN_ROOM = "appointment.assign_room";
    /** Cancel an appointment. */
    public static final String APPOINTMENT_CANCEL = "appointment.cancel";
    /** Reschedule an appointment. */
    public static final String APPOINTMENT_RESCHEDULE = "appointment.reschedule";
    /** Record a no-show. */
    public static final String APPOINTMENT_MARK_NO_SHOW = "appointment.mark_no_show";

    // --- room --------------------------------------------------------
    /** View rooms. */
    public static final String ROOM_READ = "room.read";
    /** Create, amend and deactivate rooms. */
    public static final String ROOM_MANAGE = "room.manage";
    /** View doctor availability. */
    public static final String DOCTOR_AVAILABILITY_READ = "doctor_availability.read";
    /** Set doctor availability. */
    public static final String DOCTOR_AVAILABILITY_MANAGE = "doctor_availability.manage";

    // --- consultation ------------------------------------------------
    /** Join a consultation as the clinician. */
    public static final String CONSULTATION_JOIN_AS_DOCTOR = "consultation.join_as_doctor";
    /** Join own consultation as the patient. */
    public static final String CONSULTATION_JOIN_AS_PATIENT = "consultation.join_as_patient";
    /** Join a consultation as the referring centre. */
    public static final String CONSULTATION_JOIN_AS_CENTRE = "consultation.join_as_centre";
    /** View consultation records and attendance. */
    public static final String CONSULTATION_READ = "consultation.read";
    /** End a session early and record the reason and safety action. */
    public static final String CONSULTATION_TERMINATE = "consultation.terminate";
    /** Fall back from video to audio. */
    public static final String CONSULTATION_SWITCH_MODALITY = "consultation.switch_modality";
    /** Start a recording where governance has approved it. */
    public static final String RECORDING_START = "recording.start";
    /** View a recording or transcript. */
    public static final String RECORDING_READ = "recording.read";

    // --- clinical ----------------------------------------------------
    /** Author a clinical note. */
    public static final String CLINICAL_NOTE_WRITE = "clinical_note.write";
    /** Read a clinical note. */
    public static final String CLINICAL_NOTE_READ = "clinical_note.read";
    /** Sign a clinical note. */
    public static final String CLINICAL_NOTE_SIGN = "clinical_note.sign";
    /** Author or supersede a prescription. */
    public static final String PRESCRIPTION_WRITE = "prescription.write";
    /** Read a prescription. */
    public static final String PRESCRIPTION_READ = "prescription.read";
    /** Read own prescriptions. */
    public static final String PRESCRIPTION_READ_OWN = "prescription.read_own";
    /** Author an investigation request. */
    public static final String INVESTIGATION_WRITE = "investigation.write";
    /** Read an investigation request. */
    public static final String INVESTIGATION_READ = "investigation.read";
    /** Read own investigation requests. */
    public static final String INVESTIGATION_READ_OWN = "investigation.read_own";
    /** Record a follow-up recommendation. */
    public static final String FOLLOW_UP_WRITE = "follow_up.write";
    /** Read follow-up recommendations. */
    public static final String FOLLOW_UP_READ = "follow_up.read";
    /** Read own follow-up recommendations. */
    public static final String FOLLOW_UP_READ_OWN = "follow_up.read_own";

    // --- review ------------------------------------------------------
    /** Transcribe and professionally verify a prescription. */
    public static final String REVIEW_PHARMACY = "review.pharmacy";
    /** Transcribe and review an investigation request. */
    public static final String REVIEW_LABORATORY = "review.laboratory";
    /** Submit a completed review forward to the Hub Coordinator. */
    public static final String REVIEW_SUBMIT_TO_HUB = "review.submit_to_hub";
    /** View review status and history. */
    public static final String REVIEW_READ = "review.read";

    // --- release -----------------------------------------------------
    /** View bundle completeness. */
    public static final String RELEASE_BUNDLE_READ = "release_bundle.read";
    /** Release a complete clinical bundle. */
    public static final String RELEASE_BUNDLE_RELEASE = "release_bundle.release";

    // --- queue -------------------------------------------------------
    /** Work the nursing preparation queue. */
    public static final String QUEUE_NURSING = "queue.nursing";
    /** Work the health information management queue. */
    public static final String QUEUE_HIM = "queue.him";

    // --- document ----------------------------------------------------
    /** Issue a verifiable clinical document. */
    public static final String DOCUMENT_ISSUE = "document.issue";
    /** View issued documents. */
    public static final String DOCUMENT_READ = "document.read";
    /** View own issued documents. */
    public static final String DOCUMENT_READ_OWN = "document.read_own";
    /** Download an issued document within its limit. */
    public static final String DOCUMENT_DOWNLOAD = "document.download";
    /** Revoke an issued document. */
    public static final String DOCUMENT_REVOKE = "document.revoke";

    // --- finance -----------------------------------------------------
    /** Start a payment. */
    public static final String PAYMENT_INITIATE = "payment.initiate";
    /** View payments across the service. */
    public static final String PAYMENT_READ = "payment.read";
    /** View own payments. */
    public static final String PAYMENT_READ_OWN = "payment.read_own";
    /** Run and review reconciliation. */
    public static final String PAYMENT_RECONCILE = "payment.reconcile";
    /** Work failed, pending, reversed and unmatched transactions. */
    public static final String PAYMENT_EXCEPTION_HANDLE = "payment.exception_handle";
    /** Issue a refund. */
    public static final String PAYMENT_REFUND = "payment.refund";
    /** View centre wallet balances and thresholds. */
    public static final String WALLET_READ_BALANCE = "wallet.read_balance";
    /** Credit a centre wallet from programme funding. */
    public static final String WALLET_CREDIT = "wallet.credit";
    /** Read the centre wallet ledger. */
    public static final String WALLET_READ_LEDGER = "wallet.read_ledger";
    /** Generate financial and reconciliation reports. */
    public static final String FINANCE_REPORT_READ = "finance_report.read";

    // --- centre ------------------------------------------------------
    /** View all centres. */
    public static final String CENTRE_READ = "centre.read";
    /** View own centre. */
    public static final String CENTRE_READ_OWN = "centre.read_own";
    /** Create a centre. */
    public static final String CENTRE_CREATE = "centre.create";
    /** Amend a centre. */
    public static final String CENTRE_UPDATE = "centre.update";
    /** Activate or suspend a centre. */
    public static final String CENTRE_ACTIVATE = "centre.activate";
    /** Activate optional local pharmacy, laboratory or HIM roles at a centre. */
    public static final String CENTRE_CAPABILITY_MANAGE = "centre_capability.manage";
    /** View centre patient records. */
    public static final String CENTRE_PATIENT_READ = "centre_patient.read";
    /** Create a centre patient record. */
    public static final String CENTRE_PATIENT_CREATE = "centre_patient.create";
    /** Amend a centre patient record. */
    public static final String CENTRE_PATIENT_UPDATE = "centre_patient.update";
    /** Submit a referral and appointment request. */
    public static final String CENTRE_REFERRAL_CREATE = "centre_referral.create";
    /** View submitted referrals. */
    public static final String CENTRE_REFERRAL_READ = "centre_referral.read";
    /** View released care bundles. */
    public static final String CENTRE_BUNDLE_READ = "centre_bundle.read";
    /** Mark a released bundle as treated. */
    public static final String CENTRE_BUNDLE_MARK_TREATED = "centre_bundle.mark_treated";
    /** View centre consultation and utilisation counts. */
    public static final String CENTRE_REPORT_READ = "centre_report.read";

    // --- notification ------------------------------------------------
    /** Read own notifications. */
    public static final String NOTIFICATION_READ_OWN = "notification.read_own";
    /** Send an operational notification. */
    public static final String NOTIFICATION_SEND = "notification.send";
    /** Manage notification templates. */
    public static final String NOTIFICATION_MANAGE_TEMPLATES = "notification.manage_templates";

    // --- support -----------------------------------------------------
    /** Raise a support ticket. */
    public static final String TICKET_CREATE = "ticket.create";
    /** View all support tickets. */
    public static final String TICKET_READ = "ticket.read";
    /** View own support tickets. */
    public static final String TICKET_READ_OWN = "ticket.read_own";
    /** Assign a ticket to an agent. */
    public static final String TICKET_ASSIGN = "ticket.assign";
    /** Respond to a ticket. */
    public static final String TICKET_RESPOND = "ticket.respond";
    /** Escalate a ticket. */
    public static final String TICKET_ESCALATE = "ticket.escalate";
    /** Close a ticket. */
    public static final String TICKET_CLOSE = "ticket.close";

    // --- audit -------------------------------------------------------
    /** Read the audit trail. */
    public static final String AUDIT_READ = "audit.read";
    /** Export an audit report. */
    public static final String AUDIT_EXPORT = "audit.export";

    // --- config ------------------------------------------------------
    /** View system configuration. */
    public static final String CONFIG_READ = "config.read";
    /** Change system configuration with a recorded reason. */
    public static final String CONFIG_UPDATE = "config.update";

    // --- system ------------------------------------------------------
    /** View service health and integration status. */
    public static final String SYSTEM_HEALTH_READ = "system.health_read";

}
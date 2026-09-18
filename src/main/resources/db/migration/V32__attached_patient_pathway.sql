-- Wording supplied in the patient prototype. Existing published policies are preserved.
INSERT INTO consent_documents (public_id,created_at,created_by,version,audience,title,body,status,effective_from,published_by)
SELECT '01FNPHCONSENTATTACHMENT001',NOW(6),'migration','FNPH-TP-CONSENT-01','FNPH_PATIENT','Patient consent and data-protection notice','01 Nature of telepsychiatry
Telepsychiatry provides psychiatric assessment, consultation and approved follow-up through secure digital communication. Consultations are video-first; audio may be used when video cannot be used safely or reliably. The quality of remote care depends on devices, connectivity, privacy and information available to the clinician.

02 Eligibility and emergency exclusions
This patient service is for verified existing FNPH Kaduna patients who have already received an in-person assessment and are suitable for non-emergency remote follow-up. Psychiatric emergencies, first-time acute psychosis, severe agitation, immediate risk of harm and conditions requiring urgent physical assessment are excluded.

03 Voluntary participation and accurate information
Participation is voluntary. You agree to provide accurate symptoms, vital signs and documents. The clinician may stop or redirect a consultation when remote care is unsafe, the setting is not private or respectful participation is not possible.

04 Privacy and authorised access
Personal and medical information must be handled under approved FNPH policies and applicable data-protection obligations. Access is restricted by role. You should protect your login and join from a quiet, private environment.

05 Information collected and its use
The service may process identity and EHR details, contact information, symptoms, vital signs, uploaded results, appointments, payments and consultation information to provide care, coordinate follow-up, manage the service and meet legal obligations.

06 Security, storage and retention
Approved systems should use authentication, authorisation, audit trails and appropriate encryption. Information is kept for the periods required by clinical, legal, regulatory and hospital policy, not indefinitely merely because storage is available.

07 Your data-protection rights
Subject to applicable limits, you may ask how your information is used, request access or correction, seek restriction of some processing, withdraw consent where applicable and raise a complaint. Withdrawal does not automatically erase records the hospital must lawfully retain.

08 Recording and transcription
Recording or transcription is allowed only under an approved hospital policy with the required information and consent. It is not automatically enabled by joining a video room. A separate recording decision may be requested before a session.

09 Timing and patient responsibilities
Sessions use a fixed 30-minute appointment. Joining late does not extend the end time. You are responsible for punctuality, a charged device, reasonable connectivity, truthful information, respectful behaviour and a private setting.

10 Payment, withdrawal and contact
Payment is completed later in the booking journey. You may contact the hospital about this notice or withdrawing from the service, subject to clinical and legal record duties. FNPH clinical support: 0803 272 2243.','PUBLISHED',NOW(6),'attached-patient-pathway'
WHERE NOT EXISTS (SELECT 1 FROM consent_documents WHERE audience='FNPH_PATIENT' AND status='PUBLISHED');
INSERT INTO triage_question_sets (public_id,created_at,created_by,version,audience,status,effective_from)
SELECT '01FNPHTRIAGEATTACHMENT0001',NOW(6),'migration','FNPH-TP-TRIAGE-01','FNPH_PATIENT','PUBLISHED',NOW(6)
WHERE NOT EXISTS (SELECT 1 FROM triage_question_sets WHERE audience='FNPH_PATIENT' AND status='PUBLISHED');
INSERT INTO triage_questions (public_id,created_at,created_by,question_set_id,sequence,question_text,stop_answer,stop_reason)
SELECT '01FNPHTRIAGEQUESTION000001',NOW(6),'migration',id,1,'Are you in immediate danger of harming yourself or another person?','YES','Urgent in-person assessment is needed'
FROM triage_question_sets WHERE public_id='01FNPHTRIAGEATTACHMENT0001';
INSERT INTO triage_questions (public_id,created_at,created_by,question_set_id,sequence,question_text,stop_answer,stop_reason)
SELECT '01FNPHTRIAGEQUESTION000002',NOW(6),'migration',id,2,'Are you having suicidal thoughts without a responsible person physically present to support you?','YES','Urgent in-person assessment is needed'
FROM triage_question_sets WHERE public_id='01FNPHTRIAGEATTACHMENT0001';
INSERT INTO triage_questions (public_id,created_at,created_by,question_set_id,sequence,question_text,stop_answer,stop_reason)
SELECT '01FNPHTRIAGEQUESTION000003',NOW(6),'migration',id,3,'Is this your first episode of acute psychosis, severe confusion, hallucinations or loss of contact with reality?','YES','Urgent in-person assessment is needed'
FROM triage_question_sets WHERE public_id='01FNPHTRIAGEATTACHMENT0001';
INSERT INTO triage_questions (public_id,created_at,created_by,question_set_id,sequence,question_text,stop_answer,stop_reason)
SELECT '01FNPHTRIAGEQUESTION000004',NOW(6),'migration',id,4,'Are you severely agitated or unable to participate safely by video or audio?','YES','Urgent in-person assessment is needed'
FROM triage_question_sets WHERE public_id='01FNPHTRIAGEATTACHMENT0001';
INSERT INTO triage_questions (public_id,created_at,created_by,question_set_id,sequence,question_text,stop_answer,stop_reason)
SELECT '01FNPHTRIAGEQUESTION000005',NOW(6),'migration',id,5,'Do you need immediate physical medical attention or have you been advised that admission may be required?','YES','Urgent in-person assessment is needed'
FROM triage_question_sets WHERE public_id='01FNPHTRIAGEATTACHMENT0001';

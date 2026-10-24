package com.fnph.telepsychiatric.centre.api;

import com.fnph.telepsychiatric.centre.CentreReferral;
import com.fnph.telepsychiatric.clinical.CentreVitals;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shapes FNPH staff read about a centre referral, in one place.
 *
 * The centre's referral form asks for the assessment, current condition,
 * medicines and previous results. They were saved and never shown: the doctor
 * and the hub saw only the one-line reason, so the centre's work was invisible
 * to the people it was written for.
 */
public final class CentreClinicalViews {

    private CentreClinicalViews() {
    }

    public static Map<String, Object> referral(CentreReferral r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("reference", r.getReference());
        row.put("urgency", r.getUrgency() == null ? null : r.getUrgency().name());
        row.put("reason", r.getReferralReason());
        row.put("assessment", r.getAssessment());
        row.put("currentCondition", r.getCurrentCondition());
        row.put("relevantMedicines", r.getRelevantMedicines());
        row.put("previousResults", r.getPreviousResults());
        row.put("submittedAt", r.getSubmittedAt());
        return row;
    }

    public static Map<String, Object> vitals(CentreVitals v) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicId", v.getPublicId());
        row.put("measuredAt", v.getMeasuredAt());
        row.put("systolic", v.getBloodPressureSystolic());
        row.put("diastolic", v.getBloodPressureDiastolic());
        row.put("heartRate", v.getHeartRate());
        row.put("respiratoryRate", v.getRespiratoryRate());
        row.put("temperature", v.getTemperature());
        row.put("weightKg", v.getWeightKg());
        row.put("heightCm", v.getHeightCm());
        row.put("bmi", v.getBmi());
        row.put("bloodOxygen", v.getBloodOxygen());
        row.put("bloodGlucose", v.getBloodGlucose());
        row.put("notes", v.getNotes());
        row.put("recordedBy", v.getCreatedBy());
        return row;
    }
}

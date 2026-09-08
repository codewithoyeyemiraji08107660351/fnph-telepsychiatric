package com.fnph.telepsychiatric.authz;

/**
 * Which security boundary a role belongs to.
 *
 * The boundaries are not a labelling convenience. A centre credential must be
 * rejected by the patient application and vice versa, and that check is made
 * against this value rather than against a list of role names that someone has
 * to remember to extend.
 */
public enum RoleScope {

    /** Hospital staff. Single tenant. No centre_id on the account. */
    FNPH,

    /** Verified FNPH Kaduna patients. Own records only. */
    PATIENT,

    /** Centre of Excellence staff. Always bound to exactly one centre. */
    CENTRE
}

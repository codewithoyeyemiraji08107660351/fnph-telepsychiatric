package com.fnph.telepsychiatric.user;

public enum UserStatus {

    /**
     * Created by an administrator, invitation sent, no password set yet.
     * Cannot authenticate. Distinct from DEACTIVATED so an administrator can
     * see who has not taken up their invitation.
     */
    INVITED,

    ACTIVE,

    /** Temporarily barred, for example during an investigation. Reversible. */
    SUSPENDED,

    /**
     * Access removed. Clinical, financial and audit history is retained in
     * full, which is why this is a status rather than a delete.
     */
    DEACTIVATED
}

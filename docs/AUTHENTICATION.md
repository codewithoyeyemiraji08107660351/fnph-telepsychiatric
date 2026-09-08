# Authentication and sessions

What Day 4 built, and why each decision went the way it did.

---

## Who gets an account, and how

**Staff and centre accounts are created by invitation.** An administrator holding
`user.create` creates the account in `INVITED` state and the system emails a
single-use setup link. The account cannot sign in until that link is used.

**Patients are deliberately excluded from this flow.** A patient account is never
created by an administrator and never receives an invitation email. An
unsolicited email naming someone as a patient of a neuropsychiatric hospital
discloses their care to anyone with access to that inbox: a shared family
address, a work account, a phone showing previews on a locked screen. That
disclosure would happen before the person had agreed to anything, and it is the
exact harm the rest of the privacy design exists to prevent.

Passing a `PATIENT` role to the create endpoint is rejected with 400. Patients
enrol themselves by verifying an existing FNPH EHR number, which is the EHR
verification milestone.

**No password is ever generated or emailed.** The invitation carries a link and
the recipient chooses their own. A generated password sits in that inbox and in
the mail provider's storage for the life of the account, and neither is under
FNPH's control.

---

## The sign-in sequence

```
POST /api/v1/auth/login
      │
      ├─ status AUTHENTICATED           → done, use the tokens
      ├─ status MFA_REQUIRED            → POST /auth/mfa/verify
      └─ status MFA_ENROLMENT_REQUIRED  → POST /auth/mfa/enrol
                                          POST /auth/mfa/activate
```

The client reads `status` and branches. It never has to know which roles require
a second factor.

`mfaToken` lives five minutes, is typed `CHALLENGE` so it cannot be presented as
an access token, carries no role or permission claims, and is accepted only on
the MFA endpoints. Holding one without completing the challenge grants nothing.

---

## Decisions worth understanding

### Every sign-in failure returns the same message

Wrong password, unknown username, locked account, deactivated account and
unactivated invitation all produce the same response and roughly the same
timing. A missing account still runs a bcrypt comparison against a dummy hash so
it does not return measurably faster.

Distinguishing them would turn the sign-in form into a way to discover which
staff and which EHR numbers hold accounts at a neuropsychiatric hospital. That
is a disclosure in itself, before anyone gets in. The real outcome is recorded in
`login_attempts`, where staff with the right permission can see it.

The same reasoning governs `POST /auth/password/forgot`, which always returns
202 whether or not the account exists.

### Rate limiting runs two ways at once

Five failures against one username in fifteen minutes locks that account for
thirty. Thirty failures from one address across any usernames blocks the address.

Both are needed. Username-only lockout lets an attacker spray one password
across many accounts without ever tripping a threshold, and it also lets them
lock a named clinician out of a clinical system on demand. Address-only limiting
is useless against a distributed attempt. The per-address threshold is higher
because a shared hospital connection puts a whole department behind one address.

### Refresh tokens rotate, and reuse revokes everything

Every refresh mints a new token and marks the old one replaced. Tokens descended
from one sign-in share a `family_id`.

Presenting a token that was already replaced revokes the entire family. That is
either a client retrying after a dropped response, or a stolen token being used
alongside the real one. The server cannot tell them apart, so it assumes the
worse case. Occasionally losing a session is a much better outcome than letting a
stolen token run for seven days against psychiatric records.

### Tokens are stored hashed, TOTP secrets encrypted

Refresh tokens, invitation links, reset links and recovery codes are compared by
SHA-256 hash. A read-only leak through a backup, a log or a query does not hand
over working credentials.

SHA-256 rather than bcrypt here on purpose: these carry 256 bits from a
cryptographic source, so guessing is already impossible, and a deliberately slow
hash would only add latency to every refresh.

TOTP secrets cannot be hashed, because the server has to reproduce the code the
app shows. They are encrypted with AES-256-GCM instead. A leak of clear TOTP
secrets is worse than a password leak, because valid second factors could be
generated indefinitely and nobody would know to rotate anything.

**`ENCRYPTION_KEY` is effectively permanent.** Rotating it makes every enrolled
factor undecryptable and forces every user to enrol again.

### Second factor for staff and centre accounts, not patients

Required for `FNPH` and `CENTRE` scopes. Not required for `PATIENT`.

Making someone enrol a TOTP app to attend a psychiatric appointment is a barrier
that would stop people attending, and a patient account cannot approve bookings,
move money or read anyone else's record. The risk and the friction are not in
balance there. Patient accounts get contact verification and rate limiting.

TOTP only. `SMS` and `EMAIL` exist in the enum but are not enabled: SMS delivery
in Nigeria is unreliable enough that making it the second factor would lock staff
out of a clinical system during a network outage, and SIM swap is a real attack
here.

The implementation is forty lines of HMAC rather than a dependency, and it is
verified against all six RFC 6238 published vectors. A subtly wrong TOTP
implementation still verifies against itself, so it passes every naive test and
then fails against the app on a clinician's phone. The published vectors are the
only thing that proves interoperability.

### Password policy is length, not composition

Twelve characters minimum, a blocklist of the obvious choices, and rejection of
passwords containing the account's own username, email or name. No forced
uppercase, digit or symbol, and no periodic rotation.

Composition rules reliably produce `Password1!` and nothing better, because
people satisfy the rule in the cheapest way available. This follows NIST 800-63B.
All problems are returned at once rather than one at a time, so the user is not
made to guess repeatedly.

### Inactivity is measured from last use

`last_seen_at` advances on each authenticated request, and the timeout is
measured from there rather than from `issued_at`. An actively used session is not
cut off mid-consultation at a fixed interval, while an abandoned one on a shared
clinic workstation still expires.

---

## Emails sent

All six are account emails. **None contains clinical detail**, because they reach
inboxes that may be shared, synced to a phone or backed up outside Nigeria. Every
one carries the emergency notice, because someone reaching for this service in a
crisis needs the right number rather than a sign-in page.

| Email | When | Why it exists |
|---|---|---|
| Invitation | Account created | The setup link. No password inside |
| Password reset | Reset requested | The reset link. Says plainly that ignoring it is safe |
| Password changed | Any password change | A change the holder did not make is the only signal they will get that someone else is in the account |
| New device | First sign-in from an unrecognised device | Same reason |
| Roles changed | Administrator changed access | The holder should know what they can now do, and that someone decided it |
| Account deactivated | Access removed | With the reason, and a note that history is retained |

Sent asynchronously. A mail server timeout never fails the operation that
triggered it; the account is created, the failure is logged, and the invitation
can be resent. Addresses are masked in logs so the log does not become a
directory of who holds an account here.

---

## Endpoints

### Public

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/auth/login` | Username and password |
| POST | `/api/v1/auth/mfa/verify` | Second factor, TOTP or recovery code |
| POST | `/api/v1/auth/mfa/enrol` | Start authenticator setup, returns QR URI |
| POST | `/api/v1/auth/mfa/activate` | Confirm and sign in, returns recovery codes once |
| POST | `/api/v1/auth/refresh` | Rotate the token pair |
| POST | `/api/v1/auth/logout` | End this session |
| GET | `/api/v1/auth/activation` | Check an invitation link |
| POST | `/api/v1/auth/activation` | Set password, activate, verify address |
| POST | `/api/v1/auth/password/forgot` | Request a reset. Always 202 |
| POST | `/api/v1/auth/password/reset` | Set a new password, sign out everywhere |

### Authenticated

| Method | Path | Requires |
|---|---|---|
| POST | `/api/v1/auth/password/change` | Any session |
| GET | `/api/v1/me` | Any session |
| GET | `/api/v1/sessions` | Any session |
| DELETE | `/api/v1/sessions/{id}` | Any session, own sessions only |
| DELETE | `/api/v1/sessions` | Any session |

### Administration

| Method | Path | Permission |
|---|---|---|
| POST | `/api/v1/admin/users` | `user.create` |
| POST | `/api/v1/admin/users/{id}/invitation` | `user.create` |
| POST | `/api/v1/admin/users/{id}/deactivate` | `user.deactivate` |
| DELETE | `/api/v1/admin/users/{id}/sessions` | `session.revoke` |
| DELETE | `/api/v1/admin/users/{id}/mfa` | `mfa.reset` |

`mfa.reset` deserves care in operation, not just in code. It is the one control
that removes the second factor from a clinical account, which makes it the
obvious target for a phone call claiming to be a colleague who has lost their
phone. Every use is logged with the administrator's identity.

---

## Configuration

```yaml
application:
  security:
    jwt:
      access-token-minutes: 15
      refresh-token-days: 7
      inactivity-timeout-minutes: 30
    encryption:
      key: ${ENCRYPTION_KEY}          # 32 bytes base64. Effectively permanent.
    auth:
      max-failed-attempts: 5          # per username
      max-failed-attempts-per-ip: 30  # per address
      failure-window-minutes: 15
      lockout-minutes: 30
      activation-token-hours: 72
      password-reset-token-minutes: 60
      max-mfa-attempts: 5
      mfa-challenge-minutes: 5
      recovery-code-count: 10
      min-password-length: 12
```

Generate the encryption key with `openssl rand -base64 32`, or on Windows the
PowerShell equivalent in `docs/DOCKER.md` section 4.

---

## Still open

**Behind Nginx, `X-Forwarded-For` decides the client address for rate limiting.**
Only the first entry is trusted, and only because the proxy is configured to
overwrite rather than append the header. If that configuration ever changes, the
header becomes spoofable and rate limiting becomes bypassable. Confirm it when
the reverse proxy is set up.

**No SMS provider is chosen**, so notifications are email and in-app only.

**Session cleanup is not scheduled yet.** Expired rows accumulate. A nightly job
belongs with the rest of the scheduled work.

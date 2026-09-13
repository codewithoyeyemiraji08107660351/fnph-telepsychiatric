# Fix commit — what changed and why

Everything here addresses defects found in the analysis of the existing repo.
No new feature work. The goal is an application that starts, a schema that can
be rebuilt from zero, and a security configuration that would survive review.

The migrations in this commit were executed against a live MySQL-compatible
server before it was written. All 26 tables, 59 foreign keys, 17 unique keys
and 2 check constraints apply cleanly, and each constraint was tested for the
behaviour it is meant to enforce.

---

## 1. The application could not start

Four separate causes, all fixed.

**`CentreConsultation` had no identifier.** Annotated `@Entity` and `@Table`
but did not extend `BaseEntity` and declared no `@Id`. Hibernate fails at
bootstrap on an entity with no identifier. Now extends `BaseEntity`, and while
open it was brought to parity with `Consultation`: termination reason, identity
confirmation, audio fallback, escalation instruction, scheduled start and end,
and the two countdown warning timestamps. The centre pathway needs all of
those as much as the FNPH pathway does.

**`Users.roleOverrides` carried two conflicting mappings on one field.**
`@ManyToMany` with a `@JoinTable(name = "user_roles_override")` and
`@ElementCollection` with a `@CollectionTable(name = "user_role_overrides")`
on the same property. Only one can apply. The field is deleted. A `Role` and
`Permission` model replaces it in the identity milestone so the permission
matrix lives as data rather than in code.

**No `@EnableJpaAuditing` anywhere.** `BaseEntity` declares `@CreatedDate` on
a `NOT NULL` column. Without the auditing configuration that field is never
populated, so every insert would have failed at runtime. Added in
`config/JpaAuditingConfig`, together with an `AuditorAware` that resolves the
current principal.

**`SecurityConfig` injected an `AuthenticationProvider` that did not exist.**
There was also no `PasswordEncoder`. Both beans are now defined in
`config/ApplicationConfig`, with BCrypt at strength 12 and
`hideUserNotFoundExceptions` enabled so the sign-in endpoint cannot be used to
discover which EHR numbers hold accounts.

---

## 2. Security

**Ride-hailing rules removed from `SecurityConfig`.** The file contained
`hasRole("ADMIN")` and `hasAnyRole("DRIVER", "PASSENGER")`. None of those exist
in the `Roles` enum, so every one of those matchers was dead and the paths they
appeared to protect were open to any authenticated user. Replaced with matchers
against the fourteen real roles, mapped to the API groups in the specification,
and `anyRequest().denyAll()` so a new endpoint is closed until it is
deliberately opened.

**CORS locked down.** It was `allowedOriginPatterns("*")` with
`allowCredentials(true)`, which lets any website on the internet make
authenticated requests against patient records. Origins now come from an
explicit per-environment allow-list in `application.cors.allowed-origins`.

**JWT rebuilt.** Access tokens were 24 hours with no rotation, and the refresh
token lived in a single column on the user row, meaning one session per account
and no way to revoke a lost device without signing the user out everywhere.
Access tokens are now 15 minutes, refresh tokens are separate and typed so one
cannot be presented as the other, and the refresh token carries a `jti` for the
session record that lands in the identity milestone. The `refreshToken` and
`tokenExpiry` columns are gone.

**`jjwt` upgraded 0.11.5 to 0.12.6** and `JwtService` rewritten against the
current API. Staying on 0.11 would have meant rewriting it later anyway, after
more code depended on it.

**`JwtAuthenticationEntryPoint` no longer returns HTTP 200.** It had a branch
that answered `200 OK` to unauthenticated requests under `/api/v1/auth/`. Those
paths are `permitAll` so it was unreachable, but a handler capable of answering
200 to an authentication failure does not belong in this codebase.

**`UserDetailServiceImpl` fixed.** It resolved by email only, while
`Users.getUsername()` returned the EHR number for patients. EHR-number sign-in
could never have worked. Lookup is now username then email, and the EHR number
is written into the username column at activation so all three login types
converge on one query. The `getUsername()` override is removed.

**`JwtAuthFilter`** now uses `shouldNotFilter` rather than an inline path check,
catches parse failures instead of letting them escape as 500s, and rejects
tokens for disabled or locked accounts.

---

## 3. Schema management

`ddl-auto: update` is replaced by Flyway with `ddl-auto: validate`.

- `db/migration/V1__baseline_schema.sql` — 26 tables, generated from the
  corrected entities and verified against a running server.
- `db/migration/V2__seed_centres.sql` — the 23 Kaduna State LGA centres, each
  created inactive with a zero-balance wallet.
- `db/ops/append_only_grants.sql` — revokes UPDATE and DELETE on `audit_logs`
  and `wallet_transactions` from the application account. Deliberately not a
  Flyway migration: if the application could grant itself write access back,
  revoking it would prove nothing.

Two check constraints enforce in the database what would otherwise be a service
layer convention: a prescription and an investigation each belong to exactly one
pathway, never both and never neither.

---

## 4. Timezone

The datasource was set to `serverTimezone=Africa/Kaduna`, which is not a
canonical IANA zone, and Jackson used the same value. Mixing display timezone
into storage is how appointment times drift after a restart or a server move.

Storage is now UTC end to end, with `hibernate.jdbc.time_zone: UTC`. Jackson
renders `Africa/Lagos` at the presentation boundary.

---

## 5. Enum splits

Five enums each carried two or three unrelated concepts, which made the stored
data unqueryable and unreportable.

| Removed | Replaced by |
|---|---|
| `NotificationStatus` (type + channel + status, 21 values, used for all three fields) | `NotificationType`, `NotificationChannel`, `DeliveryStatus` |
| `FileStatus` (purpose + scan state) | `FileCategory`, `ScanStatus` |
| `TransactionStatus` (direction + lifecycle) | `LedgerDirection`, `LedgerEntryStatus` |
| `PaymentStatus` (status + two purposes) | `PaymentStatus`, `PaymentPurpose` |
| `clinical.Status` (11 values shared across three entities) | `ClinicalDocumentStatus`, `FollowUpStatus` |

`Payment.paymentType` was typed as `TransactionStatus`, a ledger enum. It is now
`purpose` of type `PaymentPurpose`.

`ClinicalDocumentStatus` has no transition back to `DRAFT`. Reviews travel
forward to the Hub Coordinator and never return to the doctor through the
system, which matches the approved workflow. A correction is issued as a new
document that supersedes the previous one, which is what `supersedes_id` is for.

---

## 6. Wallet balance stored in three places

`Center` carried `walletBalance`, `lowBalanceThreshold` and
`criticalBalanceThreshold`, duplicating the same three fields on `Wallet`, while
`WalletTransaction` separately recorded before and after balances.

The `Center` columns are removed. `Wallet.balance` is now documented and treated
as a cached projection reconciled against the ledger, with an `@Version` field
so two simultaneous centre bookings cannot both debit from the same read.
`WalletTransaction` is append-only, gains a `reverses_transaction_id` so a
correction is a new entry rather than an edit, and its `appointmentReference`
String is now a real foreign key.

---

## 7. Soft delete on immutable records

`BaseEntity` carried a `deleted` flag, which applied to `AuditLog` and
`WalletTransaction` among everything else. Audit and financial ledger rows must
not be deletable.

`BaseEntity` is now mutable-but-not-deletable. `SoftDeletableEntity` adds the
delete flag plus who, when and why. `ImmutableEntity` has an id and a creation
timestamp only, and `AuditLog` and `WalletTransaction` extend it. The database
grants in `db/ops` back this up at a level the application cannot override.

---

## 8. Structural corrections made while in the files

These were in the analysis and were cheap to do now rather than later.

**`Prescription` and `Investigation` split into header and items.** Both were
single-row: one medication, one investigation type. The doctor workspace adds
multiple medicines to one prescription and selects multiple panels on one
request. `PrescriptionItem` and `InvestigationItem` added.

**Clinical outputs are dual-owned.** `Prescription`, `Investigation` and
`FollowUp` now carry nullable references to both `Consultation` and
`CentreConsultation`. Before this the centre pathway had a consultation note and
nothing else, so the centre release bundle, which is defined as note plus
reviewed prescription plus reviewed investigation plus follow-up, could not be
assembled at all.

**Document and review concerns removed from the clinical entities.** Download
counters, QR fields and reviewer fields were duplicated on both `Prescription`
and `Investigation`, which guarantees they drift apart. They move to
`IssuedDocument` and `ProfessionalReview` in the clinical outputs milestone.
`DocumentVerification` also loses its stored base64 `qrCode` column; the image
is generated on demand from the token.

**`Appointment` gained the multidisciplinary team.** `CentreAppointment` already
assigned pharmacy and laboratory; the FNPH pathway assigns the same team and had
no fields for it. Also added nursing and HIM completion timestamps, the join
window, and an `@Version` for the slot concurrency gate.

**`isEmergency` and `emergencyNote` removed from `Appointment`.** Emergencies
are excluded from this service by design. A flag inviting staff to mark an
appointment as one is a safety hazard, not a feature.

**`Patient` trimmed to the agreed minimum field set.** Email, address and the
three emergency contact fields removed; none is used by any workflow in the
documents. Added `sourceImportId`, `contactVerifiedAt`, `activatedAt` and a
`driftFlagged` group. Drift matters: silently rebinding an active account to
changed contact details from a later EHR import is an account takeover path, so
the change is flagged to Health Information Management rather than applied.

**`CentrePatient` tenancy hardened.** Unique on `(centre_id, centre_patient_id)`
so local ids are centre-scoped, which is verified by test. `fnph_ehr_number` is
documented as narrative only and must never be joined to `patients`.

**`FileUpload.filePath` became `storageBucket` plus `storageKey`.** A local
filesystem path does not survive a redeploy and cannot be read by a second
application node, which the production topology requires. Checksum is now
mandatory, and quarantine has its own timestamp and reason.

**`Consultation` lost its `recordingUrl` and `transcriptUrl` columns.** Consent,
retention and deletion need to be modelled, not implied by a nullable string.
`Recording` and `Transcript` entities land with the consultation milestone.
`application.clinical.recording-enabled` ships as `false`.

---

## 9. Configuration

Split into `application.yaml` plus dev and prod profiles. Every secret is now an
environment variable with no default, and `.env.example` documents the full set.
The previous file had `username: root` and a hard-coded port.

The clinical timing values are seeded as separate keys:

```
room-open-lead-minutes: 15
joining-grace-minutes: 5
no-show-cutoff-minutes: 15
warning-one-minutes-remaining: 15
warning-two-minutes-remaining: 10
session-minutes-fnph: 30
session-minutes-centre: 30
```

The source documents conflate the first three into a single number and disagree
with each other about what it is. Conflating them in configuration would
reproduce the bug in code, so they are separate from the start. Session duration
is per audience rather than global, so a centre change cannot silently move FNPH
appointments.

---

## 10. Dependencies added

Flyway, Redis, AMQP, Thymeleaf, ZXing, AWS S3 SDK, Resilience4j, Caffeine,
MapStruct, Micrometer Prometheus, Sentry, logstash encoder, Testcontainers,
spring-security-test, REST Assured, Awaitility.

Removed: `gson` and `commons-validator`, both redundant against Jackson and Bean
Validation, and two of the three duplicate Lombok declarations. The `<n>` tag in
the POM, which should have been `<name>`, is also fixed.

---

## What was deliberately not done in this commit

**No renames.** `Users` to `User` and `Center` to `Centre` touch every file, and
mixing a mechanical rename into a commit of behavioural fixes makes the diff
unreviewable. That is its own commit, and it should happen before the repo grows.

**No new modules.** EHR verification, scheduling, the webhook inbox, release
bundles, issued documents, professional review, system configuration and the
session and role tables are all still missing. They are the next milestones, in
the order given in the entity specification.

**Not compiled.** The build environment used to prepare this has no access to
Maven Central, so `mvn verify` has not been run against these changes. The
migrations were executed and verified; the Java was checked structurally. Run
`./mvnw clean verify` before pushing and expect a small number of import
adjustments in your IDE.

---

## Verification steps

```bash
# 1. Build
./mvnw clean verify

# 2. Fresh schema from zero
mysql -e "DROP DATABASE IF EXISTS telepsychiatric; CREATE DATABASE telepsychiatric;"
./mvnw flyway:migrate

# 3. Start. Hibernate validates the entities against the migrated schema.
#    A mismatch fails startup, which is the point.
./mvnw spring-boot:run

# 4. Apply the append-only grants as an administrator
mysql < src/main/resources/db/ops/append_only_grants.sql

# 5. Confirm the audit trail cannot be altered by the application account
#    Both statements must fail.
#    UPDATE audit_logs SET action = 'tampered' WHERE id = 1;
#    DELETE FROM wallet_transactions WHERE id = 1;
```

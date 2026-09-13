# Testing procedure

Read this before running anything. The order matters more than the coverage.

**Where the build actually stands: nothing has ever been compiled.** Every
migration has been replayed against a live MySQL and every constraint checked by
SQL, but no compiler has seen the Java. Stage 1 will fail on the first run.
That is expected and it is the point of doing it first.

---

## Stage 0 — get a compiler running

The Maven TLS failure on the Windows machine is the blocker. Docker sidesteps it
entirely, because the container's JDK trusts a normal certificate store and
whatever is intercepting TLS on the host is not in the path.

```powershell
docker run --rm -it `
  -v "${PWD}:/app" `
  -v "${env:USERPROFILE}\.m2:/root/.m2" `
  -w /app maven:3.9-eclipse-temurin-21 `
  mvn -U clean compile
```

If you would rather fix the host, try this first, in order. Stop at whichever
works.

```powershell
$env:MAVEN_OPTS = "-Dhttps.protocols=TLSv1.2 -Djdk.tls.client.protocols=TLSv1.2 -Djava.net.preferIPv4Stack=true"
.\mvnw -U clean compile
```

If that does not fix it, the cause is almost certainly antivirus or a corporate
proxy re-signing TLS. `curl` succeeds where the JDK fails because curl uses the
Windows certificate store and the JDK uses its own. Either import the
interception certificate into the JDK truststore, or use Docker and move on.
**Do not spend a day on this.** Docker is a legitimate answer.

---

## Stage 1 — compile

```bash
scripts/test.sh compile
```

**Expect errors.** 344 Java files were written without a compiler ever running.
The realistic failures are:

- missing or wrong imports, especially across the `clinical`, `document` and
  `centre` packages which reference each other
- getter and setter names that do not match a Lombok-generated one
- enum values referenced that were renamed later, which already bit once with
  `Outcome.TERMINATED` and `Outcome.NOT_ATTENDED`
- constructor and record signatures that drifted between a service and its DTO

These are mechanical. Work through them top to bottom rather than jumping around;
one wrong import often produces twenty errors.

**What would be a real problem, and probably is not:** a circular dependency
between services that Spring refuses to start. If `PaymentService` and
`BookingService` end up requiring each other, break it with an event rather than
`@Lazy`.

Do not move on until this is clean.

---

## Stage 2 — unit tests, no database

```bash
scripts/test.sh unit
```

Eleven classes, seconds to run, no Docker. These check logic that has to be right
before anything built on it is worth testing:

| Class | What it protects |
|---|---|
| `PublicIdTest` | ULID generation, no ambiguous characters, no collisions over 200k |
| `TokensTest` | Token entropy, constant-time comparison |
| `TotpServiceTest` | All six RFC 6238 vectors including the 2033 rollover |
| `PasswordPolicyTest` | NIST 800-63B rules, all problems returned at once |
| `AuditChainTest` | Six tampering modes, including re-hashing after an edit |
| `SupervisionRulesTest` | Supervision context fails closed and clears |
| `SessionClockTest` | Late join shortens the session, never moves the end |
| `PhoneNormalisationTest` | Nigerian number formats all match |
| `PaymentRulesTest` | Credit arithmetic, and money compared by value not scale |
| `BookingSequenceTest` | Wallet credited on payment, spent on approval, kept on rejection |
| `TenantScopeTest` | Scope defaults to constrained, restores after widening |

A failure here is a genuine logic defect, not a wiring problem.

---

## Stage 3 — schema and migrations

```bash
scripts/test.sh schema
```

Needs Docker. Runs against a real MySQL 8.4 container, deliberately not H2: the
schema depends on MySQL behaviour that H2 in compatibility mode gets wrong,
which would give passing tests for a schema production rejects.

Fourteen classes. `MigrationReplayTest` runs all 17 migrations from empty, which
is the single most valuable test in the suite — it is what proves a fresh
deployment works.

The rest assert boundaries that a later change could remove without anyone
noticing what they protected: the permission matrix (126 permissions, 16 roles),
tenant isolation (14 cases), and one schema class per module.

**If `PermissionMatrixTest` fails**, someone has changed a role's permissions.
That is either a deliberate decision that needs the test updating with a reason,
or an accident. It is never "just update the test".

---

## Stage 4 — acceptance gates

```bash
scripts/test.sh gates
```

These are the five FNPH named. Four are automated; the fifth is a drill.

### 1. Slot concurrency

`SlotConcurrencyAcceptanceTest`. Twenty threads, twenty connections, a start
barrier so they all reach the same slot at the same instant. Exactly one may
succeed.

**Already verified against real MySQL: 20 concurrent claims produced 1
appointment, slot version 1.**

A second test bypasses the service-layer lock entirely and proves the unique
index on `appointments.slot_id` still refuses the double booking. That is what
protects the system if someone later removes the `SELECT ... FOR UPDATE`, which
is the realistic way this regresses.

### 2. Duplicate provider callbacks

`DuplicateWebhookAcceptanceTest`. Ten identical Remita callbacks delivered
simultaneously. Exactly one is stored.

**Already verified: 10 concurrent deliveries produced 1 row.**

A second test proves a genuine later status change for the same order is *not*
suppressed. The idempotency key is the payload, not the order, and getting that
backwards would swallow real events.

### 3. Cross-tenant isolation

`TenantIsolationTest`, 14 cases. List queries, altered identifiers, existence
checks, counts, derived queries, writes, and the fail-closed default.

Case 13 is the one that matters long term: it scans every entity for a
`centre_id` and fails if one is not tenant-enforced. That catches the entity
somebody adds in six months without reading any of this.

### 4. Audit reconstruction

`AuditChainTest` plus `GET /api/v1/admin/audit/verify` on a running instance.

The endpoint walks every row and reports where the chain breaks. Run it during
assurance testing and show FNPH the output. **A break is an incident, not a
warning.**

### 5. Restore from backup

Not automated, and should not be. Run it as a drill:

```bash
scripts/backup.sh                  # take one
scripts/restore.sh <backup-file>   # restore to a scratch database
```

The script verifies the checksum, records the binary log position and reports
elapsed time against the 2-hour RTO. **Do this at least once before pilot and
time it honestly.** A backup nobody has restored is not a backup.

---

## Stage 5 — what no automated test covers

Be honest with FNPH about this list rather than letting them assume.

**No integration test runs a full journey.** Enrol, book, pay, join, prescribe,
review, release, download has never been executed end to end. This is the biggest
gap in the suite and it needs doing manually before pilot, ideally scripted
afterwards.

**The video and payment providers have never been called.** Remita's endpoint
shapes came from published documentation and vary between merchant
configurations. Daily's room and token calls are unexercised. Both need a
sandbox run against real credentials before anyone relies on them.

**Performance is unmeasured.** No load test exists. Do at least one before
pilot: 50 concurrent booking attempts and 20 simultaneous consultations, and
watch the connection pool.

**Scheduled jobs are not wired.** The methods exist, the scheduler does not, so
nothing currently releases lapsed slot holds, expires documents, publishes the
outbox, or runs reconciliation. Until they are wired there is nothing to test.

---

## Manual test script for pilot readiness

Run this once, by hand, on a deployed instance. It is the walkthrough to do in
front of FNPH.

**Setup:** upload and activate an EHR snapshot containing a test patient. Publish
a schedule day. Set a doctor available. Credit a centre wallet.

**FNPH patient journey**

1. Enrol with the EHR number and date of birth. Confirm the masked details, the
   snapshot date and its age are all shown.
2. Enrol again with a wrong date of birth. Confirm the message is identical to a
   wrong EHR number. **If the two differ, stop; the enumeration defence is
   broken.**
3. Choose a slot. Confirm it disappears for a second browser.
4. Abandon the payment. Wait for the hold to lapse. Confirm the slot returns.
5. Choose again, pay in the Remita sandbox, return. Confirm the payment verifies
   server-side and the wallet is credited.
6. As Hub Coordinator, confirm the request is on the dashboard. Reject it.
   Confirm the wallet keeps the balance and the patient is told.
7. Book again. Confirm nothing is payable and no Remita call happens.
8. Approve, assigning doctor, nurse, room, pharmacy, laboratory, HIM. Confirm
   every assignee is notified with room, date and time.
9. Try to join 30 minutes early. Confirm refusal with the wait time.
10. Join at the window. Confirm the countdown, and that the patient sees
    "Consultant" rather than the doctor's name.
11. Let a warning fire. Confirm both warnings appear once each.
12. Write a note, sign it, issue a prescription, mark investigation not required
    with a reason.
13. As pharmacist, confirm the clinical note is not visible anywhere. Submit the
    review with a query.
14. As Hub Coordinator, confirm the query arrived and the bundle is READY.
    Release it.
15. As patient, download the prescription. Download again. Confirm the second is
    refused and the document is still readable.
16. Scan the QR. Confirm no patient name appears in the response.
17. Revoke it. Scan the same QR. Confirm it now says REVOKED.

**Centre journey**

18. Register a centre patient, create a referral, record consent, submit.
19. Request an appointment. Approve it. Confirm the centre wallet debits and the
    balance is not visible to the centre.
20. Run the consultation, release the bundle. Confirm it lands in the centre's
    incoming queue and mark it treated.
21. From a second centre's account, try to open the first centre's patient by
    identifier. **Confirm a 404, not a 403.** A 403 would confirm the record
    exists.

**Administration**

22. Open a supervised session on the doctor. Confirm read access works and every
    write control is unavailable.
23. Check the audit trail shows both identities on those actions.
24. Run `/admin/audit/verify`. Confirm the chain is intact.
25. Change the consultation fee. Confirm the reason is mandatory and the history
    shows the previous value.

---

## What "passing" means

Stages 1 to 4 green means the schema is sound, the boundaries hold, and the
logic is right where it is tested.

It does not mean the system works. That comes from the manual script above and
from a sandbox run against real Remita and Daily credentials. Say that plainly
to FNPH rather than letting a green suite carry more weight than it has earned.

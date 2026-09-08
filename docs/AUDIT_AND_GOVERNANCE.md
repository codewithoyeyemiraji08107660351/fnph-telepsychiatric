# Audit, supervised access and configuration

Day 6. Three things that decide whether this system can be assessed rather than
just demonstrated.

---

## Supervised access is read-only

This is the decision worth arguing about, so here is the argument.

The Central Administrator can open any staff dashboard and see exactly what that
person sees. That is what makes a support call about "the release button is
greyed out" answerable without asking the clinician to describe their screen.

They cannot write a clinical note, sign one, issue or supersede a prescription,
submit a professional review, approve a booking, move money, or download a
patient's document.

**Not caution.** If an administrator could write in a clinician's name, the
resulting note would be indistinguishable afterwards from one the clinician
wrote, and the specification states plainly that clinician-authored content is
not altered by anyone else. A supervision feature that can forge clinical
authorship is not a supervision feature.

### How it is enforced

Every permission carries `is_mutating`. During supervision the effective
authority set is the target's non-mutating permissions only.

The rule that set the flag: a permission is non-mutating exactly when its action
is a read. Everything else defaults to mutating, which is the safe direction, so
a permission added next year is excluded from supervision until someone
deliberately says otherwise. 47 of 126 permissions are non-mutating.

`document.download` is mutating on purpose. It consumes the patient's single
allowed download, and an administrator looking at a dashboard must not burn it.

### Other rules

**Patient accounts cannot be supervised.** Reading a patient's record is a
records access under `patient.read`, audited by patient. Dressing it up as
supervision would hide it inside a mode built for staff dashboards, where it
would be filed under "administrator viewed a dashboard" rather than
"administrator read this person's record".

**One session at a time.** Two open sessions would make the audit trail
ambiguous about which one an action belonged to.

**Sessions expire.** An administrator who walks away does not leave the mode
running.

**Self-supervision is refused by a database check constraint**, not just by
service code. It would produce an audit trail that says nothing.

---

## The audit trail is tamper-evident, not tamper-proof

That distinction is the honest one, and it is what the design delivers.

**Layer one: database grants.** `db/ops/append_only_grants.sql` revokes UPDATE
and DELETE on `audit_logs` from the application account. The application cannot
alter the trail even with a bug or an injection.

**Layer two: a hash chain.** Grants stop the application. They do not stop
someone holding database credentials, and "the audit trail cannot be altered" is
a claim that has to survive that person existing.

Each row's `chain_hash` covers its own content plus the previous row's
`chain_hash`. Editing or removing any row breaks every hash after it.

An attacker who knows the algorithm can edit a row and recompute its own hash.
That fixes one row and breaks the link into the next, which is exactly the point:
covering the tracks means rewriting every subsequent row, and doing that
undetectably means holding the whole table. Verified against six tampering modes,
including the re-hash attempt and truncating the front of the chain.

`GET /api/v1/admin/audit/verify` walks the chain and reports where it breaks,
naming the entry and whether a row was removed, inserted or edited in place. **A
break is an incident, not a warning.**

### Two identities on every row

During supervision, `username` is the account that authenticated and
`effectivePrincipal` is the account being acted as. "Who did this" has two
correct answers in that case and an investigation needs both.

`GET /api/v1/admin/audit/supervision/{id}` returns everything done inside one
supervised session, which is the question an auditor actually asks. Supervised
access is read-only, so those entries should all be reads; one recording a state
change means the enforcement has a hole.

### Why audit is written explicitly, not by a persistence listener

A Hibernate listener can see that `prescriptions.status` changed from
`PENDING_REVIEW` to `RELEASED`. It cannot see that a Hub Coordinator released a
clinical bundle for a named patient after a pharmacy review, which is the only
version of that event an auditor can use. Intent and reason exist at the service
boundary and nowhere below it.

The cost is that a developer has to remember. The mitigation is that the auditable
actions are a closed enum and code review names them. Free text would produce six
spellings of the same event within a year, and an auditor filtering on "show me
every time someone opened a clinician's dashboard" needs a value, not a phrase.

### Audit writes run in their own transaction

`REQUIRES_NEW`. If a clinical operation fails and rolls back, the attempt still
happened and the record of it must survive. An audit row that disappears with the
failed operation is exactly the row an investigation would want.

A failed audit write never takes down the operation it describes; it is logged at
error so a persistent failure shows up in monitoring rather than silently
producing a trail with holes in it.

---

## Configuration is data, with history

24 settings across seven categories, 19 of them owned by FNPH governance rather
than operations.

Fees, timing values, thresholds and validity periods change without a release. In
a properties file, a fee change is a deployment, which in practice means it does
not happen and the wrong number stays live.

### Every change keeps the previous value and a reason

`configuration_changes` is append-only, including a seeded row for each initial
value so history starts at creation rather than at the first edit. Without that,
the first change would show a previous value of nothing.

This is what settles a reconciliation dispute. Six months from now the question is
which consultation fee was in force on a given day and who decided it. "Updated"
answers neither, so the reason has a 15-character minimum.

### Bounds are checked on write

A consultation fee of zero or a session length of four hours is a typo. Catching
it costs one method; catching it in a reconciliation report a month later costs a
refund process.

`no_show_cutoff_minutes` is bounded 5 to 30. `session_minutes_fnph` is 20 to 60.
`consultation_fee_ngn` has a floor of 0 and a ceiling of 1,000,000.

### Reads never fall back to a default

A missing key throws. Returning a sensible default means a typo in a key name
silently produces a 30-minute session where the configured value was 40, with
nothing anywhere saying so. Failing loudly is better than being quietly wrong for
months.

`ConfigurationKeys` holds a constant per key, and `GovernanceSchemaTest` asserts
every constant exists in the database, so the two cannot drift.

### The three timing keys stay separate

`room_open_lead_minutes` is how early the join control activates.
`joining_grace_minutes` is how late a patient may be before being flagged.
`no_show_cutoff_minutes` is when the link deactivates.

The source documents give three different numbers for what reads like one
setting: five minutes in the consent text, fifteen in the specification, fifteen
in the prototype for a different parameter entirely. They are three things.
Merging them in configuration would reproduce the ambiguity in code, where it is
much harder to see.

Session length is per audience for the same reason: a global key would let a
Centre change silently move FNPH appointments. A test asserts both.

### Caching

Reads are cached with a 60-second expiry and eviction on write. Eviction handles
changes made through this instance; the expiry bounds how long a second
application node can serve a stale fee after the first changed it. Two nodes are
the production topology, so this is not hypothetical.

---

## Endpoints

| Method | Path | Permission |
|---|---|---|
| POST | `/api/v1/admin/supervision` | `supervision.view_as` |
| DELETE | `/api/v1/admin/supervision/{id}` | `supervision.view_as` |
| GET | `/api/v1/admin/audit` | `audit.read` |
| GET | `/api/v1/admin/audit/supervision/{id}` | `supervision.read_log` |
| GET | `/api/v1/admin/audit/verify` | `audit.export` |
| GET | `/api/v1/admin/configuration` | `config.read` |
| GET | `/api/v1/admin/configuration/{key}/history` | `config.read` |
| PUT | `/api/v1/admin/configuration/{key}` | `config.update` |

All eight are Central Administrator only. `audit.read`, `config.update`,
`supervision.view_as` and `supervision.read_log` are held by no other role.

While a supervised session is open the client sends `X-View-As-Session`. The
server looks the session up rather than trusting the header: it must exist,
belong to the calling administrator, and still be open. The header is a view
hint, not a grant.

---

## Tests

```bash
./mvnw test -Dtest='AuditChainTest,SupervisionRulesTest,GovernanceSchemaTest'
```

`AuditChainTest` covers six tampering modes: intact, edited, removed, inserted,
re-hashed after editing, and front truncation.

`GovernanceSchemaTest` asserts every key constant is seeded, the timing keys are
separate, session length is per audience, recording ships disabled, every setting
has history, no state-changing permission is marked read-only, and self
supervision is blocked by a database constraint.

---

## Still open

**The chain has no external anchor.** Someone with database credentials and
enough time could rewrite the whole table consistently. Publishing a periodic
digest somewhere outside the database, even an email to FNPH management, would
close that. Worth doing before go-live.

**Scheduled jobs are not wired yet.** Closing expired supervised sessions and
verifying the chain should both run nightly. The methods exist; the scheduler
does not.

**Audit retention is undefined.** Nothing is deleted today, which is correct for
now and will not scale forever. FNPH has to state a retention period, and it
interacts with the chain: removing old rows breaks it unless the chain is
segmented by period.

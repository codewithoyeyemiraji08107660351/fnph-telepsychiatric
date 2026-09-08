# Tenant isolation

23 Centres of Excellence share one database. A centre must not reach another
centre's data by any route. This is how that is enforced, and why it is enforced
where it is.

---

## Where the guarantee lives

**In the repository base class, not in services or controllers.**

Services and controllers are written one at a time, by different people, over
months. Every one of them would have to remember to add a centre filter, and the
single place somebody forgets is the leak. Worse, it would leak silently: the
query returns rows, the page renders, nobody sees an error.

`TenantAwareRepository` is registered as the base class for every Spring Data
repository through one line in `JpaRepositoryConfig`. A repository added next
year inherits the enforcement without its author doing anything, or knowing it
exists.

---

## Two mechanisms, because one is not enough

### 1. The Hibernate filter

`@FilterDef` and `@Filter` on every tenant-owned entity append
`centre_id = :centreId` to the generated SQL. This covers JPQL, criteria queries
and derived query methods alike, so a repository method written later is
constrained without mentioning a centre.

### 2. An explicit check on `findById`

Hibernate filters are documented **not to apply to `EntityManager.find()`**.

That is exactly the gap the acceptance criteria test. Altering a record
identifier in a URL goes straight to a primary key lookup, which the filter
would happily let through. Relying on the filter alone gives false confidence
precisely where the attack lands.

So `findById`, `getReferenceById` and `existsById` verify the returned row's
centre against the caller's, and throw if they differ.

`existsById` is routed through `findById` rather than a count query for the same
reason. A bare existence check that ignored the tenant would answer "yes, that
record exists" about another centre's patient, which is the disclosure the whole
design is there to prevent.

### Writes are checked too

Reads are the obvious risk and the one everyone tests. The quieter one is a
centre coordinator posting a referral carrying another centre's identifier, which
would create a record inside someone else's tenant. `save` and `delete` verify
ownership before proceeding.

---

## Cross-tenant access returns 404, never 403

A 403 confirms the record exists. On this system that turns a URL into a way to
discover that a named person is a patient at a specific centre, which is a
disclosure on its own even though no data was returned.

From outside, "not found" and "not yours" are indistinguishable. The attempt is
logged, and staff holding `audit.read` can see the difference.

---

## The scopes

| Scope | Filter | Who |
|---|---|---|
| `centre(id)` | Constrained to that centre | Centre coordinators, assistants, and the optional local roles |
| `hospital(reason)` | Unrestricted | FNPH staff |
| `none()` | Constrained with no centre, matches nothing | Patients, unauthenticated requests, scheduled jobs |

**Hospital scope is not a loophole.** The Hub Coordinator approves bookings from
every centre and the doctor consults for all of them, so FNPH staff genuinely
work across the estate. What they cannot do is reach a centre's data through a
centre account. The reason string is carried into the audit trail, so widened
scope always has a stated justification attached.

**The default fails closed.** An unset context is `none()`, which is constrained
with a null centre and resolves to `centre_id = -1`. A bug that leaves the
context unset returns no rows rather than every centre's rows. That direction of
failure is the single most important property here: failing open would be silent
and total.

---

## The context is cleared in a finally block

`TenantContextFilter` sets the scope from the authenticated principal and clears
it after the request, always.

That is not defensive habit. Servlet containers reuse threads. A scope left
behind would be inherited by whichever request the pool hands that thread to
next, and the symptom would be one centre intermittently seeing another's data
under load. Rare, load-dependent, and a disclosure every time. It is the worst
class of bug this system could have.

The same reasoning applies to `TenantContext.runAcrossAllCentres`, which
restores the previous scope in a finally block so an exception inside a widened
block cannot leak the widening.

---

## Two entities carry a tenant key without being filtered

Both are deliberate, and both are asserted in the test suite rather than trusted
to a comment.

**`Users`** — authentication has to find the account before any tenant scope
exists, so a filter here would make signing in impossible. Access to other
people's accounts is guarded by `user.read`, which no centre role holds.

**`AuditLog`** — append-only, and readable only by holders of `audit.read`,
which no centre role holds. Filtering it would also break the Central
Administrator's cross-centre audit view, which is the one place a cross-tenant
attempt becomes visible.

Test 14 asserts no centre role holds `user.read`, `user.create`, `user.update`,
`audit.read` or `audit.export`. If that ever changes, the exception becomes a
hole and the build fails.

---

## Capabilities replaced three booleans

`centres` carried `has_pharmacy_capability`, `has_laboratory_capability` and
`has_him_capability`. Activating an optional local role is a Central
Administrator decision taken, in the specification's words, "after staffing and
capability review". A boolean records the answer and destroys the review.

`centre_capabilities` holds one row per centre per capability, with who enabled
or disabled it, when, and why. Withdrawal records whether the pharmacist simply
left or the centre failed an audit, which should not look identical a year later.

**Withdrawing a capability does not strip the role from anyone who already holds
it.** Silently removing access from a working account mid-shift would strand
clinical work with no explanation to the person doing it. Deactivating those
accounts is a separate, visible decision.

Centres also gained a lifecycle: `SETUP`, `ACTIVE`, `SUSPENDED`. A centre in
`SETUP` has no coordinator and must not appear in a booking list, because a
released bundle with nobody to receive it strands clinical output. All 23 seed as
`SETUP`.

---

## The test suite

`TenantIsolationTest`, fourteen cases. It stays green or the build stops.

| # | Asserts |
|---|---|
| 01 | A list query returns only the caller's rows |
| 02 | An altered identifier throws rather than returning another centre's record |
| 03 | A centre can still read its own record by identifier |
| 04 | An existence check does not confirm another centre's record |
| 05 | A count reflects only the caller's centre |
| 06 | A derived query method is scoped without being told to |
| 07 | A centre cannot write a row into another centre |
| 08 | Hospital staff legitimately see every centre |
| 09 | An unset context returns nothing rather than everything |
| 10 | A centre principal with no centre bound sees nothing |
| 11 | A patient principal reaches no centre data |
| 12 | Widening scope for a job restores it afterwards |
| 13 | Every entity carrying `centre_id` implements `TenantOwned` |
| 14 | No centre role holds the permissions guarding the two exceptions |

Test 13 catches the real long-term failure mode: an entity added in six months by
someone who has never read this document. It scans for `centre_id` and fails if
the entity is not enforced.

```bash
./mvnw test -Dtest='TenantIsolationTest,TenantScopeTest'
```

Runs against a real MySQL 8.4 container. The whole mechanism is a Hibernate
filter emitting SQL, and an H2 dialect that accepted it would prove nothing about
production.

---

## Still open

**Native queries bypass the filter.** Hibernate filters apply to JPQL and
criteria queries, not to `@Query(nativeQuery = true)`. There are none today. Any
added later against a tenant-owned table must include the centre predicate by
hand, and that is a code review item, not something the framework catches.

**Associations reached by navigation are not re-checked.** Loading a permitted
row and following a reference into another aggregate is not verified by the
repository. Services validate before crossing an aggregate boundary; there is no
automatic guard.

**Reporting queries across centres will need explicit widening.** Utilisation
reports for the Central Administrator span the estate. Those go through
`runAcrossAllCentres` with a stated reason rather than by disabling the filter
ad hoc.

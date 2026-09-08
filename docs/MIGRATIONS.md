# Schema conventions and migration discipline

The rules in this document are enforced by `SchemaConventionsTest` and
`MigrationReplayTest`, which run on every build. A convention nobody checks is a
convention that decays.

---

## The six columns every table carries

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | Primary key. Internal only. Never appears in a URL, an API response or a document. |
| `public_id` | `VARCHAR(26)` unique | ULID. The only identifier any external surface sees. |
| `created_at` | `DATETIME(6)` | UTC. Set by JPA auditing. |
| `created_by` | `VARCHAR(100)` | Resolved from the security context. |
| `updated_at` | `DATETIME(6)` | Mutable tables only. |
| `updated_by` | `VARCHAR(100)` | Mutable tables only. |

`audit_logs` and `wallet_transactions` are append-only and deliberately have no
`updated_at` or `updated_by`. A test asserts they never gain them.

---

## Four decisions worth understanding

### Two identifiers, not one

The primary key is a sequential `BIGINT` because InnoDB clusters the table on
it, so inserts append to the end of the index rather than scattering across it.
A random UUID primary key on MySQL costs roughly an order of magnitude in write
throughput on a large table, and inflates every foreign key and secondary index
that references it.

But a sequential integer must never appear in a URL here. Anyone holding
`/api/v1/appointments/4471` knows the system has issued about 4,471
appointments, and can walk the range probing for authorisation gaps. The
acceptance criteria require that an altered identifier returns a denial. Making
the identifier unguessable removes the whole class of attack rather than relying
on every future endpoint remembering to check ownership.

ULID rather than UUIDv4 because it sorts by creation time, which keeps it usable
in an ordered index and in a document reference a human might read aloud.
Crockford base32 omits I, L, O and U, so it cannot be misread between 1 and I or
0 and O, and cannot accidentally spell a word.

Generated in `PublicId`, assigned in `@PrePersist`, verified over 200,000
identifiers by `PublicIdTest`.

### The tenant key lives on the row

Tenant isolation has to be one filter at the repository layer. That is only
possible if `centre_id` is on the row.

Six tables did not have it. `centre_consultations` could only be scoped by
joining through `centre_appointments`; `centre_consultation_notes` through two
tables. Scoping that depends on a join fails open the first time someone writes a
query without the join, and it fails silently, returning another centre's
clinical records rather than an error.

V4 denormalises `centre_id` onto every tenant-owned table. The cost is one
`BIGINT` per row. There is no exception list, because an exception is a table
that will eventually leak.

`prescriptions`, `investigations` and `follow_ups` serve both pathways: `NULL`
means FNPH, a value means Centre. Check constraints make the column and the
pathway unable to disagree.

### `DATETIME`, never `TIMESTAMP`

`TIMESTAMP` silently converts on read and write using the session timezone, and
tops out in 2038. `DATETIME` stores exactly what it is given, which is what a
UTC-everywhere policy needs. Enforced by test.

### Money is never a floating point type

Binary floating point cannot represent 0.10 exactly. `DECIMAL(19,2)` everywhere
for amounts, balances, fees and thresholds. Enforced by test against any column
whose name suggests money.

---

## Writing a migration

**Naming.** `V<n>__snake_case_description.sql`, numbered sequentially, two
underscores. `V5__ehr_verification.sql`.

**Forward only.** Never edit a migration that has been applied anywhere,
including your own machine. Flyway records a checksum; changing the file makes
every environment that already ran it fail to start. Correct a mistake with a
new migration.

**One concern per migration.** V3 does conventions, V4 does tenant scoping. A
migration that does three unrelated things cannot be reasoned about when it
fails halfway.

**Backfill before you tighten.** Add the column nullable, populate it from
wherever the value already lives, then set `NOT NULL`. V4 does this for all six
tables, so it is correct against a populated database as well as an empty one.

**Order your DDL so failures name themselves.** V3 adds every column, then
creates every index. A failure in the index phase reports which table it came
from rather than aborting mid-column.

**Never `flyway clean`.** It drops every object in the schema. On a database
holding clinical records and payment history there is no circumstance in which
that is the right command, so `clean-disabled: true` removes the capability
rather than trusting nobody to type it.

**`baseline-on-migrate` stays false.** Starting against a database that has
tables Flyway did not create means someone ran `ddl-auto` or applied SQL by
hand. Continuing produces two environments that silently differ.

**`out-of-order` stays false.** If two people both number a migration V7, that is
a conflict to resolve, not something to slot in quietly with the loser skipped.

---

## Applied migrations

| Version | Purpose |
|---|---|
| V1 | Baseline schema, 26 tables |
| V2 | The 23 Kaduna State LGA centres, each created inactive with a zero-balance wallet |
| V3 | `public_id`, `created_by`, `updated_by` on every table; removed the duplicated `posted_at` and `posted_by` from the ledger |
| V4 | `centre_id` denormalised onto every tenant-owned table, with pathway consistency constraints |

---

## Backup and restore

Recovery point target 15 minutes. Recovery time target 2 hours.

A nightly dump alone gives a recovery point of up to 24 hours. The binary log
closes the gap by replaying every write since the last full backup, which is why
`log-bin` and `sync-binlog=1` are set in `docker/mysql/fnph.cnf` and why
`--source-data=2` is passed to every dump: it writes the log position into the
dump so the replay knows where to start.

```bash
./scripts/backup.sh                  # or  .\scripts\backup.ps1
./scripts/restore.sh <file> --into telepsychiatric_restore_test
```

The scripts write a `.sha256` alongside every backup and refuse to restore a
file whose checksum does not match. `restore.sh` reports elapsed time and warns
if it exceeds the two-hour target.

**Run the restore monthly and record the time.** The recovery time target is only
real if it has been measured on the hardware you actually run. A backup that has
never been restored is an assumption, not a backup.

**A backup on the same host as the database is not a backup.** Copy it off-box,
encrypted, before the job counts as done.

---

## Verifying the whole thing

```bash
# Rebuilds the schema from zero in a real MySQL 8.4 container and asserts
# every convention above.
./mvnw test -Dtest='MigrationReplayTest,SchemaConventionsTest,PublicIdTest'
```

Testcontainers rather than H2 deliberately. The schema relies on MySQL behaviour
the acceptance criteria depend on: BIT columns, multiple NULLs under a unique
index, check constraints, InnoDB row locking. H2 in MySQL compatibility mode
passes tests that MySQL fails, which is worse than having no test.

Requires Docker running.

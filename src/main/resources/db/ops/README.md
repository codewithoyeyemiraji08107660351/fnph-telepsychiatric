# Database operations

## Order of execution

1. Create the schema owner and the application account.
2. Run the application once, or `mvn flyway:migrate`. V1 and V2 apply.
3. Run `append_only_grants.sql` as an administrator.
4. Confirm the two verification statements at the bottom of that file fail.

## Why the grants are not a Flyway migration

Flyway runs as the application account. If that account could grant itself
write access to the audit table, revoking it would prove nothing. The
revocation has to come from an account the application does not control.

## Backup and restore

Recovery point target is 15 minutes or less, recovery time target 2 hours
or less. Nightly encrypted full backups plus binary log shipping to an
independent location. Test the restore monthly and time it; the gate is a
demonstrated and timed restore, not a backup that exists.

-- Supervised access could not open four staff dashboards.
--
-- V8 classed a permission as non-mutating only when its code contains "read".
-- The Nurse, HIM, Pharmacist and Laboratory landing screens are gated on
-- queue.nursing, queue.him, review.pharmacy and review.laboratory, so a
-- supervising administrator was stopped at "This screen changes records" on
-- the first page of each.
--
-- These permissions gate both reading the queue (GET) and acting on it (POST).
-- Marking them non-mutating is safe because supervision no longer relies on
-- this flag alone: SupervisionContextFilter refuses every request other than
-- GET, HEAD or OPTIONS during a supervised session, whatever the permission.
--
-- is_mutating is read only by supervision (UserRoleRepository
-- .findNonMutatingPermissionCodesByUserId); nothing else changes.

UPDATE permissions
SET is_mutating = b'0'
WHERE code IN ('queue.nursing', 'queue.him', 'review.pharmacy', 'review.laboratory');

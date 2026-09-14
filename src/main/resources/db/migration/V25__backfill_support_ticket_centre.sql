-- V25__backfill_support_ticket_centre.sql
--
-- SupportTicket now carries the tenant filter. Rows created before it have a
-- null centre_id and would become invisible to the centre that raised them.
UPDATE support_tickets t
   JOIN users u ON u.id = t.raised_by_user_id
    SET t.centre_id = u.centre_id
  WHERE t.centre_id IS NULL
    AND u.centre_id IS NOT NULL;
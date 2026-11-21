-- Carries the support reply mails still waiting in support_mail_outbox into the event publication registry,
-- where the reply-mail listener and its retry policy pick them up: every row not sent and under six attempts
-- becomes a FAILED publication of SupportReplyMailRequested, keeping its id, attempt count (at least 1) and
-- creation time. A row with 0 attempts loses one of its six tries. The request's locale is read from
-- support_request because the event carries it.
--
-- A carried row is marked sent in the same statement, so an instance of the previous release that is still
-- running during a rolling deploy does not mail it a second time. The table itself is left in place and is
-- no longer read or written by this release: dropping it ships in a later release, once no instance of the
-- previous one can still insert into it, and that release repeats this statement first to carry any row
-- such an instance wrote after this one ran.
--
-- The serialized event is written in the property order the registry writes (record component order), so a
-- carried row reads like a published one. Sent rows and rows with six attempts are not carried.
-- Rollback: UPDATE support_mail_outbox SET sent_at = NULL WHERE id IN
-- (SELECT id FROM event_publication WHERE listener_id = 'support.reply-mail'), then
-- DELETE FROM event_publication WHERE listener_id = 'support.reply-mail'.
-- Re-running is safe: a row already carried is marked sent and is skipped.

WITH carried AS (
    INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                   completion_date, status, completion_attempts, last_resubmission_date)
    SELECT o.id,
           'support.reply-mail',
           'com.flowcortex.support.request.SupportReplyMailRequested',
           format('{"requestId":"%s","messageId":"%s","recipientUserId":"%s","requestNumber":%s,"locale":%s}',
                  o.request_id, o.message_id, o.recipient_user_id, r.number, to_json(r.locale)::text),
           o.created_at,
           NULL,
           'FAILED',
           GREATEST(o.attempts, 1),
           NULL
      FROM support_mail_outbox o
      JOIN support_request r ON r.id = o.request_id
     WHERE o.sent_at IS NULL
       AND o.attempts < 6
    ON CONFLICT (id) DO NOTHING
    RETURNING id
)
UPDATE support_mail_outbox
   SET sent_at = now(),
       updated_at = now()
 WHERE id IN (SELECT id FROM carried);

package com.example.skladdo.model;

/**
 * Where a {@link ScheduledEmail} is in its short life.
 *
 * <p>There is deliberately no {@code SENT} and no {@code CANCELLED}. A send that goes out is fully
 * recorded as {@link SentEmail} rows, so the schedule row has nothing left to say and is deleted;
 * cancelling one before it fires deletes it too, since nothing happened that anyone needs a record of
 * (who cancelled what is the audit log's job, not this table's). What is left in the table is therefore
 * always either work still to do or work that needs a human.</p>
 */
public enum ScheduledEmailStatus {

    /** Waiting for its time. The only status the dispatcher will pick up, and the only one editable. */
    PENDING,

    /**
     * Claimed by the dispatcher and being sent right now. Committed <em>before</em> the first message
     * goes out, so a process that dies mid-batch leaves the row here rather than back in PENDING - a
     * visibly stuck send is a much better failure than a silently duplicated one.
     */
    SENDING,

    /** The dispatcher could not send it at all (SMTP unconfigured, add-on lapsed). Kept, with a reason. */
    FAILED
}

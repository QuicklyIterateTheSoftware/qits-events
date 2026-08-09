-- The whole schema of qits-events, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE. The H2 lineage (V1 init, V2 payload, V3 parent_id, V4 the
-- SoftwareRelease cleanup, V5 the query indexes) was DELETED rather than continued, and it could be
-- because the move onto PostgreSQL is an unwrap and a re-bootstrap rather than a data migration: no
-- database anywhere is on the old lineage, so no `V6__move_to_postgres.sql` would have had a
-- reader. Those five files are history in this repository's log, and this file is where they
-- arrived, translated. FROM HERE ON THE ORDINARY RULE IS BACK: keep appending, never edit an
-- applied migration.
--
-- What the translation did, statement by statement:
--   * `clob` → `text`, for both `payload` and `description`. Same decision as before — no length a
--     schema could pick here would be anything but a guess, and neither column is queried by a
--     comparison an index could serve. The entity names no columnDefinition, so nothing in Java
--     moves with it.
--   * V4's `delete from Event where name = 'SoftwareRelease'` is GONE, and it has to be: it removed
--     three rows written under the old meaning of that name, on a database that no longer exists.
--     Every database reaching this file is empty, so the statement would delete nothing and only
--     read as an instruction to a future maintainer.
--   * V2's and V3's columns are declared in the table rather than added to it, and V5's index is
--     created beside V1's and V3's. The reasoning that justified each is kept here, where the
--     column is.
--
-- The table is `event`, unquoted: PostgreSQL folds an unquoted identifier to lower case and so does
-- Hibernate's naming strategy for the entity `Event`, so the two agree without a single quote in
-- either place.
--
-- Deliberately no foreign key to anything: an event that comes to name a project or a repository
-- names it by String id in a column of its own, because those rows live in another physical
-- database and no FK can span one (the platform-wide rule).
create table event (
    id varchar(255) not null,

    -- Short label, and — since this context became the platform's bus — the event's SIGNATURE, the
    -- string a websocket subscriber matches its interest against. One column serving both is
    -- deliberate; a separate signature field would be a second name that could disagree.
    name varchar(512) not null,

    -- The CALLER's time — when the thing happened, freely in the past — where created_at/updated_at
    -- are this row's, written by Hibernate. Collapsing them would make a backfilled event
    -- indistinguishable from one recorded as it happened. Precision 6 is the contract the write
    -- path truncates to: comparing a caller's nanoseconds against the database's microseconds would
    -- 400 a publisher's honest retry.
    occurred_at timestamp(6) with time zone not null,

    -- The machine half of an event: the publishing event class's own fields, as canonical JSON. It
    -- does NOT replace `description` — that is the human account, written for a person reading a
    -- timeline, while this is the structured facts a subscriber acts on.
    --
    -- NULLABLE, and permanently so: the manual POST path records events that are honestly nothing
    -- but a name and a time.
    --
    -- The server treats the string as opaque — it stores what the publisher canonicalized, compares
    -- it byte for byte when a PUT replays an id it already has, and hands it back verbatim.
    -- Canonicalization belongs to the publisher, so a server that reformatted this value would
    -- break exactly the equality the idempotent PUT is built on.
    payload text,

    -- The long-form account. Optional: a name and a time are the whole of what an event must have.
    description text,

    -- What caused this event, or null for a root — the platform's causation edge, and the only
    -- relation this table has. varchar(255) matches `id`; a narrower column would be a second
    -- opinion about what an id is. NULLABLE permanently: a root event has no cause, and `null` is
    -- the honest answer rather than a sentinel.
    --
    -- NO foreign key, not even a self-referential one, and this is the load-bearing omission rather
    -- than an application of the no-FKs-across-contexts rule. Nothing orders a parent's arrival
    -- before its child's: publishes are independent HTTP calls, and a parent whose inline attempt
    -- failed sits in the publisher's outbox for minutes while its child lands on the first try. An
    -- FK (or an existence check in the service) would refuse that child — and the refusal is a 400,
    -- which is unretryable, so the publisher's outbox would mark it FAILED and the event would be
    -- lost permanently over a fact that became true sixty seconds later. A dangling parent is DATA:
    -- the reader treats an unresolvable parent_id as the start of the chain.
    parent_id varchar(255),

    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,

    primary key (id)
);

-- The unfiltered page, the `since` floor and the cursor predicate are all ranges over occurred_at
-- read newest first, which is the only order an event log is ever read in.
create index idx_event_occurred_at on event (occurred_at);

-- The chain walk downwards — `where parent_id = ?`, once per hop. The log is append-only and
-- unbounded, so an unindexed scan per hop is the wrong shape before it is a slow one. A plain
-- single-column index is enough: ordering the children of one parent is an in-memory sort of a
-- handful of rows, so (parent_id, occurred_at) would buy nothing for twice the write cost.
create index idx_event_parent_id on event (parent_id);

-- `where name in (…) order by occurred_at desc`, which neither index above covers: with the
-- single-column one the database either scans the table for the names and sorts what it finds, or
-- walks occurred_at from the head discarding rows until the page fills — and the second is worst
-- exactly when the filter is most useful, because a name with few rows is a long walk. Leading with
-- `name` rather than with `occurred_at` is the whole design of it: equality first, range second.
-- The reverse order would be idx_event_occurred_at with a column stapled on.
create index idx_event_name_occurred_at on event (name, occurred_at);

-- `?q=` GETS NO INDEX, and that is a decision rather than an omission. It is a substring match on
-- the payload — `lower(payload) like '%…%'` — and a leading wildcard cannot use a b-tree at all.
-- Postgres would now permit what H2 could not (a trigram index, or full text over a tsvector
-- column), and both stay refused for the reason that was already written down: this service defines
-- the payload as OPAQUE and never parses it, so an index over its contents would be the first thing
-- to make that untrue. Measured instead of assumed: the log took 137 rows in its first day, peaking
-- at 27 in an hour, which is about 50,000 rows and 22 MB a year. Scanning that for a search a person
-- types by hand is honest. Revisit it when the number is different, not when the query looks slow in
-- the abstract.
--
-- No index on `id` beyond the primary key either: the cursor's tiebreaker reads
-- `occurred_at = ? and id < ?`, which is a handful of rows sharing one microsecond.

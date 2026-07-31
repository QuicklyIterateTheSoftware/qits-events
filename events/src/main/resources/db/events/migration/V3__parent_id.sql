-- What caused what. The bus records THAT things happened; until this column nothing recorded the
-- edge between them, and a release train — an event fires a build, the build publishes an event,
-- that event fires another build — was a set of unrelated rows distinguishable from coincidence
-- only by reading timestamps and guessing. One nullable edge is the whole feature: an event has at
-- most one parent, and a chain is that edge walked.
--
-- varchar(255), matching `id`. A parent id is an id of this table; a narrower column would be a
-- second opinion about what an id is.
--
-- NULLABLE, and permanently so: a root event has no cause, every row written before this migration
-- has none recorded, and `null` is the honest answer rather than a sentinel.
--
-- NO foreign key, not even a self-referential one — and this is the load-bearing omission, not an
-- application of the platform's no-FKs-across-contexts rule. Nothing orders a parent's arrival
-- before its child's: publishes are independent HTTP calls, and a parent whose inline attempt
-- failed sits in the publisher's outbox for minutes while its child lands on the first try. An FK
-- (or an existence check in the service) would refuse that child — and the refusal is a 400, which
-- is unretryable, so the publisher's outbox would mark it FAILED and the event would be lost
-- permanently over a fact that became true sixty seconds later. A dangling parent is DATA: the
-- reader treats an unresolvable parent_id as the start of the chain.
alter table Event add column parent_id varchar(255);

-- Indexed from the first row, because the read model is `where parent_id = ?` — the children of X —
-- and a chain-walk is that query once per hop. The log is append-only and unbounded, so an
-- unindexed scan per hop is the wrong shape before it is a slow one. A plain single-column index is
-- enough: ordering the children of one parent is an in-memory sort of a handful of rows, so
-- (parent_id, occurred_at) would buy nothing for twice the write cost.
create index idx_event_parent_id on Event (parent_id);

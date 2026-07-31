-- Events' own Flyway lineage, on its OWN named datasource — a separate physical H2 file, never
-- mixed with another service's schema. The table name matches the entity's simple name (no
-- @Table), the convention every qits context follows.
--
-- Deliberately no foreign key to anything: an event that comes to name a project or a repository
-- names it by String id in a column of its own, because those rows live in another physical
-- database and no FK can span one (the platform-wide rule).

-- occurred_at is the CALLER's time — when the thing happened, freely in the past — while
-- created_at/updated_at are this row's, written by Hibernate. The index is on occurred_at because
-- that is the only order an event log is ever read in.
create table Event (
    id varchar(255) not null,
    name varchar(512) not null,
    occurred_at timestamp(6) with time zone not null,
    description clob,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_event_occurred_at on Event (occurred_at);

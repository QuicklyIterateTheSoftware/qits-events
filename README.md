# qits-events

The **event** context of [qits](https://github.com/QuicklyIterateTheSoftware): a recorded thing that
happened, kept where the rest of the platform can read it.

It is a **skeleton**, and deliberately so. Unlike its siblings this repo was not extracted from the
monorepo with a body of code already in it — it starts as the *shape* every qits service has, with
the smallest amount of behaviour that makes that shape real, so the first feature is written against
a structure that is already right rather than one invented under pressure. What is here is the whole
of what is here; nothing below describes a plan.

## What it owns

| | |
|---|---|
| `Event` | a name, an `occurredAt`, an optional `payload`, an optional description and an optional `parentId` — plus the row's own `createdAt`/`updatedAt` |

The three timestamps are not redundant. `occurredAt` is the **caller's** — when the thing happened,
supplied on write and freely in the past, because a log is mostly written after the fact — while
`createdAt`/`updatedAt` are the row's, written by Hibernate. Collapse them and a backfilled event
becomes indistinguishable from one recorded as it happened, which is the one distinction an event
log exists to keep. Listing is ordered by `occurredAt`, never by insertion — and then by `id`,
because `occurredAt` ties: the events one pipeline run publishes carry the run's finish instant.

`parentId` is the id of the event that **caused** this one, or null for a root — the platform's
causation edge, and the one relation this table has. It records what a timeline cannot: a release
train is an event firing a build, the build publishing an event, that event firing another build,
and without the edge each hop is an unrelated row distinguishable from coincidence only by reading
timestamps and guessing.

**A `parentId` this log cannot resolve is data, not an error, and there is no foreign key.** Nothing
orders a parent's arrival before its child's — publishes are independent HTTP calls, and a parent
whose first attempt failed sits in the publisher's outbox for minutes while its child lands on the
first try. An existence check would refuse that child with a 400, which is unretryable, so the
publisher's outbox would mark it FAILED and a timing accident would become permanent data loss. The
same argument covers the first retention policy and a parent from a publisher this instance never
heard from. A reader treats an unresolvable parent as the start of the chain.

## What it deliberately does NOT own

Any relation to another context's rows. An event that comes to name a project, a repository or a
deployment will name it by **String id** in a column of its own — those rows live in another
physical database and no foreign key can span one. That is the platform-wide rule, not a
this-repo preference.

Any *view* of a causation chain. There is no `/chain` route, no depth parameter, no root filter and
no graph endpoint: `parentId` on a read walks upwards, `?parentId=` walks downwards, and a tree
endpoint would have to settle depth limits, cycle handling and ordering before anyone has drawn the
picture. **Nor is there a cycle guard.** One that caught only the self-edge would be worse than
none — it cannot see `A → B → A` while telling a reader that cycles have been handled — so a
chain-walking client bounds its own depth and remembers the ids it has visited.

There is no MCP server here. That was true of the websocket too until this service became the
platform's **bus**, and the sentence is worth keeping in its new form: a literal route is not free.
`/events/stream` earned its place by being the only way an event can reach anything without being
polled for, and it cost an entry in `quarkus.quinoa.ignored-path-prefixes` in the same commit.

## Layout

    events/   the entity, persistence, control, mapper and dto — a library jar, no JAX-RS
    service/  the REST boundary over it, the client, and the security mechanism — THE APPLICATION

`service/` carries `<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as
a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar          # :8080

    ./mvnw package -Dnative
    ./service/target/qits-events                                  # same routes

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain — the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
if it finds none it does not fail, it quietly falls back to pulling a 1.8 GB Mandrel image and
running the compile under docker. That fallback still works; it is just not the intended path, and
it is worth recognising by name when a compile that normally takes about a minute starts downloading
a container image.

Everything it serves sits under its gateway segment, `/events`:

| | |
|---|---|
| `/events/` | the Angular SPA, built from `service/src/main/webui` by Quinoa and served by this process (`quarkus.quinoa.ui-root-path`); unmatched paths under it fall back to `index.html`, so the client's own router gets its deep links — except under the prefixes below |
| `/events` | a 301 to `/events/`. Quinoa mounts at `/events/*`, which does not match the bare segment (upstream quinoa #960); `webui/WebUiRedirect` is this service's answer |
| `/events/api/events` | the REST surface (`quarkus.rest.path=/events/api`) |
| `/events/stream` | the event stream socket — a `@WebSocket` literal, which follows `quarkus.rest.path` for nothing and carries the segment itself |
| `/events/q/openapi`, `/events/q/swagger-ui` | the API document and its UI (`quarkus.http.non-application-root-path`) |
| `/events/q/health/ready` | the readiness endpoint qits-cd's health gate curls |

qits-gateway routes verbatim by prefix — `/events/*` → this service, no rewriting — so the segment
is served here or the service is not reachable through it. There is no unprefixed form.

The SPA takes the *whole* segment, so it is the one that can swallow the rest: the deep-link
fallback answers anything under `/events` that matched no route, with `200 text/html`. That is right
for a person and wrong for a machine, which parses `index.html` as garbage data. Quinoa **derives**
the exclusion list from `quarkus.rest.path` and `quarkus.http.non-application-root-path` when the
key is unset, and that derivation *was* exactly right until `/events/stream` existed — a `@WebSocket`
literal follows neither key. The key had been spelled out before it was needed, which is why adding
the socket meant adding a line beside it rather than finding out from a subscriber parsing HTML.
Three traps travel with it: setting it **replaces** the derivation rather than extending it (so
`/api` and `/q` are repeated by hand); the values are matched **after** `ui-root-path` is stripped,
so they are relative — `/events/api` written there matches nothing at all and is indistinguishable
from not setting the key; and websockets-next claims only the **upgrade handshake**, so a plain GET
on the socket path reaches no socket route and falls through to the SPA unless the prefix is
ignored. Ignoring it does not unregister the route — the upgrade still works, and
`PackagedSurfaceIT` asserts both halves on the built artifact.

## The bus

Two things make this an event *bus* rather than an event *log*:

    PUT /events/api/events/{id}    idempotent publish under the publisher's own UUID
    ws:  /events/stream            live push of every newly created event

The envelope is one shape in both directions:

```json
{ "name": "BuildSuccessful",
  "occurredAt": "2026-07-31T12:46:03Z",
  "payload": "{\"branch\":\"main\",\"repoId\":\"qits-ci\"}",
  "description": null,
  "parentId": null }
```

`payload` is the publishing event class's own fields as **canonical JSON in a string**. This service
stores and compares it verbatim and never reformats it: canonicalization is the publisher's job, and
the equality below is the only reason a retry is safe. `parentId` is envelope, never payload — the
payload is compared byte for byte, so a cause that entered it would make one event published under
two parents two events nothing could reconcile.

**`parentId` may be absent, and absent means null.** That is the whole of this contract's backward
compatibility: a publisher that never learned about the field keeps working, which is why this
service ships before any publisher that stamps. The reverse order is the unsafe one — a stamping
publisher against a service without the column has its parents silently dropped, and chains recorded
as roots cannot be backfilled.

The publish has three answers and no fourth — `201` for an id this log has not seen, `200` for the
same `name`/`occurredAt`/`payload`/`parentId` arriving again (nothing written, nothing pushed),
`400` for an id that exists with anything different, which is a reused UUID and not something a
retry fixes. `description` is deliberately outside that comparison and `parentId` is deliberately
inside it: the line is identity of the occurrence versus prose about it, and two PUTs of one id
claiming different causes are two different claims about history. Two more `400`s belong to the
cause — a `parentId` that is not a canonical UUID, and a `parentId` equal to the event's own id,
since an event cannot cause itself. A `parentId` this log has never seen is **not** one of them.
`POST /events/api/events` stays what it was, for recording something by hand, and takes an optional
`parentId` validated identically so the two write paths cannot diverge about what an event is.

A subscriber connects to `/events/stream` and sends one frame — `{"subscribe": ["BuildSuccessful"]}`,
which *replaces* that connection's set; `["*"]` means everything — and is then pushed
`{"id", "name", "occurredAt", "payload", "description", "parentId"}` for each newly created matching
event. `name` doubles as the **signature** a subscriber matches on. The field *order* is not part of
the contract — both sides bind by name — but appending is: a subscriber built against the first five
fields reads the frame it always read. Live only, at-most-once: no replay, no offset, no catch-up.
That is a deliberate omission rather than a gap — catch-up reads the event log itself and is a
separate feature — and the envelope carries the id precisely so it can be built without breaking
anyone.

## Walking a chain

Two affordances, and there is no third:

    GET /events/api/events/{id}              → the event, whose parentId is its cause  (upwards)
    GET /events/api/events?parentId=<id>     → that event's children, newest first     (downwards)

Downwards is the shape a release train actually has — one release fans out to N builds — and a
client cannot do it without listing the whole log, which is why it is served. An unknown parent is
an **empty list, not a 404**: this log cannot tell a wrong id from one that has not arrived yet, and
"nothing was caused by it as far as I know" is true in both cases. A blank value is treated as
absent, so `?parentId=` is the whole log.

It is a query parameter on the route that already exists, and that is not only economy: a new
literal under `/events` would need an entry in `quarkus.quinoa.ignored-path-prefixes` in the same
commit. This is the one addition that needs none, so this feature does not touch that key at all.

## Reading the log

    GET /events/api/events?limit=200&cursor=<occurredAt>,<id>&name=A,B&since=<instant>&q=<text>
    GET /events/api/events/names   → { "names": ["BuildSuccessful", "SCMRelease", …] }

The list answers a **page**, and its envelope grew one field:

```json
{ "events": [ … ], "nextCursor": "2026-08-01T08:52:23.928965Z,0bdbe98d-…" }
```

`nextCursor` is `null` on the last page, and that null is the only end-of-log signal a client needs.
A full page is not one, and there is no count — the route is asked for one row more than the page
holds, so it knows the answer without a second query. The field was appended to a shape that already
shipped, so a consumer that never reads it reads the list it always read.

| | |
|---|---|
| `limit` | page size. Absent is **200**, above **1000** is 1000, anything that is not a whole number ≥ 1 is a `400`. The clamp is silent-safe: the page says what it holds and whether more exist |
| `cursor` | `<occurredAt>,<id>` — the last row of the previous page |
| `name` | comma-separated, the **same vocabulary** a subscribe frame names, so a filter means one thing live and one thing historically |
| `since` | inclusive lower bound on `occurredAt`. There is deliberately no `until`: the cursor **is** the upper bound |
| `q` | case-insensitive substring of the payload |

**The cursor is composite, and that is the whole design of it.** `occurredAt` is not unique and
cannot be made unique — the events one pipeline run publishes carry the run's finish instant, so a
fork's siblings tie by construction — and a scalar `before=<occurredAt>` cursor whose boundary lands
on a tie either repeats a sibling or drops one, on precisely the rows a release train is read for.
The pair supports `occurred_at < :at or (occurred_at = :at and id < :id)`, which has no such gap. The
list's sort carries the id for the same reason: without it a tied pair comes back in whatever order
the database picked this time, and the log's order is not reproducible across two identical requests.

**`q` searches the payload as a string and parses nothing.** There is no one key that means "which
repository" — a build names it under `repoId`, a release under `repository` — so `q=qits-stt` finds
all of them, over-matches slightly, and is named for what it is. The server's stance that the payload
is opaque is what makes the idempotent publish's byte-for-byte comparison true, and no read is worth
giving it up.

**`?parentId=` takes none of these and is answered whole.** A parent's children are one per artifact
a pipeline declares — bounded by a file in a repository rather than by history — so its `nextCursor`
is always `null`.

`/names` is `select distinct name order by name`. It is a route because the alternative is fetching
all of history to learn five strings, which is the thing paging exists to stop; it is a literal
beside the `/{id}` template, and JAX-RS matches literal characters first, which the suite pins rather
than trusts. Both live under `/events/api`, so `quarkus.quinoa.ignored-path-prefixes` is untouched.

## The client

[qits-spa-events](https://github.com/QuicklyIterateTheSoftware/qits-spa-events) — Angular 21,
standalone components, no SSR — is a submodule at `service/src/main/webui`, which is Quinoa's
default `web-ui-dir`, so the path is a convention rather than a setting. Its `angular.json` sets
`baseHref` to `"/events/"`: the segment is spelled in **four** places that move together, and that
one is in another repository where no build here can check it. A `baseHref` that disagrees yields a
page that loads and then fetches its own JavaScript from the wrong place.

That gives this repo a clone rule with two halves:

    git clone … && git submodule update --init

- **The test suite needs neither node nor the submodule.** Quinoa is disabled by default in test
  mode (`Quinoa is disabled by default in tests.`), so all 86 `@QuarkusTest`s are green against an
  empty `webui/` on a machine with no node at all — `./mvnw test`, measured.
- **Anything that reaches `package` needs both**, and that includes `./mvnw verify`, which runs
  `package` on its way to failsafe. An uninitialised gitlink is an *empty directory*, and that is
  the one case Quinoa treats as a misconfiguration rather than "no client": augmentation stops at
  `No package.json found in Web UI directory`.

  The platform reference (`docs/project-setup-quinoa-angular.md`) says `mvn verify` needs neither;
  that is true of the *tests* it runs and not of the goal, and it holds for every SPA-serving
  service, not just this one. `./mvnw test` is the command the clone-alone rule actually names here.

The client depends on `@qits/ui-components`, which exists only on the platform's own npm registry —
reachable from a developer's host (its committed `.npmrc` names `localhost:8081`) and from
`qits-net`, and from **no address inside a docker build**. So the image build does not build the
client: `.config/qits/ci-post-receive.yml` builds it in a step container on `qits-net` and
`docker/Dockerfile` packages the bundle it was handed. Every SPA-serving service in the platform
does this, for the same reason.

## Configuration

The `events` jar ships its defaults at ordinal 100
(`events/src/main/resources/META-INF/microprofile-config.properties`); `service`'s
`application.properties` is the app's own at 250; `.env` is 295 and real environment is 300. One
variable is the whole of what a deployment must say about storage:

    QUARKUS_DATASOURCE_EVENTS_JDBC_URL=jdbc:h2:file:/data/events/h2/events

The image names no default for it. An unconfigured `docker run` fails at Flyway's first connect, and
that is the honest behaviour — a home dir baked into the image would make the bare run "work" by
writing a database that dies with the container.

## Authentication

There is none here, and that is the design. Authentication terminates at qits-gateway; this service
reads the `X-Qits-User` header the gateway injects (`events/security/ForwardAuthMechanism`) and
authenticates nothing. A missing header is *anonymous*, and anonymous is not a denial — reaching
this service at all already implies you are inside the trusted network. See `AGENTS.md`.

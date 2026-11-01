# Exam revision scheduling — algorithm v1

`RevisionPlanner` constructs concrete sessions from conservative free intervals
exported by `WorkloadEngine`. This extends the independent cutoff analysis in
[WORKLOAD-ALGORITHM.md](WORKLOAD-ALGORITHM.md); its scalar capacity formula is not
used as a session scheduler. The implementation is deterministic for identical
inputs and `now`, fully client-side, with no model-generated weights.

## Inputs and ownership

One selected non-Moodle account supplies original exam/timetable snapshots, its
confirmed `WorkloadPolicy`, and an owner-scoped `RevisionWorkspace`. The workspace
contains active/archived exam plans, ordered `StudyTopic` values and sessions with
`PLANNED`, `COMPLETED` or `MISSED` status. A topic stores a stable local ID, title
and total effort, independently of official submission/completion state. The topic
value can support later assignment-subtask work without adding a second exam-only
effort primitive.

Each exam uses `WorkloadRef`; source identity is never guessed from a title or
student number. All active plans in one account compete for one set of intervals
and weekday budgets. There is no cross-account schedule consolidation.

`WorkloadDay.available` exposes whole-minute conservative intervals after known
classes, partial cancellations, make-up classes, optional calendar events,
explicit exam-duration assumptions, unknown-end scenarios, saved personal blocks,
travel buffers and study windows. It exports no intervals when required busy data
cannot be read. Its existing `capacity` field and workload figures remain unchanged.

Additional revision settings are maximum session length `q` (10–240 minutes), rest
`r` (0–120 minutes), and start delay `a` (0–120 minutes). Require `q >= m`, where
`m` is the workload minimum. Workload windows/budgets, lead `L`, horizon and source
freshness are independently configurable. All clock operations use GMT+07.

Generation blocks on unconfirmed budgets, stale/unavailable sources, unreadable
local data, conflicting work, failed refreshes and other workload quality issues.
`UNKNOWN_END` alone is allowed: the supplied conservative intervals already
reserve that uncertainty. Missing/changed active exam references also block it.
The account-level saved timestamp does not prove per-section freshness.

## Preserve progress before allocating

Explicit generation copies the workspace into a proposal:

1. Preserve every completed record, including archived-plan history.
2. Preserve active locked sessions and active sessions currently in progress.
3. Preserve missed records; turn expired unlocked planned records into missed ones.
4. Remove other active unlocked future reservations from the proposal only.
5. Leave archived plans unchanged. Their future reservations do not consume the
   active schedule; completed history still consumes its reported day's budget.

Locked expired sessions remain fixed conflicts, requiring an explicit unlock or
skip. Existing fixed sessions must pass the same availability, rest, deadline and
budget checks. If they do not, return the original workspace with blockers; do not
silently move a lock or discard completed preparation.

For topic `i`, let `E_i` be total entered effort and `F_i` the allocated effort of
its preserved completed/in-progress/locked sessions:

```math
R_i = E_i - F_i
```

Missed records contribute zero credit. Validation requires `R_i >= 0`. All saved
non-missed allocations together must not exceed a topic's total; an edit below
preserved credit fails while its draft stays open.

## Constructive allocation

Order active plans by `(examDue, examRefDigest)`, then traverse each plan's explicit
topic order. All use the same finish lead, so exam and cutoff order agree. No
arbitrary difficulty, credit or risk weights are added.

For every topic with remaining effort, visit dates and conservative gaps in time
order. The planning cutoff is `due - 60L` in seconds. New starts are no earlier than
the later of the gap start and `ceil(now / 60)*60 + 60a`. Within a gap:

1. Subtract fixed and already proposed sessions expanded by `r` on both sides.
   This enforces at least `r` clock minutes between actual sessions, including
   today's completed records. Break time is not charged as study time.
2. Let `b` be the day's budget minus all completed/fixed/new reserved minutes.
3. Clip the candidate free segment at the cutoff, obtaining length `g` in minutes.
4. Compute the largest allowed reservation:

```math
u = min(g, b, q)
```

If `u < m`, skip that segment. Otherwise choose:

```math
v = min(u, max(m, R_i))
c = min(R_i, v)
```

`v` is reserved clock/budget time; `c` is allocated topic effort. If work remains
after this reservation, `0 < b-v < m`, and `b >= 2m`, shorten `v` to `b-m` before
computing `c`. This prevents stranding a subminimum daily budget remainder when
two minimum sessions can fit. The shortened value remains between `m` and `u`.

Append `[start, start+60v)` with allocated effort `c`; subtract `v` from the day's
budget, subtract `c` from `R_i`, reserve the rest interval, and continue. Session
IDs are deterministic name-based UUIDs from exam digest, topic ID and start time.
Visit all eligible gaps until the topic is covered or the finite horizon ends.

Remaining `R_i` becomes an explicit unplaced row. A valid partial draft may be
saved; this does not mark its topics completed. The 2,048-record workspace limit
is an explicit blocker, not permission to truncate history or publish a complete
plan. No automatic planning occurs while the screen is closed.

## Independent postcondition validation

After constructing the proposal, validate the complete active schedule, including
fixed sessions, rather than comparing independent prefix capacity numbers:

- Every planned reservation has whole-minute bounds, one UIT date and duration
  between the configured minimum and maximum.
- Its future portion lies wholly inside one conservative free interval and ends
  no later than its own exam cutoff.
- All planned reservations obey pairwise rest/non-overlap constraints against
  other planned and completed records. Historical completed/completed overlaps
  are not reinterpreted as future availability.
- Sum of reserved planned minutes plus reported completed minutes on each date
  does not exceed that weekday's shared budget. Completed history outside the
  current horizon remains intact; today's completed work reduces today's budget.
- Every active exam still resolves to the exact current source reference/date.
- Every topic's non-missed allocated effort stays at or below its entered total;
  reservations may include padding but credited effort is never fabricated.

The same scheduling validator checks manual moves and draft saving. Moves are
future-only, preserve topic identity and lock their new interval; shortening a
reservation can reduce its allocated effort. A newly proposed session cannot be
saved after its start has passed. Source or concurrent workspace changes invalidate
the proposal. A compare-and-replace storage write rejects stale edits atomically.

## Examples and limits

With budget 50, minimum 30, a 30-minute early gap and a 50-minute later gap,
two exams needing 30 early and 20 later cannot both receive sessions. The planner
reserves the early 30 and reports the later 20 as unplaced. It never treats the
independently available later 50 as if the daily budget had not already been used.

With a topic remainder of 20 and minimum 30, a session reserves 30 and allocates
20. Both values remain visible. With 60 budget minutes remaining and a 50-minute
early segment, a longer topic reserves 30 first, leaving 30 usable budget minutes
for a later session instead of stranding 10 after a 50-minute reservation.

This is an earliest-gap heuristic, not exhaustive search, an optimal solver, a
fairness optimizer or proof that all unplaced effort is impossible. Minimum-size
padding, fixed choices, topic order and limited horizon can leave work unplaced
even if another arrangement exists. The app labels this limitation. Manual edits
and changed configuration are deliberate alternatives; no solution-quality score
or grade guarantee is invented.

The planner covers entered exam topics, not unestimated assignment work or hidden
real-world commitments. Separate workload estimates are not automatically combined
with topic totals, preventing silent double counting. It does not infer topics,
generate study content, notify, track actual study time or write remote calendars.

## Bounds, cost and tests

Each owner has up to 20 exam plans, 20 topics per plan, 2,048 session records and
2,000,000 JSON characters. Topic totals are 1–60,000 minutes. Persistence validates
unique exam/topic/session IDs, ownership, credit, minute precision, same-day
reservations and schema version; malformed data is retained with an error.

With `P` topics, `G` daily gaps and `S` kept/generated sessions, repeated interval
queries cost approximately `O((P*G + S)*S)` and pairwise final validation `O(S²)`,
plus sorting exam plans and source projection. These are bounded client inputs;
calculation runs on `Dispatchers.Default`. Cancellation/session guards reject
obsolete drafts; allocation checks cancellation between topics and interval scans.
The default search guard allows 5,000,000 interval-scan units (each query charges
the current occupied-record count plus one). Its constructor limit can be reduced
for tests or adjusted within 1–50,000,000 by application code. Reaching it returns
an explicit computation-limit blocker and prevents saving the proposal; it never
proves infeasibility. Pairwise validation is separately bounded by the 2,048-record
storage limit. These are engineering guards, not study-policy weights.
This implementation favors an auditable constructive algorithm
over an unsupported optimality claim.

Tests include the independent-prefix counterexample, shared budgets/deadlines,
padding, breaks/lead/start delay, a budget-tail adjustment, preserved progress and
locks, missed sessions, changed sources, archives and strict identities. A seeded
150-case oracle independently checks generated interval membership, pairwise rest,
budget totals, cutoff times and `allocated + unplaced = entered` effort. Separate
repository/ViewModel/Compose tests cover concurrency, corruption, account removal,
explicit preview/save, failed draft retention, manual movement and Activity
recreation. Native Android instrumentation verifies real Keystore restoration.

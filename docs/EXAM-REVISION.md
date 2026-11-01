# Exam revision planner

Open **Schedule → Exam revision planner**. Select a DAA/Portal teaching account,
choose a cached upcoming exam, and add revision topics with **total study effort**
in minutes. Total includes effort already reported as completed. Titles and topic
order are editable; nothing is inferred from credits, grades or exam type.

Review **Configure budget** for that teaching account. Windows, weekday budgets,
minimum sessions, buffers, finish lead, freshness and the 7/14/28-day horizon reuse
the workload settings. A Moodle account's budget is not silently copied to a
different account. All active exam plans in the selected account share one budget.
Different accounts remain independent planning workspaces.

**Session rules** adds editable maximum session length (default 90 minutes), rest
between sessions (10 minutes) and a delay before newly generated sessions (10
minutes). These defaults are templates. Maximum must be at least the current
minimum. Breaks consume clock time but not study-budget minutes.

## Draft, inspect, save

**Generate / replan all exams** creates an unsaved proposal for every active exam
plan in this account, using earliest exam deadlines and then the saved topic order.
Review the date-grouped sessions, effort versus reserved time, and unplaced topics.
**Save draft on device** persists it explicitly; **Discard draft** makes no write.

The algorithm constructs sessions together and validates their shared constraints.
It is a heuristic, not an optimal scheduler: unplaced work does not prove that no
alternative arrangement exists. A partial valid draft can be saved, retaining its
unplaced topic effort for the next attempt. No academic completion is inferred.
Saved plans continue to show effort without a current reservation, including work
whose session has expired without a completion report. Recorded future reservations
are not a guarantee against later source changes; inspect conflicts and replan.

A final topic remainder shorter than the minimum reserves a full minimum-length
session, but credits only its actual entered effort. For example, 20 minutes of
remaining work with a 30-minute minimum reserves 30 minutes and credits 20.

Unknown exam ends use the workload engine's conservative blocked intervals. A
personal exam-duration assumption can be entered through **Exam duration / effort**
for the selected upcoming exam. Its remaining-effort estimate is a separate
workload value; revision topic totals are **not automatically added to it**.

## Edit, complete and replan

**Session details** allows an explicit move to a future same-day interval in UIT
time. Saving a move locks it, checks conservative availability, cutoffs, daily
budgets and rest against other sessions, and reduces allocated effort if the new
reservation is shorter. Unlock deliberately to let a subsequent replan replace it.

**Mark completed** is available only after the recorded session end. It is a
personal report for the allocated effort, not a study timer or university status.
**Skip session** retains a missed record without credit. Explicit generation turns
expired unlocked sessions into missed history, preserves completed, locked and
in-progress sessions, and replaces other unlocked future sessions.

Editing a topic removes its unlocked future sessions, preserving locked/completed
and missed records. Total effort cannot be reduced below preserved allocated or
completed effort. Topic order breaks ties within an exam; exam cutoff order still
wins across exams. Replan explicitly after edits or policy changes.

## Changed exams and retained history

The exam reference is the workload source's account-scoped natural identity,
including class code, date, shift and resolved start time. Changed dates/times can
produce a new reference. Missing, conflicting or moved exams block active-plan
generation until the student deliberately uses **Change exam link** or **Archive
plan**. Relinking retains topics and every session; conflicting future fixed
sessions must then be moved, unlocked or skipped. Nothing transfers by similar title.

Archive retains history and excludes its future sessions from planning. Reported
completed sessions still consume today's shared budget. Resuming is explicit.
**Delete local plan** requires a second confirmation and permanently removes the
plan, topics and history on the device. Account removal clears its entire bucket.

## Data and verification

Planning is client-only and offline. Reload retries local reads; university refresh
remains the workspace's existing read workflow. The feature creates no university
edit, submission, remote calendar event, notification or AI/backend request.

Separate Android Keystore AES-GCM storage uses preference namespace `revision`,
alias `uit.revision.v1` and hashed per-account buckets. Versioned strict JSON,
compare-and-replace writes and late-write cleanup protect progress and account
isolation. Malformed buckets remain intact with an error rather than being reset.

See the [algorithm and validation contract](EXAM-REVISION-ALGORITHM.md),
[phone captures](design/README.md) and [stacked PR landing guide](STACKED-PRS.md).
Synthetic host checks cover domain invariants, storage, ViewModel drafts and actual
Compose interactions. A separate native Android test exercises real Keystore
history restoration and account-scoped removal. Host renders do not establish
physical-device keyboard or TalkBack behavior.

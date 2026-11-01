# Workload preview

Open **Schedule → Workload preview**. Choose the planning account, then use
**Configure budget** to choose a 7/14/28-day horizon and review each weekday's study
window and budget. For Moodle work, explicitly select one DAA/Portal teaching
calendar. Review minimum session length, travel/break buffer, finish lead time,
freshness threshold and whether other calendar events should block time.

Save settings to confirm them. The initial 18:00–22:00 / 120-minute weekday values
are editable templates, not inferred availability. Select weekdays individually;
a zero-minute budget is a day off. Windows are same-day GMT+07 times. Today uses
only the remaining window; the app does not infer time already spent studying.

Enter **remaining study minutes** for a deadline or exam. Leaving the field blank
keeps it unknown. Zero is a personal estimate, never a submission status. Exams
without official end times can also have an optional personal duration assumption
for capacity planning. Clear both fields to remove the saved estimate.

The overview identifies the worst upcoming cutoff, known demand and missing
estimates. **How this is calculated** shows cumulative demand, recorded capacity,
the planning cutoff and contributing items. Due dates group tasks by UIT date.
Overdue items stay separate; saved estimates whose current source is unavailable
are retained with a deliberate Remove action. Removing or missing a source does
not mean its work was submitted.

Personal blocks reuse the existing Find free time store for the connected-account
workspace. Open **Manage personal blocks**, save desired blocks on the device,
then reopen Workload preview. Unsaved free-time drafts are excluded. Workload reads
this store strictly: a failed or partially malformed read is an uncertainty, not an
empty personal calendar. Existing Find free time recovery behavior is unchanged.

Policy belongs to the selected planning account. Each estimate belongs to the
source account owning the work. Switching a teaching source never transfers exam
estimates by title. Removing an account clears its workload bucket. Settings and
estimates remain local and encrypted; there is no AI/backend dependency, university
submission, academic edit, upload, notification or background refresh in this feature.
Reload local data retries local reads only. Global workspace refresh remains the
existing way to fetch university updates.

The account snapshot timestamp does not establish that every section refreshed.
Inspect data limitations before relying on the projection. Even with sufficient
per-cutoff capacity, fragmentation can prevent a feasible sequence of sessions;
this feature does not automatically schedule study work.

See the [algorithm, formulas, examples and invariants](WORKLOAD-ALGORITHM.md) and
the [phone gallery](design/README.md). Implements
[issue #10](https://github.com/trungdangtapcode/UIT-Mobile-kt/issues/10).

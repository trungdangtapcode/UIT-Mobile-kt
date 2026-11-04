# Stacked pull requests

The exam revision planner is developed on `feat/exam-revision-planner`, forked from
`feat/configurable-workload` at `78bad1afbddee8728689dd88b4f47b6646ba8d32`.
Its PR targets **`feat/configurable-workload`**, not `main`, while parent PR
[#71](https://github.com/trungdangtapcode/UIT-Mobile-kt/pull/71) is open. This keeps
the child review focused on revision scheduling instead of repeating the workload
implementation. Both branches retain normal CI; nothing is pushed directly to main.

## Landing order

1. Review and merge parent #71 into `main` first.
2. Retarget the child to `main` and verify its comparison. GitHub may retarget it
   automatically when the parent branch is removed; confirm rather than assume.
3. If the parent was squash/rebase merged, rebase only the child commits onto the
   new main so the parent's old commits do not reappear in its review.
4. Push the rebased branch with `--force-with-lease`, rerun required checks and
   review the final child diff before merging it.

For this initial fork point, after the parent has actually merged:

```sh
git fetch origin
git switch feat/exam-revision-planner
git rebase --onto origin/main 78bad1afbddee8728689dd88b4f47b6646ba8d32
git push --force-with-lease origin feat/exam-revision-planner
gh pr edit --base main
```

These are landing instructions, not commands to run while #71 remains open. If
the child is first rebased onto a newer parent tip, record that new fork point
and use it instead. Resolve conflicts and inspect `git diff origin/main...HEAD`.
Do not merge the child into the parent feature branch: that would change #71's
scope before its own review. Mark the child ready only after the base and required
checks match its intended landing target.

# Actions execution and budget policy

Run build, lint and relevant tests locally first. Push a reviewed batch when it
is ready for remote validation. Actions is a merge gate, not an iterative remote
development environment. Changes to CI also belong on a branch and a PR.

## Execution scope

| Event | Result |
| --- | --- |
| Push to `main`, feature branch or tag | No automatic workflow run |
| Open, reopen or update a ready PR targeting `main` | Both required Android jobs run |
| Open or update a draft PR targeting `main` | Workflow metadata may appear; both jobs skip without allocating runners |
| Mark a PR targeting `main` ready for review | Both required Android jobs run |
| Convert a PR targeting `main` back to draft | The replacement workflow cancels its older run; both jobs skip |
| PR targeting another feature/stack branch | No automatic workflow run |
| Manual Run workflow / `gh workflow run` | Both jobs run on the selected ref |
| Edit a title/body, comment, review, close or merge a PR | No automatic workflow run |

The target is the PR **base** branch, not its head. Retargeting alone is an
`edited` event and intentionally does not trigger another full build. After
retargeting a stack PR to `main`, mark it ready, push a finalized update, or
explicitly dispatch once on its current head branch. Do not use unrelated PR
description edits to request tests.

Concurrency is scoped to this workflow and PR number, or selected ref for manual
runs. A newer run cancels the older run in that same group. Different PRs remain
independent. Cancellation limits additional work; it does not refund execution
already consumed. Manual and PR runs use different groups, so avoid dispatching
manually while the same head is already being tested by a PR run.

There is no scheduled run, automatic retry or push-to-main duplicate. Ready PR
updates still trigger CI; keep intermediate work local or in a draft PR. Job
timeouts are 20 minutes for `verify` and 15 minutes for `sign-in-smoke`. These are
per-job execution bounds, not an account-wide spending cap.

## Local checks and manual execution

For application changes, use the normal local toolchain:

```sh
./gradlew check :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Use focused tests as appropriate before the final local checks. Workflow-only
changes can be checked with `actionlint` and `git diff --check`; they do not
require another full Android build just to validate YAML. A local workflow lint
pass is not evidence that GitHub executed a runner or emulator.

In GitHub, open Actions → Android → Run workflow and select the actual PR head
branch. The same deliberate run is available through the CLI:

```sh
gh workflow run android.yml --ref YOUR_PR_HEAD_BRANCH
```

The workflow must exist on the default branch before GitHub offers manual
dispatch. Manual runs are not free retries; wait for the result and inspect a
failure before deciding whether a rerun is justified.

To request the debug APK artifact explicitly:

```sh
gh workflow run android.yml --ref YOUR_PR_HEAD_BRANCH -f upload_apk=true
```

Regular PR runs do not upload an APK. Smoke-test results upload only on job
failure. Both artifact types expire after three days. Local APKs and deliberate
preview releases remain separate from this CI artifact policy.

## Merge protection and honest evidence

The policy retains `verify` and `sign-in-smoke` as required checks on `main`,
strict base freshness, the PR requirement, zero required outside approvals and
admin enforcement. No repository protection setting is changed by this workflow.

A skipped draft job is not a passed Android test. Before merging a ready PR,
verify that both jobs actually completed successfully on the current head after
the latest base update. An earlier head's run or a draft's skipped jobs is not
acceptance evidence. An update required by strict branch protection needs a new
deliberate/automatic run; do not manufacture statuses or bypass the gate.

Avoid workflow-level `paths-ignore` or `[skip ci]` on a PR that is ready to merge:
skipping the entire required workflow can leave its checks pending and block
integration. Do not remove required checks merely to merge a docs/CI change.

GitHub references:

- [Branch/event filters and concurrency](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [Skipping runs and pending required checks](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)
- [Manual execution](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-running-a-workflow)

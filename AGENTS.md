# UIT Mobile

This project is a native Android phone application. Design and review the portrait
phone experience at 360dp, 390dp and 412dp widths. Keep one content column and bottom
navigation. Do not add desktop dashboards, sidebars, navigation rails, two-pane
product flows or desktop preview targets unless the user explicitly requests them.

Use the current phone direction in `.interface-design/system.md` and the gallery
in `docs/design/README.md`. Older design notes and archived images are historical,
not requirements for the current app.

Preserve the requested cut-corner frames: three equal small cuts and a larger
physical bottom-right cut, using `TechnicalShapes`. Main panels use 8dp/28dp cuts.
Keep the authentic transparent UIT emblem and UIT blue. Use the existing native
Material 3 controls and repository-backed content.

Write source identifiers, comments, documentation, tooling output and commit
messages in English. Vietnamese is allowed where functionally required: localized
UI resources, literal text/labels matched from UIT portals, synthetic fixtures
covering that portal content, and proper names. Do not translate portal literals
or parser patterns in a way that changes their behavior. Keep UI translations in
locale-specific resources rather than mixing languages in general documentation.
Communicate with this user in Vietnamese.

For UI changes, render the actual Compose phone screens, inspect the images and
run the relevant checks. A passing test suite does not establish visual approval.
See `docs/DEBUGGING.md` for rootless SSH development and preview limitations.

## UIT Forum: web parity is the target

The user requires the full UIT Forum student experience in the native phone app.
The anonymous reader is an initial increment, not the completed Forum feature.
Do not silently reduce this target to feeds, text, a few screenshots or browser
links. Follow [the parity research and acceptance matrix](docs/research/UIT-FORUM-PARITY.md).

- Research the actual UIT mobile website, current response contracts and
  applicable upstream Discourse behavior before implementing a Forum capability.
  Record the observed server version, date, account mode and source. Distinguish
  UIT-observed behavior, upstream documentation and unverified assumptions.
- Inventory the entire flow, including content fidelity, navigation, permissions,
  loading/errors, edits/deletions, reconnects, accessibility and account changes.
  Maintain the parity matrix as implementation evolves. Do not call a row complete
  until its acceptance evidence exists; unknown or deferred work stays visible.
- Discover capabilities from UIT settings and per-resource permissions. Do not
  assume that a bundled plugin is enabled, an enabled feature is permitted for
  every account/category, or UIT login uses standard DiscourseConnect. Never
  hardcode observed topic counts, tag lists, reactions or account capabilities.
- Preserve source meaning: inline formatting, nested content, quotes/reply
  targets, spoilers, attachments, image originals, revisions and plugin blocks.
  Silently flattening/dropping content is a fidelity bug. An explicit browser
  fallback is temporary coverage, not native parity or permission to reduce scope.
- Published Forum screenshots must render actual UIT responses through the app's
  production path. No authored posts, fabricated accounts, synthetic screenshot
  galleries or substituted success data. Fixtures remain valid for automated
  edge-case tests, but those tests must not export Forum review screenshots.
- Attach source routes, capture time and runtime to real-data evidence. Compare
  the same content/state against the mobile website. Distinguish host rendering,
  emulator/device execution, live networking and authenticated verification.
  Inspect captures before publishing; credentials, cookies, private content and
  personal support details stay out of the public repository and PR.
- Research and testing on the owner's real university account remain read-only,
  apart from already-authorized necessary login. Do not post, edit, delete, like,
  react, vote, flag, send messages, change settings, grant API keys or mark items
  read for verification. Inspect automatic tracking/draft/notification writes;
  GET is not a guarantee of no side effects. Use a dedicated test environment
  or separately authorized test account for mutation acceptance tests.
- Implement account-bound storage, credentials, permission checks and mutation
  contracts separately from the anonymous guest reader. Never forward university
  cookies to arbitrary media hosts or weaken TLS to achieve apparent parity.
- Every Forum PR must identify covered matrix rows, remaining gaps and verification
  limits. Keep work on feature/stacked branches. Do not claim "100%", close the
  full-parity scope or merge solely because a happy path or fixture test passes.

Use the [commit message contract](docs/COMMIT-CONTRACT.md) for all commits. Enable
the local hooks with `sh scripts/install-git-hooks.sh` after cloning. Never stage
local SDK settings, credentials, signing material or generated build outputs.

## CI budget discipline

The owner has a limited Actions compute allowance. Run the relevant build, lint
and tests locally before pushing. Group reviewed changes into a push rather than
using Actions to discover routine formatting or test failures. Do not dispatch
or rerun CI unless a concrete validation need justifies the cost.

Android CI is scoped to ready PRs targeting `main` and explicit manual runs.
Keep development PRs in draft and stacked PRs based on their dependency branch.
When preparing a stack for integration, validate locally first, then retarget
and mark ready only the PR being merged next. Main pushes do not need another
automatic verification run. Do not re-enable broad push/PR triggers or weaken
branch protection to save time. See [the CI policy](docs/CI.md) for triggers,
manual execution, cancellation, artifacts and required-check limitations.

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

Use the [commit message contract](docs/COMMIT-CONTRACT.md) for all commits. Enable
the local hooks with `sh scripts/install-git-hooks.sh` after cloning. Never stage
local SDK settings, credentials, signing material or generated build outputs.

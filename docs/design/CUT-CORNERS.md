# Asymmetric cut-corner frames

The requested direction keeps the content frames and replaces rounded corners
with straight octagonal outlines. Three corners have small equal cuts; the
physical bottom-right has a visibly larger cut, giving the panels a technical,
file-card profile.

[Phone](previews/dashboard-phone.png) ·
[Dark phone](previews/dashboard-dark.png) ·
[Course list](previews/courses-phone.png) ·
[Larger text](previews/dashboard-compact-large-text.png)

![Current phone frames](previews/dashboard-phone.png)

## Geometry

[TechnicalShapes.kt](../../core/designsystem/src/main/kotlin/vn/edu/uit/mobile/designsystem/TechnicalShapes.kt)
is the single source for the shape scale. It uses Compose's
`AbsoluteCutCornerShape`, so layout direction does not move the larger cut to the
other side. The outlines and clipping follow the same shape.

| Role | Top-left / top-right / bottom-left | Bottom-right |
| --- | --- | --- |
| Tile | 3dp | 8dp |
| Control | 4dp | 12dp |
| Field | 6dp | 16dp |
| Panel | 8dp | 28dp |
| Dialog | 8dp | 32dp |

Main panels use a 1dp outline and 16dp content padding. Timetable and submission
rows remain grouped within their frame. Course directory entries use individual
file-like frames. Buttons, filter tabs, fields, navigation selections and dialogs
use the corresponding smaller or larger shape. UIT blue, the transparent original
emblem, campus photograph, layout order and existing interactions are retained.

## Comparison and validation

The [previous phone](previews/before-cut-corners/dashboard-phone.png) is preserved
for comparison. See [the phone scope](MOBILE.md) for the current product direction. Preview content is synthetic and rendered from actual Compose
components without credentials or an emulator.

Existing UI coverage checks navigation, search, calendar selection, class details,
submission filters, materials, academic interactions and message drafts. Visual
review uses 360dp, 390dp and 412dp phones, dark mode and 1.3× font-scale renders.

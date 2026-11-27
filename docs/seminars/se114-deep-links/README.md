# SE114 Deep Links and App Links

A 22-slide Vietnamese Beamer seminar. The opening calendar question leads through
Web addresses, Android Intent resolution, and verified HTTPS links. It ends by
tracing a UIT Mobile widget interaction through production Kotlin, the
Activity lifecycle, and the schedule screen. The 20-minute script includes
the code walkthrough, phone demonstration, and closing question.

## Build the PDF

Run `make pdf` in this directory. Tectonic writes `build/main.pdf`.
The same build exports the identical `build/SE114-Deep-Links.pdf` for sharing.
For a local TeX Live installation, use XeLaTeX:

```sh
mkdir -p build
latexmk -xelatex -outdir=build -halt-on-error main.tex
```

On Overleaf, upload the contents of this directory, choose XeLaTeX, and select
`main.tex`.
The included fonts and images are used directly from `assets/`. The project
also needs Beamer, PGF/TikZ, booktabs, tabularx, fancyvrb, and fontspec.

## Source map

| File | Purpose |
| --- | --- |
| `frames-01-07.tex` | Opening question, Web addresses, Intent resolution, and early App Indexing |
| `frames-08-13.tex` | Android verification history, signing identity, browser fallback, and Dynamic App Links |
| `frames-14-22.tex` | HTTPS setup, public YouTube statement, UIT Mobile widget implementation, and phone demonstration |
| `snippets/` | Illustrative HTTPS filter, production Kotlin excerpts, and the ADB command |
| `seminar-meta.tex` | Course, lecturer, and optional presenter names |
| `assets/` | Brand, fonts, photos, Android Settings, YouTube statement, and widget and calendar render provenance |

Slides 20–21 use Robolectric renders of the app UI with empty schedule data.
The ADB listing targets the separate preview package
`vn.edu.uit.mobile.preview`. See [Home screen widgets](../../HOME-WIDGETS.md)
for how the app handles calendar routes.

## Design references

The original theme draws on [Metropolis](https://ctan.org/pkg/beamertheme-metropolis)
for headings and the progress bar, and [Trigon](https://ctan.org/pkg/beamertheme-trigon)
for angular panels. It uses a UIT emblem, the UIT blue palette, and locally
bundled Be Vietnam Pro. The figures and diagrams record their sources in
`assets/`.

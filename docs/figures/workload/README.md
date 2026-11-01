# Workload algorithm figures

These illustrations use synthetic sources only. No student records, credentials,
authenticated images or service responses are included. Text is English; clocks
use UIT time (GMT+07). PNG is provided for GitHub and SVG for scalable export.

| Figure | Purpose | Scalable copy |
| --- | --- | --- |
| [Equation reference](equations.png) | Personal cutoffs, entered demand, unknown estimates and session-constrained capacity | [SVG](equations.svg) |
| [Cutoff pressure](cutoff-pressure.png) | Earlier shortfalls survive later equality; unknown exam ends produce two capacity scenarios | [SVG](cutoff-pressure.svg) |
| [Busy union and fragments](busy-union-and-fragments.png) | Union overlapping busy records and respect the minimum length of each used gap | [SVG](busy-union-and-fragments.svg) |
| [Independent-check counterexample](prefix-counterexample.png) | Passing every independent cutoff check does not construct a jointly feasible session schedule | [SVG](prefix-counterexample.svg) |

The full definitions, assumptions, classification order and limitations are in
[WORKLOAD-ALGORITHM.md](../../WORKLOAD-ALGORITHM.md). Actual Compose phone captures
use separate synthetic UI fixtures and live in the [phone gallery](../../design/README.md).

## Provenance

[`WorkloadFigureData`](../../../core/domain/src/test/kotlin/vn/edu/uit/mobile/domain/WorkloadFigureData.kt)
runs the production `WorkloadEngine`; it does not implement a second capacity
formula in Python. Three executable figure-contract tests check the numerical
examples against that engine. The export task writes:

- [`engine-curves.tsv`](engine-curves.tsv): 601 one-minute cutoff samples for each
  of two scenarios, including demand, conservative/optimistic capacity, missing
  estimate count and classification. A zero-effort probe creates each queried
  checkpoint without adding demand or busy time.
- [`engine-examples.tsv`](engine-examples.tsv): synthetic input coordinates and
  engine-derived outputs for interval union, fragmented budgets and the
  independent-check counterexample. Coordinates are minutes since 08:00.

The curve fixture starts Monday, October 26, 2026, at 08:00. It uses a seven-day
horizon, an 08:00–18:00 daily window, a 240-minute daily budget, a 30-minute minimum
session, no finish lead, and no travel buffer. A lab occupies 08:00–10:00. Known
remaining efforts are 180 minutes due at 12:00 and 60 due at 15:00. Both tasks are
available from 08:00; demand jumps show cutoffs, not study start times.

The second curve adds an 11:00 exam with an unknown end. Its preparation estimate
is explicitly zero; this does not assert zero attendance duration. At 15:00,
conservative capacity is 60 minutes and optimistic capacity is 240 minutes.
The noon shortfall is still 60 minutes even in the optimistic scenario.

The union example instead uses an 08:00–12:00 window, a 120-minute budget and a
15-minute buffer. Busy intervals 09:00–10:30 and 10:00–11:00 become one buffered
08:45–11:15 interval; two 45-minute free gaps yield 90 usable minutes.
Buffering before union or buffering their connected union gives the same result
here. The engine expands each interval before union.

The fragment example has two 30-minute gaps. With a 30-minute minimum, a
50-minute budget permits one gap (30 usable minutes), while a 60-minute budget
permits both. Shading illustrates one maximizing choice, not an assigned plan.

The counterexample has a 30-minute early gap, a 50-minute later gap, a 50-minute
budget and a 30-minute minimum. Independent checks pass at 08:30 (`D=C=30`) and
10:50 (`D=C=50`). After spending 30 minutes before the first cutoff, only 20 budget
minutes remain, insufficient for another minimum-sized session. This intentionally
illustrates a limitation of checkpoint bounds, not a solver bug or a feasible plan.

## Reproduce

Use the project's JDK 21 setup and Python 3.12 or later. Install plotting packages
in an isolated environment; no Android device, credentials, network service,
TeX installation or AI image generator is needed to render these figures.
The first Gradle/package installation may download build dependencies.

```sh
python3 -m venv /tmp/uit-workload-figures
/tmp/uit-workload-figures/bin/pip install -r scripts/requirements-workload-figures.txt
/tmp/uit-workload-figures/bin/python scripts/render-workload-figures.py
```

The default command first runs `:core:domain:exportWorkloadFigureData`, then
renders all four PNG/SVG pairs with Matplotlib. To render the committed TSV files
without rerunning Kotlin:

```sh
/tmp/uit-workload-figures/bin/python scripts/render-workload-figures.py --skip-export
```

To validate just the executable examples:

```sh
./gradlew :core:domain:test --tests '*WorkloadFigureDataTest'
```

The renderer uses the repository's bundled IBM Plex Sans plus Matplotlib's STIX
math fonts. SVG text is converted to paths, so exported figures do not fetch fonts
or images. A fixed SVG hash salt and omitted timestamps make repeated output
stable within the pinned plotting environment. Figures are documentation assets;
they do not add a plotting dependency to the Android application or ordinary CI.

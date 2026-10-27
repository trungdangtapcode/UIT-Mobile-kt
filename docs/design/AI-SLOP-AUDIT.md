# Visual audit: why the current UI still feels generated

Historical review. The current product scope is the [Android phone experience](MOBILE.md).

Reviewed on October 27, 2026 against the actual Compose screenshots and source.
This is the historical critique of the console revision. A subsequent
[agenda revision](AGENDA-REVISION.md) changes the structure; the observations below
refer to the preserved baseline, not the new gallery images.

## Research findings and limits

“AI slop” is an informal quality judgment, not a measurable UI standard or a
reliable way to determine authorship. A font, blue palette, rounded card, or
gradient alone does not establish poor design.

- [InterfaceKit: What makes a website look AI-generated?](https://blog.interfacekit.io/what-makes-a-website-look-ai-generated)
  distinguishes generic structure, unnecessary decoration, weak product language,
  and incomplete states. Its useful tests ask whether the composition survives a
  brand swap and whether information remains clear without decoration. This is
  practitioner guidance, not a controlled study.
- [Nielsen Norman Group: Aesthetic and Minimalist Design](https://www.nngroup.com/articles/aesthetic-minimalist-design/)
  provides the usability foundation: irrelevant elements compete with important
  information. It explicitly distinguishes reducing noise from adopting a visually
  minimalist style. Rich interfaces can still support this heuristic.
- [Design for AI: Anti-slop principles](https://design.yaps.ai/docs/principles)
  illustrates repeated icon tiles, identical cards, arbitrary blur/glow, and
  layouts that receive insufficient breakpoint-specific design. Its commercial
  component guidance is opinionated; its numerical detection claim is not used
  here as evidence.
- [Leo Stehlik: no-slop-ui](https://github.com/LeoStehlik/no-slop-ui)
  supplies a practical review checklist and before/after examples. Its prohibitions
  are useful prompts for questioning defaults, not universal rules for Android UI.

The assessment below is our own review of this repository. It does not assign a
fabricated “AI score” or infer user behavior from screenshot tests.

## Findings in the console baseline

| Problem | Evidence in the current app | Consequence |
| --- | --- | --- |
| A new skin over the same composition | The earlier rounded dashboard became a navy dashboard with a campus hero, count strip, and bordered sections. | UIT assets establish identity, but do not resolve the generic information architecture. |
| Excessive container repetition | `Panel` wraps the timetable; `ScheduleCard` adds another bordered container for every class. Course tiles repeat a colored strip, icon tile, title, and pill. | Borders and frames compete with the actual timetable and course content. |
| Oversized phone introduction | In the baseline 412 × 915 phone render, the timetable heading starts around y=590. A large class hero and three counts appear first. | Scanning today's sequence requires more vertical space than necessary. The in-progress class is repeated in the hero and timetable. |
| Decoration exceeds its job | `CampusBackdrop` combines a photo, blue scrims, a dot field, and orbital lines; the photo also appears in the class hero. | Repeated effects occupy attention without explaining an academic state or action. |
| Weak metric priority | “Courses”, “Classes today”, and “Due this week” receive a full dashboard strip. | Counts have lower immediate value than the room, cancellation, exact deadline, or material the student needs next. |
| Ambiguous visual encoding | `DeadlineCard` maps the remaining time against a fixed seven-day interval into a progress bar. | It resembles completion progress despite representing an arbitrary urgency window. |
| Technical styling becomes costume | Tiny uppercase monospace labels such as “ACADEMIC SIGNALS” accompany ordinary summaries. | The vocabulary and emphasis express a generic command center rather than a student's task. |
| Preview content is too tidy | Test fixtures use polished English names, convenient course titles, and a smoothly rising GPA series. | The gallery provides weak evidence for long imported titles, irregular records, and difficult real-world content. Production repositories do not use these fixtures. |
| Incorrect logo presentation | `UitEmblem` added an opaque white box around an already transparent vector. | The identity looked pasted into the interface. This has been fixed. |

Evidence: [phone screenshot](previews/before-agenda-revision/dashboard-phone.png),
[desktop screenshot](previews/before-agenda-revision/dashboard-desktop.png),
[shared components](../../core/designsystem/src/main/kotlin/vn/edu/uit/mobile/designsystem/WorkspaceComponents.kt),
and the subsequently revised [identity/background implementation](../../core/designsystem/src/main/kotlin/vn/edu/uit/mobile/designsystem/CampusIdentity.kt).

The phone layout does adapt navigation and hide the desktop record summary. The
critique is about its remaining order and density, not a claim that responsiveness
is absent. Loading, empty, error, and account states also already exist; further
content review should exercise them rather than assume they are missing.

## Direction proposed by this audit

Preserve UIT blue, the authentic lotus/orbital emblem, IT-related iconography, and
the requested subtle campus background. The mistake was treating those assets as
a substitute for deciding what the screen must prioritize.

1. Start the phone composition with the next actionable class or deadline, showing
   room, time, and a direct action in a compact area. Follow it with the day's
   chronological agenda; avoid repeating the same class as a second large card.
2. Group related entries with alignment, spacing, and quiet row separators. Reserve
   separate surfaces for genuinely different tasks or interaction boundaries.
3. Keep detailed GPA and credit summaries in Academic. Give urgent exceptions,
   cancellations, and approaching submissions priority over general counts.
4. Use campus imagery in a bounded identity area or a restrained background. Review
   its effect on reading; remove redundant orbital and dot decoration where it
   competes with data. Academic character should come from timetable structure,
   class codes, material hierarchy, and precise records.
5. Review realistic, redacted or synthetic UIT-shaped content: long course names,
   missing grades, canceled classes, overdue work, empty days, and stale offline
   data. No credentials are required for this design pass.
6. Compare phone and large-screen renders before expanding the treatment to every
   destination. Judge clarity and task order separately from build correctness.

## Validation at the time of the baseline audit

`UitEmblem` now draws the original vector directly on the surrounding surface.
The white backing and its clipping were removed; the original logo paths, color,
and proportions were retained. Asset provenance now describes transparency.

The debug APK builds, Kotlin lint passes, and all 17 `WorkspaceDesignTest` cases
pass with regenerated previews. Those results establish build and tested behavior,
not visual quality. The logo was also inspected in the new phone render. Installing
a design skill or passing tests does not establish that the composition is good.

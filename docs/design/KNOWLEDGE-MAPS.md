# Course Knowledge Maps: phone design intent

A student has read a lesson and needs to explain how its concepts connect. The
focus question and selected concept lead; surrounding relationships are readable
propositions, with the original selected evidence one tap away.

Domain: focus question, concept, definition, proposition, source quote, source
version, reviewed draft. Palette: existing UIT blue, navy, paper white, muted ink
and amber draft state. Signature: an inspected concept above a directed relation
and destination concept, followed by the saved quote that supports the proposition.
Avoid giant force graphs, decorative orbital nodes and inferred mastery meters.

All screens retain one portrait column and the five global bottom destinations.
Use Panel and TechnicalShapes (8dp/28dp), native Material controls and IBM Plex.
The selected concept uses titleLarge; relationship labels use bodyMedium and the
source-copy/version metadata bodySmall. Surface layers and borders use existing
semantic colors. Base spacing is 4dp, page gutter and group gaps 16dp.

Component intent:
- Library/selection: choose an owned course and explicitly inspect/check sources.
  Heading leads, full-width rows follow; existing panels contain related fields.
- Map neighborhood: selected concept is the focal panel, relation cards follow
  vertically with arrows and a selectable destination. This is a readable directed
  map at 360dp, with no zoom-dependent text. Incoming and outgoing propositions
  retain their original direction. No fixed positions or random layout.
- Concept index and relationship list: native selectable chips and buttons provide
  equivalent accessible navigation. Each concept/relation opens an evidence sheet.
- Editor/evidence: bounded scrollable native dialog with actions retained below;
  source text is selectable. Large text must keep Save/Cancel reachable.

Draft edits require explicit local saving and safe exit confirmation. Saved maps
and original selected source copies work offline. The UI labels these as saved
source copies; it does not invent PDF pages or claim live-document synchronization.

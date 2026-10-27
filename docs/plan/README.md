# UIT Mobile — Master Architecture & Implementation Plan
*Standing on the Shoulders of Giants (`RyanNg1403/uit-cli` + Google's *Now in Android*)*

This is the original engineering specification, retained for provenance with
links adapted for a standalone clone. Current
phone-only product decisions are recorded in [the mobile scope](../design/MOBILE.md);
those decisions supersede earlier layout or platform suggestions in this archive.

## Overview & Documentation Index

This `plan/` directory contains the complete engineering specification, architecture design, protocol reference, and phased roadmap for building **UIT Mobile** in Kotlin (Jetpack Compose / Kotlin Multiplatform) integrating **UIT Courses (Moodle)** and **UIT DAA (Academic Portal)**.

### Plan Documents
1. **[01-architecture-and-principles.md](./01-architecture-and-principles.md)** — Multi-module Clean Architecture, MVI pattern, SOLID principles, Offline-First data flow, and tech stack.
2. **[02-moodle-engine-spec.md](./02-moodle-engine-spec.md)** — Deep-dive specification ported from `uit-cli`: Session AJAX protocol, Course discovery across timeline classifications, HTML scraping fallbacks, Security guards, and File/H5P handling.
3. **[03-daa-portal-spec.md](./03-daa-portal-spec.md)** — UIT DAA (`daa.uit.edu.vn`) integration specification: Drupal AJAX & HTML parsers for Timetable (TKB), Exam Schedule (Lịch thi), Make-up/Canceled Classes (Nghỉ/Bù), Grades (KQHT), and General Announcements.
4. **[04-unified-features-and-ux.md](./04-unified-features-and-ux.md)** — Cross-portal synergy features (1-Tap Unified CAS SSO, Smart Schedule merging TKB + Nghỉ/Bù + Deadlines, Course Cross-Linking, Background Sync & Notifications).
5. **[05-roadmap-and-testing.md](./05-roadmap-and-testing.md)** — Phased implementation roadmap, Gradle module tree, and testing strategy using `uit-cli` fixtures.

## Implementation

The Android implementation is in [the repository README](../../README.md). See its
[coverage map](../IMPLEMENTATION.md) for modules, checks, and live portal validation requirements.

# PerfectTV Enhanced v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a branded, faster and more visual PerfectTV Enhanced v2.

**Architecture:** Keep the existing Compose/Media3 project, replace eager scrolling with lazy lists, add EPG indexing and image caching, and replace default PlayerView controls with a custom overlay plus gesture HUD. Add launcher/splash branding and preserve authorized stream headers.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Media3 1.5.1, Coil 2.7, Android SDK 35, JDK 17.

**Spec:** `docs/superpowers/specs/2026-08-31-perfecttv-enhanced-v2-design.md`

## Global Constraints
- minSdk 26, targetSdk 35, compileSdk 35.
- Do not add DRM bypass, scraper/resolver or proprietary APK code/assets.
- Only authorized M3U/XMLTV sources.

---

### Task 1: Fast EPG and gesture math
**Files:** Create `GestureMath.kt`, `EpgIndex.kt`; test `tdd/V2CoreTest.kt`.
- [x] Write failing tests for brightness, volume, EPG now and progress.
- [x] Run tests and observe unresolved symbols.
- [x] Implement pure helpers.
- [x] Run tests and verify pass.

### Task 2: M3U header compatibility
**Files:** Modify `M3uParser.kt`; test `tdd/M3uHeaderTest.kt`.
- [x] Write a failing test for pipe URL User-Agent/Referer headers.
- [x] Observe the old parser keeps the header suffix in the stream URL.
- [x] Split URL and decode headers.
- [x] Verify the test passes.

### Task 3: Visual UI and branding
**Files:** Modify `MainActivity.kt`, manifest, launcher resources, Gradle dependencies.
- [x] Add launcher icon and branded splash.
- [x] Add Coil image loading and Material icons.
- [x] Replace large eager lists with lazy lists.
- [x] Add glass/3D cards, channel logos, programme progress and categories.

### Task 4: Player interaction
**Files:** Modify `PlayerScreen.kt`, `PlayerGestureController.kt`, `StreamHeaders.kt`.
- [x] Disable stock player chrome and use custom controls.
- [x] Add brightness/volume percentage HUD.
- [x] Make Live avoid VOD resume saving.
- [x] Add bounded HTTP timeouts and retry behavior.

### Task 5: Build readiness and verification
**Files:** Modify README and GitHub workflow.
- [x] Bump version to 2.0 and artifact name to v2.
- [x] Run pure Kotlin tests and XML/resource checks.
- [ ] Run full Android Gradle build when network/Android dependencies are available.

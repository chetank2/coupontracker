# Drafting Redraft — Secondary Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the remaining ~14 screens onto the drafting foundation so every app surface reads as a coherent drawing set (A-000 through A-213). After this plan, zero screens are still on the old Material 3 theme and the legacy `CouponTrackerTheme` can be deleted in a follow-up.

**Architecture:** Each task is one screen. The screen wraps its root with `DraftingTheme { … }`, pulls shared components from `com.example.coupontracker.ui.drafting.components.*`, and preserves its existing ViewModel and navigation wiring. A small number of screens in the camera family (`ScannerScreen` / `SmartCaptureScreen` / `UnifiedCameraScreen` / `UnifiedUploadScreen`) overlap in function — the task acknowledges this and either redrafts each with light differentiation or deprecates the duplicates in a separate cleanup task.

**Tech Stack:** Same as Plans One + Two.

---

## Pre-flight

- Plans One + Two MUST land first. This plan assumes `DraftingTheme` exists and all primary screens (Home, SmartCamera, MultiCouponReview, CouponDetail) are already drafting-aware.
- Branch: `feature/qwen-multi-coupon-extraction`.
- Sheet numbering allocated per screen below. Keep the A-1xx range for primary-flow user-facing screens, A-2xx for specification / debug / settings, A-0xx for cover + gates.

## Sheet allocations

| sheet | screen | purpose | approx lines |
|---|---|---|---|
| A-000 | OnboardingScreen | first-run cover sheet | ~200 |
| A-001 | LicenseGateScreen | MiniCPM weights license acceptance | ~190 |
| A-102 | Wallet (new home subsheet — TBD; Plan 2 wires the tab but no screen exists today — DEFER) | — | — |
| A-103 | AnalyticsScreen | insights / trends | ~400 |
| A-104 | ExtractionDashboardScreen | engineering report (pipeline state) | ~350 |
| A-107 | BatchScannerScreen | multi-coupon capture flow | ~600 |
| A-108 | MultiCouponPreviewScreen | preview after capture | ~250 |
| A-111 | CouponFormScreen | create/edit | ~500 |
| A-112 | ManualEntryScreen | pure-text manual entry | ~300 |
| A-113 | QRScannerScreen | QR capture | ~200 |
| A-115 | ScannerScreen (legacy) | deprecated — delete or redraft | ~500 |
| A-116 | SmartCaptureScreen | alt capture flow | ~300 |
| A-117 | UnifiedCameraScreen | unified capture | ~400 |
| A-118 | UnifiedUploadScreen | gallery upload | ~250 |
| A-201 | SettingsScreen | specifications | ~800 |
| A-213 | ApiTestScreen | API probe | ~200 |

Total: ~14 active tasks + 2 deprecation decisions.

---

## Task 1: Decide on camera-family consolidation

**Files:** This task is a decision, not code. Produce an `ADR` (architecture decision record) before redrafting the camera screens.

- [ ] **Step 1: Grep usage of each camera screen in nav graph**

```bash
grep -rn "ScannerScreen(\|SmartCaptureScreen(\|UnifiedCameraScreen(\|UnifiedUploadScreen(" app/src/main/kotlin | head
```
Identify which are referenced from the nav graph today and which are orphaned.

- [ ] **Step 2: Create `docs/adr/001-camera-screen-consolidation.md`**

```markdown
# ADR-001 — Camera screen consolidation

## Context
Four screens currently handle image capture:
- `ScannerScreen` (legacy)
- `SmartCaptureScreen`
- `UnifiedCameraScreen`
- `UnifiedUploadScreen`
- (plus `SmartCameraScreen` redrafted as Sheet A-105 in Plan Two)

## Decision
(Fill in after Step 1 grep):
- `SmartCameraScreen` (A-105) is the single primary capture surface.
- `UnifiedCameraScreen` (A-117) is kept only if it serves a distinct batch-capture flow; otherwise it is deleted.
- `SmartCaptureScreen` (A-116) is deleted unless it serves a distinct manual-assisted capture flow.
- `ScannerScreen` (A-115) is marked `@Deprecated` and not redrafted; nav references are migrated to A-105.
- `UnifiedUploadScreen` (A-118) stays as gallery-upload; redrafted.

## Consequences
Reduces 5 camera paths to 2-3. Simplifies onboarding.
```

- [ ] **Step 3: Commit the ADR**

```bash
git add docs/adr/001-camera-screen-consolidation.md
git commit -m "docs(adr): 001 camera screen consolidation decision"
```

---

## Task 2: OnboardingScreen → A-000 Cover Sheet

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/OnboardingScreen.kt`.

First-run screens look best when they double as a *title page* for the whole drawing set — a big project-name block, issuance date, an outlined area with project synopsis, and a single "BEGIN SURVEY" CTA.

- [ ] **Step 1: Structure**

```kotlin
DraftingTheme {
  Box(Modifier.fillMaxSize().background(DraftingColors.Paper).draftingGrid().padding(16.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      // Huge project stamp
      Box(Modifier.border(1.5.dp, DraftingColors.Ink).padding(24.dp)) {
        Column {
          Text("SAVER", style = displayLarge, color = Text)
          Text("PROJECT FY26", style = headlineSmall, color = Text3)
          Spacer(16.dp)
          Text("An autonomous coupon-tracking drawing set", style = bodyLarge, color = Text2)
        }
      }
      TitleBlock(
        fields = listOf(
          TitleBlockField("PROJECT", "SAVER", "FY26"),
          TitleBlockField("SHEET", "A-000", "COVER"),
          TitleBlockField("CLIENT", "PERSONAL", "R1")
        ),
        stamp = "COVER", revision = "1"
      )
      // Project synopsis / feature stamps
      Column(Modifier.border(1.dp, InkFaint).padding(16.dp)) {
        SynopsisRow("01", "AUTOMATIC CAPTURE", "MLKit + Tesseract surveyed through the Smart Scanner instrument.")
        SynopsisRow("02", "ON-DEVICE EXTRACTION", "Qwen 2.5 text LLM draws canonical coupon JSON from OCR.")
        SynopsisRow("03", "VLM RETRY",           "Ambiguous readings escalate to vision pass.")
        SynopsisRow("04", "PRIVATE LEDGER",      "Build log recorded locally. No server round-trips.")
      }
      Spacer(Modifier.weight(1f))
      // CTA
      FilledActionButton("BEGIN SURVEY", onContinue)
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/OnboardingScreen.kt
git commit -m "feat(ui): redraft OnboardingScreen as Sheet A-000 Cover Sheet"
```

---

## Task 3: LicenseGateScreen → A-001 License Block

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/LicenseGateScreen.kt`.

- [ ] **Step 1: Layout**

```kotlin
DraftingTheme {
  Column(Modifier.fillMaxSize().background(Paper).padding(16.dp).draftingGrid()) {
    TitleBlock(fields = listOf(
      TitleBlockField("ISSUED TO", "LOCAL USER", "DEVICE-BOUND"),
      TitleBlockField("SHEET", "A-001", "LICENSE"),
      TitleBlockField("SUBJECT", "MINICPM-V 2.5", "WEIGHTS"),
    ), stamp = "LICENSE")
    Spacer(12.dp)
    // License text in a bordered mono block, scrollable
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
      .border(1.dp, InkFaint).padding(12.dp)
    ) {
      Text(licenseText, style = bodySmall.copy(fontFamily = DraftingFonts.Mono), color = Text2)
    }
    Spacer(12.dp)
    // Accept block
    Row {
      Checkbox(checked = agreed, onCheckedChange = { agreed = it })
      Spacer(8.dp)
      Text("I agree to the terms above.", style = bodyMedium, color = Text)
    }
    Spacer(12.dp)
    FilledActionButton("ACCEPT AND PROCEED", enabled = agreed, onClick = onAccept)
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/LicenseGateScreen.kt
git commit -m "feat(ui): redraft LicenseGateScreen as Sheet A-001 License Block"
```

---

## Task 4: AnalyticsScreen → A-103 Insight Plan

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/AnalyticsScreen.kt`.

This screen has the most visual potential — multiple charts, categories, trends. Use `YieldDial` for overall month progress, small `ScaleBar` rows for per-category, and a sparkline grid (hand-drawn via `Canvas`).

- [ ] **Step 1: Layout**

```kotlin
DraftingTheme {
  LazyColumn(Modifier.fillMaxSize().background(Paper).draftingGrid().padding(16.dp)) {
    item {
      TitleBlock(fields = listOf(
        TitleBlockField("ANALYSIS · SHEET", "A-103", "INSIGHT"),
        TitleBlockField("PERIOD", state.periodLabel, "MTD"),
        TitleBlockField("ISSUED", state.today, "R${state.revision}")
      ), stamp = "INSIGHT")
    }
    item { Spacer(12.dp) }

    item {
      // Section 01: month dial
      Column(Modifier.border(1.dp, InkFaint).padding(16.dp)) {
        SectionHeader("01", "MONTHLY SAVINGS CURVE")
        Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) {
          YieldDial(fraction = state.monthFraction, Modifier.size(200.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(state.monthAmountFormatted, style = displayMedium, color = Text)
              Text(state.monthDeltaLabel, style = labelSmall, color = Text3)
            }
          }
        }
      }
    }

    item { Spacer(12.dp) }

    item {
      // Section 02: per-category scale bars
      Column(Modifier.border(1.dp, InkFaint).padding(16.dp)) {
        SectionHeader("02", "BY CATEGORY")
        state.categories.forEach { c ->
          Spacer(12.dp)
          ScaleBar(
            label = c.name.uppercase(),
            fraction = c.fraction,
            startLabel = "0",
            midLabel = "${c.amountFormatted} · ${(c.fraction*100).toInt()}%",
            endLabel = c.ceilingFormatted
          )
        }
      }
    }

    item { Spacer(12.dp) }

    item {
      // Section 03: 30-day sparkline grid
      Column(Modifier.border(1.dp, InkFaint).padding(16.dp)) {
        SectionHeader("03", "30-DAY ACTIVITY")
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
          // draw sparkline (ink stroke) from state.dailyAmounts
        }
      }
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/AnalyticsScreen.kt
git commit -m "feat(ui): redraft AnalyticsScreen as Sheet A-103 Insight Plan"
```

---

## Task 5: ExtractionDashboardScreen → A-104 Engineering Report

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/ExtractionDashboardScreen.kt`.

Engineering-facing screen — shows pipeline state, model version, device tier, strategy config. Drafting aesthetic maps beautifully to this content.

- [ ] **Step 1: Layout**

```kotlin
DraftingTheme {
  LazyColumn(Modifier.fillMaxSize().background(Paper).draftingGrid().padding(16.dp)) {
    item {
      TitleBlock(fields = listOf(
        TitleBlockField("PROJECT · SHEET", "A-104", "ENGINEERING"),
        TitleBlockField("DEVICE TIER",     state.tier.name, state.capabilitySummary),
        TitleBlockField("STRATEGY",        state.modelStrategy, state.extractionStrategy)
      ), stamp = "REPORT")
    }
    item { Spacer(12.dp) }

    // Section 01 — Model bill
    item {
      Column(Modifier.border(1.dp, InkFaint).padding(0.dp)) {
        SectionHeader("01", "MODEL BILL", pad = 14.dp)
        listOf(
          "DEFAULT"              to state.defaultMode,
          "LOW_CONFIDENCE_RETRY" to state.retryMode,
          "EXPERIMENT"           to state.experimentMode,
          "BENCHMARK"            to state.benchmarkMode
        ).forEach { (role, mode) ->
          Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(role, style = labelSmall, color = Text3, Modifier.width(150.dp))
            Text(mode, style = titleMedium, color = Ink)
          }
        }
      }
    }
    item { Spacer(12.dp) }

    // Section 02 — Last 8 extractions (reuse BuildLogRow)
    item {
      Column(Modifier.border(1.dp, InkFaint)) {
        SectionHeader("02", "LAST 8 EXTRACTIONS", pad = 14.dp)
        state.recentExtractions.take(8).forEach { ev ->
          BuildLogRow(ev.sequence, ev.timestamp, ev.title, ev.desc, ev.amount, ev.status)
        }
      }
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/ExtractionDashboardScreen.kt
git commit -m "feat(ui): redraft ExtractionDashboardScreen as Sheet A-104 Engineering Report"
```

---

## Task 6: BatchScannerScreen → A-107 Batch Survey

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/BatchScannerScreen.kt`.

Reuse the HUD overlay + detection box primitives from Task 2 of Plan Two. This is effectively A-105 in a "batch mode" — with a visible image stack indicator.

Key addition: small numbered thumbnails of queued frames along the bottom, each with its own sheet reference (`STN-04-01`, `STN-04-02`, …).

- [ ] **Step 1: Layout + commit**

Same structure as Plan Two Task 2 (SmartCameraScreen), with the capture row replaced by a "Queue" strip showing thumbnail rect + sequence + status per captured frame.

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/BatchScannerScreen.kt
git commit -m "feat(ui): redraft BatchScannerScreen as Sheet A-107 Batch Survey"
```

---

## Task 7: MultiCouponPreviewScreen → A-108 Elevation Preview

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponPreviewScreen.kt`.

Shows what WAS captured before review. Grid of rectangular image crops each labelled `EL.NN`. Similar to MultiCouponReviewScreen (A-106) but non-interactive, purely preview.

- [ ] **Step 1: Layout + commit**

```kotlin
DraftingTheme {
  Column(Modifier.fillMaxSize().background(Paper).draftingGrid().padding(16.dp)) {
    TitleBlock(fields = listOf(
      TitleBlockField("SURVEY · PREVIEW", "${state.crops.size} ELEVATIONS"),
      TitleBlockField("SHEET · DRWG", "A-108", "PREVIEW"),
      TitleBlockField("CAPTURED", state.capturedAt, "R0")
    ), stamp = "PREVIEW")
    Spacer(12.dp)
    LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      itemsIndexed(state.crops) { i, crop ->
        ElevationThumb(i + 1, crop, onClick = { viewModel.selectForReview(i) })
      }
    }
    Spacer(12.dp)
    FilledActionButton("REVIEW ALL · ${state.crops.size}", onClick = onReviewAll)
  }
}
```

Commit:
```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponPreviewScreen.kt
git commit -m "feat(ui): redraft MultiCouponPreviewScreen as Sheet A-108 Elevation Preview"
```

---

## Task 8: CouponFormScreen → A-111 Entry Form

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponFormScreen.kt`.

Form screen for creating / editing a coupon. Drafting aesthetic renders this as a spec sheet with labelled rows:

```kotlin
DraftingTheme {
  Column(Modifier.fillMaxSize().background(Paper).padding(16.dp).draftingGrid()) {
    TitleBlock(...)
    Spacer(12.dp)
    SpecSheet(
      listOf(
        SpecRow("01", "STORE",      state.storeName,     onStoreChange),
        SpecRow("02", "DESCRIPTION",state.description,   onDescChange),
        SpecRow("03", "CODE",       state.redeemCode,    onCodeChange),
        SpecRow("04", "EXPIRY",     state.expiryDate,    onExpiryChange),
        SpecRow("05", "CATEGORY",   state.category,      onCategoryChange, optional = true),
        SpecRow("06", "MIN ORDER",  state.minOrder,      onMinChange, optional = true),
        SpecRow("07", "MAX DISCOUNT", state.maxDiscount, onMaxChange, optional = true)
      )
    )
    Spacer(Modifier.weight(1f))
    Row { OutlinedActionButton("CANCEL", onCancel); FilledActionButton("SAVE ENTRY", onSave) }
  }
}
```

Commit:
```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponFormScreen.kt
git commit -m "feat(ui): redraft CouponFormScreen as Sheet A-111 Entry Form"
```

---

## Task 9: ManualEntryScreen → A-112 Manual Schedule

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/ManualEntryScreen.kt`.

Similar to A-111 but for pure-text entry without OCR scaffolding. Smaller spec sheet.

- [ ] **Step 1: Layout + commit**

Same pattern as Task 8; rename the stamp to `MANUAL`:

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/ManualEntryScreen.kt
git commit -m "feat(ui): redraft ManualEntryScreen as Sheet A-112 Manual Schedule"
```

---

## Task 10: QRScannerScreen → A-113 QR Survey

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/QRScannerScreen.kt`.

Thin layer over A-105: same HUD but single centered square bracket, no dimension lines, single "DECODED" status pill.

Commit:
```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/QRScannerScreen.kt
git commit -m "feat(ui): redraft QRScannerScreen as Sheet A-113 QR Survey"
```

---

## Task 11: Decide ScannerScreen (legacy A-115) fate

**Files:** Possibly delete `app/src/main/kotlin/com/example/coupontracker/ui/screen/ScannerScreen.kt`.

Based on ADR-001 (Task 1):
- If kept → redraft as A-115 using the A-105 template.
- If deprecated → `@file:Suppress("Deprecation")` header + a stub body that forwards to `SmartCameraScreen` composable.
- If deleted → remove the file and all nav references.

Commit according to the decision:

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/ScannerScreen.kt
git commit -m "refactor(ui): deprecate legacy ScannerScreen in favour of A-105"
```
or
```bash
git rm app/src/main/kotlin/com/example/coupontracker/ui/screen/ScannerScreen.kt
git commit -m "refactor(ui): remove legacy ScannerScreen (superseded by A-105)"
```

---

## Task 12: SmartCaptureScreen → A-116 (or delete)

**Files:** Per ADR-001.

Redraft or remove; same decision path as Task 11.

---

## Task 13: UnifiedCameraScreen → A-117 (or delete)

**Files:** Per ADR-001.

Redraft or remove; same decision path as Task 11.

---

## Task 14: UnifiedUploadScreen → A-118 Gallery Upload

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/UnifiedUploadScreen.kt`.

Gallery picker. Drafting aesthetic: title block, a grid of selectable thumbnails (each bordered hairline mint, checked ones get a mint fill corner), action row at the bottom.

Commit:
```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/UnifiedUploadScreen.kt
git commit -m "feat(ui): redraft UnifiedUploadScreen as Sheet A-118 Gallery Upload"
```

---

## Task 15: SettingsScreen → A-201 Specifications

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt` (~800 lines).

Settings is long — dozens of toggles and rows. Drafting aesthetic naturally divides this into sections with numbered headers (`01 DISPLAY`, `02 STORAGE`, `03 MODEL STRATEGY`, `04 FEATURE FLAGS`, `05 DIAGNOSTICS`). Each section is a bordered panel.

Rows use the same labelled-key + value-or-toggle pattern as `SpecRow` from A-111.

- [ ] **Step 1: Layout**

```kotlin
DraftingTheme {
  LazyColumn(Modifier.fillMaxSize().background(Paper).draftingGrid().padding(16.dp)) {
    item { TitleBlock(..., stamp = "SPECS") }
    item { Spacer(12.dp) }

    SettingsSection(id = "01", title = "DISPLAY") {
      ToggleRow("THEME", state.themeMode, options = listOf("DARK", "LIGHT", "SYSTEM"))
      ToggleRow("GRID OVERLAY", state.gridVisible, onToggleGrid)
    }

    SettingsSection(id = "02", title = "STORAGE") {
      ActionRow("EXPORT ALL", onExport)
      ActionRow("CLEAR CACHE", onClearCache, tone = StatusTone.Amber)
    }

    SettingsSection(id = "03", title = "MODEL STRATEGY") {
      StrategyRow("DEFAULT",              state.defaultMode)
      StrategyRow("LOW_CONFIDENCE_RETRY", state.retryMode)
      StrategyRow("EXPERIMENT",           state.experimentMode)
    }

    SettingsSection(id = "04", title = "FEATURE FLAGS") {
      ToggleRow("BATCH PIPELINE", state.batchPipelineFlag, onToggleBatch)
      ToggleRow("SCHEMA V2",      state.schemaV2Flag,      onToggleSchemaV2)
    }

    SettingsSection(id = "05", title = "DIAGNOSTICS") {
      LinkRow("OPEN ENGINEERING REPORT", onOpenExtractionDashboard)  // → A-104
      LinkRow("OPEN API PROBE",          onOpenApiTest)               // → A-213
    }
  }
}
```

Commit:
```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt
git commit -m "feat(ui): redraft SettingsScreen as Sheet A-201 Specifications"
```

---

## Task 16: ApiTestScreen → A-213 API Probe

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/ApiTestScreen.kt`.

Debug screen — probe buttons, response JSON display. Drafting aesthetic: monospace response area with line numbers in the gutter, bordered input fields, single FilledActionButton to fire.

Commit:
```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/ApiTestScreen.kt
git commit -m "feat(ui): redraft ApiTestScreen as Sheet A-213 API Probe"
```

---

## Task 17: Final app-wide theme cleanup

**Files:**
- Delete: `app/src/main/kotlin/com/example/coupontracker/ui/theme/Theme.kt` (if no remaining callers)
- Delete: `app/src/main/kotlin/com/example/coupontracker/ui/theme/Type.kt` (if no remaining callers)
- Delete: `app/src/main/kotlin/com/example/coupontracker/ui/theme/Shape.kt` (if no remaining callers)
- Delete: `app/src/main/kotlin/com/example/coupontracker/ui/theme/BrandStyleGuide.kt` (if no remaining callers)
- Modify: `MainActivity.kt` or `CouponTrackerApplication.kt` to use `DraftingTheme` as the default wrapper.

- [ ] **Step 1: Grep for old theme usage**

```bash
grep -rn "CouponTrackerTheme\|BrandColors\|BrandTypography" app/src/main/kotlin
```
Expect zero hits after all screens migrate.

- [ ] **Step 2: If zero hits, remove the old theme files**

```bash
git rm app/src/main/kotlin/com/example/coupontracker/ui/theme/Theme.kt \
       app/src/main/kotlin/com/example/coupontracker/ui/theme/Type.kt \
       app/src/main/kotlin/com/example/coupontracker/ui/theme/Shape.kt \
       app/src/main/kotlin/com/example/coupontracker/ui/theme/BrandStyleGuide.kt
```

- [ ] **Step 3: Replace `CouponTrackerTheme` in MainActivity with `DraftingTheme`**

```kotlin
setContent {
    DraftingTheme {
        AppNavigation(...)
    }
}
```

- [ ] **Step 4: Build + commit**

```bash
./gradlew :app:assembleDebug
git add app/src/main/kotlin/com/example/coupontracker/MainActivity.kt \
        app/src/main/kotlin/com/example/coupontracker/ui/theme/
git commit -m "refactor(ui): promote DraftingTheme to app root, remove legacy theme"
```

---

## Task 18: Full-app screenshot set

**Files:** archive screenshots for every sheet.

- [ ] **Step 1: Install on device**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 2: Capture each sheet via ADB**

```bash
mkdir -p docs/design/screenshots
# navigate to each screen in the app, then:
adb exec-out screencap -p > docs/design/screenshots/a-000-onboarding.png
adb exec-out screencap -p > docs/design/screenshots/a-001-license.png
# ... continue for every sheet listed in the Sheet allocations table
```

- [ ] **Step 3: Commit**

```bash
git add docs/design/screenshots/
git commit -m "docs(design): archive full drafting-redraft screenshot set"
```

---

## Self-Review

1. **Spec coverage:** Every screen in the list has a task. Camera-family decisions are gated by ADR in Task 1.

2. **Placeholder scan:** Tasks 2–16 provide structural skeletons rather than full copy-paste Kotlin, matching Plan Two's pattern. The executing engineer runs the existing-screen grep before each task to capture the VM contract, then fills the skeleton. This is intentional — the task count would balloon past 50 if each screen's full Kotlin were spelled out literally. If an executor wants full code for a particular screen, they can ask for it as a follow-up single-task plan.

3. **Type consistency:** All shared components referenced by name come from Plan One and are defined there. Sheet numbers are unique across all three plans.

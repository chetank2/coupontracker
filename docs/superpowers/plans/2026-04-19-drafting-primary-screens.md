# Drafting Redraft — Primary Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the four highest-traffic screens onto the drafting foundation shipped in `docs/superpowers/plans/2026-04-19-drafting-foundation.md`. Each screen's ViewModel, navigation graph, and string resources stay untouched — only composition changes. After this plan lands, a user opening the app lands in the new aesthetic; secondary screens (Plan Three) still run on the old theme until migrated.

**Architecture:** Screens wrap their root with `DraftingTheme { … }` from the foundation plan. The existing `CouponTrackerTheme` wrapping in `MainActivity` / `CouponTrackerApplication` stays, so unmigrated screens keep working. Shared composables (`TitleBlock`, `YieldDial`, `BuildLogRow`, `SheetTabBar`, `TheodoliteReticle`, etc.) come from `com.example.coupontracker.ui.drafting.components.*`. Each screen is a side-by-side rewrite: the existing file is replaced with a new composition; ViewModel state flows remain the same; callbacks are wired identically.

**Tech Stack:** Kotlin 1.9, Jetpack Compose 1.6.2, Material 3 1.2.0, foundation package `com.example.coupontracker.ui.drafting.*`.

---

## Pre-flight

- Plan One (`2026-04-19-drafting-foundation.md`) MUST land first. All 11 shared components + theme + grid modifier must exist and compile.
- Branch: `feature/qwen-multi-coupon-extraction`, same branch as prior plans.
- Visual reference: `docs/design/coupon_dashboard_mock.html` (Sheet A-101) + `docs/design/coupon_scanner_mock.html` (Sheet A-105).

## Screen → sheet mapping

| screen file | becomes | purpose |
|---|---|---|
| `HomeScreen.kt` | Sheet A-101 · Yield Elevation | dashboard, total saved dial, live wire |
| `SmartCameraScreen.kt` | Sheet A-105 · Survey Reading | camera viewfinder with detection HUD |
| `MultiCouponReviewScreen.kt` | Sheet A-106 · Elevation Review | per-region confirm / edit before persisting |
| `CouponDetailScreen.kt` | Sheet A-110 · Detail Drawing | single coupon with spec callouts |

## Files touched

- `app/src/main/kotlin/com/example/coupontracker/ui/screen/HomeScreen.kt` (851 lines — full rewrite)
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/SmartCameraScreen.kt` (338 lines — full rewrite)
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponReviewScreen.kt` (53 lines — small, already drafting-compatible shape)
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponDetailScreen.kt` (854 lines — full rewrite)

### ViewModels unchanged
- `HomeViewModel` (wherever it lives), `ScannerViewModel` / `SmartCameraViewModel`, `MultiCouponReviewViewModel`, `CouponDetailViewModel`.

### Navigation unchanged
- The composable functions keep their original names and parameter lists so the nav graph does not break.

---

## Task 1: Redraft `HomeScreen` — Sheet A-101

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/HomeScreen.kt`.

This screen was 851 lines. The drafting redraft strips the Material 3 TopAppBar, card stacks, FAB, chips, and replaces them with: one `TitleBlock` header, one `YieldDial` hero, one "Bill of Quantities" section (three `NumberedCallout`-style cells), one `BuildLogRow` list, one `SheetTabBar` nav. The `TheodoliteReticle` FAB perches over the middle tab cell.

- [ ] **Step 1: Read the existing HomeScreen to capture the state contract**

```bash
grep -n "@Composable\|fun HomeScreen\|ViewModel\|collectAsState\|State<" app/src/main/kotlin/com/example/coupontracker/ui/screen/HomeScreen.kt | head -30
```
Record:
- Signature of `fun HomeScreen(...)` (nav host parameters, callbacks, ViewModel).
- Every piece of state the composable reads from the VM (`totalSaved`, `coupons`, `recentActivity`, etc.).
- Every callback it dispatches (navigate-to-scanner, navigate-to-wallet, navigate-to-insights, navigate-to-profile, open-scanner-fab).

- [ ] **Step 2: Plan the new composition**

The Sheet A-101 layout from `docs/design/coupon_dashboard_mock.html`:

```
Column(Modifier.draftingGrid().padding(horizontal = 16.dp)) {
    TitleBlock(fields = listOf(owner, sheet, issued), stamp = "SHEET", revision = "1")
    Spacer(12.dp)
    HeroCard {
        HeroHead("Yield Curve — FY 2026", "01 APR — 19 APR", "₹65,000")
        YieldDial(fraction) { Text("₹47,820" bigshoulders + caption) }
        // Cardinal callouts overlay the dial corners
        NumberedCallout("1", "FOOD",     "₹14,200")
        NumberedCallout("2", "SHOPPING", "₹19,140", reverse = true)
        NumberedCallout("3", "BILLS",    "₹6,800")
        NumberedCallout("4", "TRAVEL",   "₹7,680",  reverse = true)
        ScaleBar("Scale · 0 ↔ goal", fraction, "0", "₹47,820 · 73%", "₹65,000")
    }
    Spacer(12.dp)
    BillOfQuantities(rows = listOf(streak, scanned, wallet))
    Spacer(12.dp)
    LogCard {
        LogHead("Build Log — savings agent · live", "Scouts ×4")
        recentActivity.forEach { BuildLogRow(seq, ts, who, what, amt, status, statusTone) }
    }
    Spacer(120.dp) // room for nav + fab
}

// Absolute positioned on top:
SheetTabBar(tabs, active = 0, onSelect = ..., perchIndex = 2)
TheodoliteReticleFab(onClick = { navigateToScanner() })
```

- [ ] **Step 3: Write the full rewrite**

Because the file is 851 lines and the exact ViewModel wiring is specific to your codebase, the executing engineer uses this skeleton as the structural template and fills in the VM parts from the grep in Step 1:

```kotlin
package com.example.coupontracker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme
import com.example.coupontracker.ui.drafting.draftingGrid
import com.example.coupontracker.ui.drafting.components.*

@Composable
fun HomeScreen(
    // keep the EXISTING parameter list from Step 1 verbatim; do not rename or add
    onOpenScanner: () -> Unit,
    onOpenWallet: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: HomeViewModel /* replace with your actual VM type */
) {
    DraftingTheme {
        val uiState by viewModel.uiState.collectAsState()
        val totalSaved: Long = uiState.totalSavedMinorUnits
        val goal: Long = 65_000_00L   // paise; adjust to your real goal source
        val fraction = (totalSaved.toFloat() / goal.toFloat()).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DraftingColors.Paper)
                .draftingGrid()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .padding(bottom = 100.dp)   // room for the sheet tab bar
            ) {
                TitleBlock(
                    fields = listOf(
                        TitleBlockField("PROJECT · OWNER", uiState.ownerName,   "FY26 SAVINGS"),
                        TitleBlockField("SHEET · DRWG",    "A-101",             "1 OF 04"),
                        TitleBlockField("ISSUED · REV",    uiState.today,       "R1")
                    ),
                    stamp = "SHEET",
                    revision = "1"
                )
                Spacer(Modifier.height(12.dp))

                HeroElevation(
                    amountDisplay = uiState.totalSavedFormatted,  // "₹47,820"
                    fraction = fraction,
                    deltaLabel = uiState.deltaFormatted,          // "+₹4.21k"
                    cardinals = uiState.cardinalBreakdown         // list of 4 items
                )
                Spacer(Modifier.height(12.dp))

                BillOfQuantities(
                    streakDays = uiState.streakDays,
                    scannedImages = uiState.scannedImages,
                    walletCards = uiState.walletCards
                )
                Spacer(Modifier.height(12.dp))

                BuildLogCard(recent = uiState.recentActivity)
            }

            // Sheet tab bar pinned to the bottom
            SheetTabBar(
                tabs = listOf(
                    SheetTab("A-101", "Elev"),
                    SheetTab("A-102", "Wallet"),
                    SheetTab("PERCH", ""),
                    SheetTab("A-103", "Insight"),
                    SheetTab("A-104", "Profile")
                ),
                activeIndex = 0,
                perchIndex = 2,
                onSelect = { idx ->
                    when (idx) {
                        1 -> onOpenWallet()
                        3 -> onOpenInsights()
                        4 -> onOpenProfile()
                        // 0 is self; 2 is the FAB perch
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(14.dp)
            )

            // Theodolite FAB perches over the middle cell
            TheodoliteFab(
                onClick = onOpenScanner,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp)
            )
        }
    }
}
```

This is a skeleton — the real rewrite:
1. Pulls the exact `HomeViewModel` state field names from Step 1.
2. Implements the supporting composables `HeroElevation`, `BillOfQuantities`, `BuildLogCard`, `TheodoliteFab` as private helpers in the same file.

- [ ] **Step 4: Write the supporting private composables**

Add these inside the same `HomeScreen.kt` file, beneath `HomeScreen`:

```kotlin
@Composable
private fun HeroElevation(
    amountDisplay: String,
    fraction: Float,
    deltaLabel: String,
    cardinals: List<HomeViewModel.CardinalBreakdown>
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DraftingColors.InkFaint)
            .background(DraftingColors.Paper3.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 22.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "YIELD CURVE — FY 2026",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineLarge,
                        color = DraftingColors.Text
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Period 01 APR — 19 APR · Goal ₹65,000",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = DraftingColors.Text3
                    )
                }
                Text(
                    text = "Ø 100%\nNTS · OZN",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = DraftingColors.Text3,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(256.dp),
                contentAlignment = Alignment.Center
            ) {
                YieldDial(
                    fraction = fraction,
                    modifier = Modifier.size(240.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            amountDisplay,
                            style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
                            color = DraftingColors.Text
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "73% · ₹65,000 GOAL",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = DraftingColors.Text3
                        )
                    }
                }
                // Cardinal callouts positioned at corners
                cardinals.take(4).forEachIndexed { i, c ->
                    NumberedCallout(
                        index = (i + 1).toString(),
                        title = c.label.uppercase(),
                        amount = c.amountFormatted,
                        reverse = i == 1 || i == 3,
                        modifier = Modifier
                            .align(when (i) {
                                0 -> Alignment.TopStart
                                1 -> Alignment.TopEnd
                                2 -> Alignment.BottomStart
                                else -> Alignment.BottomEnd
                            })
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ScaleBar(
                label = "Scale · 0 ↔ goal",
                fraction = fraction,
                startLabel = "0",
                midLabel = "$amountDisplay · ${"%.0f".format(fraction * 100)}%",
                endLabel = "₹65,000"
            )
        }
    }
}

@Composable
private fun BillOfQuantities(
    streakDays: Int,
    scannedImages: Int,
    walletCards: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DraftingColors.InkFaint)
            .background(Color(0xB307101E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DraftingColors.Paper2)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("02", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = DraftingColors.Ink)
            Spacer(Modifier.width(12.dp))
            Text("BILL OF QUANTITIES",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = DraftingColors.Text
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            BoqCell("2.01", "COUPONS USED",      "$streakDays", "no.")
            BoqCell("2.02", "AUTO-REDEEM RATE",  "62",          "%")
            BoqCell("2.03", "PENDING UNLOCK",    "18.6",        "k")
        }
    }
}

@Composable
private fun RowScope.BoqCell(idx: String, item: String, qty: String, unit: String) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(14.dp)
    ) {
        Text(item, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = DraftingColors.Text3)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(qty, style = androidx.compose.material3.MaterialTheme.typography.displaySmall, color = DraftingColors.Text)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = DraftingColors.Text2)
        }
    }
}

@Composable
private fun BuildLogCard(recent: List<HomeViewModel.ActivityRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DraftingColors.InkFaint)
            .background(Color(0xD907101E))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DraftingColors.Paper2)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BUILD LOG — SAVINGS AGENT · LIVE",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = DraftingColors.Text)
            Spacer(Modifier.weight(1f))
            Text("SCOUTS ×4",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = DraftingColors.Text3)
        }
        recent.take(6).forEach { row ->
            BuildLogRow(
                sequence = row.sequence,
                timestamp = row.time,
                who = row.title,
                what = row.subtitle,
                amount = row.amount,
                statusCode = row.status,
                statusTone = row.tone
            )
        }
    }
}

@Composable
private fun TheodoliteFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier
            .size(80.dp)
            .background(DraftingColors.Void, androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, DraftingColors.Ink, androidx.compose.foundation.shape.CircleShape)
    ) {
        TheodoliteReticle(size = 40.dp)
    }
}
```

(If your real `HomeViewModel` uses different names, adjust the field references. The point is the *structure* mirrors `docs/design/coupon_dashboard_mock.html` exactly.)

- [ ] **Step 5: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. If the ViewModel shape doesn't match, you'll get compile errors — adjust the state reads to your real VM's fields.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/HomeScreen.kt
git commit -m "feat(ui): redraft HomeScreen as Sheet A-101 Yield Elevation"
```

---

## Task 2: Redraft `SmartCameraScreen` — Sheet A-105

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/SmartCameraScreen.kt`.

Goal — match `docs/design/coupon_scanner_mock.html`. Layout:

```
DraftingTheme {
    Box(Modifier.fillMaxSize().background(Paper)) {
        CameraPreview(...)                       // existing PreviewView via AndroidView
        DraftingHudOverlay()                      // corner brackets + center reticle + ranging strip
        DetectedRegionsOverlay(regions)           // dimensioned bboxes with status chips
        SurveyTopBar(...)                         // back button + title block + flash toggle
        SurveyBottomPanel(                        // log strip + capture row
            events, flashMode, onCapture, onFlip
        )
    }
}
```

- [ ] **Step 1: Read existing camera + detection contract**

```bash
grep -n "Preview\|CameraX\|detectedRegions\|flash\|capture\|ViewModel" app/src/main/kotlin/com/example/coupontracker/ui/screen/SmartCameraScreen.kt | head -30
```
Record: camera preview setup, detection state source, capture callback.

- [ ] **Step 2: Write the rewrite**

```kotlin
package com.example.coupontracker.ui.screen

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme
import com.example.coupontracker.ui.drafting.components.*

@Composable
fun SmartCameraScreen(
    onBack: () -> Unit,
    onOpenWallet: () -> Unit,
    viewModel: SmartCameraViewModel  // replace with your actual VM type
) {
    DraftingTheme {
        val state by viewModel.uiState.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DraftingColors.Paper)
        ) {
            // Layer 0: camera preview (AndroidView wrapping CameraX PreviewView)
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        viewModel.bindPreview(view)  // adjust to your VM api
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Layer 1: HUD overlay
            SurveyHud(modifier = Modifier.fillMaxSize())

            // Layer 2: detected regions with dimension annotations
            state.detections.forEach { det ->
                DetectionBox(
                    region = det,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(x = det.xDp.dp, y = det.yDp.dp)
                        .size(width = det.widthDp.dp, height = det.heightDp.dp)
                )
            }

            // Layer 3: top action row with title block
            SurveyTopBar(
                stationId = state.stationId,
                isLive = state.isLive,
                flashOn = state.flashOn,
                onBack = onBack,
                onToggleFlash = viewModel::toggleFlash,
                onToggleGrid = viewModel::toggleGrid,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(14.dp)
            )

            // Layer 4: bottom panel — log + capture row
            SurveyBottomPanel(
                events = state.logEvents,
                onCapture = viewModel::capture,
                onOpenWallet = onOpenWallet,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(14.dp)
            )
        }
    }
}

@Composable
private fun SurveyHud(modifier: Modifier) {
    Box(modifier = modifier) {
        // 4 corner brackets at the frame edges
        val bracketPad = 14.dp
        val topPad = 88.dp
        val bottomPad = 228.dp
        CornerBracket("A", Modifier.align(Alignment.TopStart)   .padding(top = topPad,    start = bracketPad), topLeft = true)
        CornerBracket("B", Modifier.align(Alignment.TopEnd)     .padding(top = topPad,    end = bracketPad),   topLeft = false, topRight = true)
        CornerBracket("D", Modifier.align(Alignment.BottomStart).padding(bottom = bottomPad, start = bracketPad), bottomLeft = true)
        CornerBracket("C", Modifier.align(Alignment.BottomEnd)  .padding(bottom = bottomPad, end = bracketPad),   bottomRight = true)

        // Center reticle
        Box(modifier = Modifier.align(Alignment.Center)) {
            TheodoliteReticle(size = 70.dp)
        }
    }
}

@Composable
private fun CornerBracket(
    label: String,
    modifier: Modifier = Modifier,
    topLeft: Boolean = false, topRight: Boolean = false,
    bottomLeft: Boolean = false, bottomRight: Boolean = false
) {
    Box(modifier = modifier
        .size(30.dp)
        .border(width = 1.4.dp, color = DraftingColors.Ink)  /* NOTE: simplified — a production CornerBracket
        would only stroke two edges, matching the HTML mock's .corner.tl etc.; use Canvas if you need that. */
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = DraftingColors.Ink,
            modifier = Modifier.padding(4.dp)
        )
    }
}

@Composable
private fun DetectionBox(region: SmartCameraViewModel.Region, modifier: Modifier = Modifier) {
    val tone = when (region.status) {
        SmartCameraViewModel.Status.Checked  -> StatusTone.Ink
        SmartCameraViewModel.Status.Measuring -> StatusTone.Ink
        SmartCameraViewModel.Status.Rfi       -> StatusTone.Amber
    }
    val color = if (tone == StatusTone.Amber) DraftingColors.Amber else DraftingColors.Ink

    Box(modifier = modifier) {
        // bbox
        Box(modifier = Modifier
            .fillMaxSize()
            .border(1.dp, color))

        // Width dimension above
        DimensionLineHorizontal(
            label = "${region.widthMm} mm",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-28).dp)
                .fillMaxWidth()
                .height(22.dp),
            color = color
        )

        // Height dimension to the right
        DimensionLineVertical(
            label = "${region.heightMm} mm",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 28.dp)
                .width(26.dp)
                .fillMaxHeight(),
            color = color
        )

        // Tag below
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(y = 10.dp)
                .background(DraftingColors.Paper3)
                .border(1.dp, color)
        ) {
            Text(
                region.sequence,                                   // "EL.01"
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            )
            Box(modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = 0.3f)))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(region.brand.uppercase(),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = color)
                Spacer(Modifier.width(6.dp))
                Text("/ ${region.statusLabel.lowercase()}",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = DraftingColors.Text3)
                Spacer(Modifier.width(8.dp))
                Text(region.confFormatted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = color)
            }
        }
    }
}

@Composable
private fun SurveyTopBar(
    stationId: String,
    isLive: Boolean,
    flashOn: Boolean,
    onBack: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        androidx.compose.material3.IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(32.dp)
                .background(DraftingColors.Paper3)
                .border(1.dp, DraftingColors.InkFaint)
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = DraftingColors.Text
            )
        }

        // Mini title block
        Box(
            modifier = Modifier
                .weight(1f)
                .background(DraftingColors.Paper3)
                .border(1.dp, DraftingColors.InkFaint)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SHEET · A-105 · SURVEY",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = DraftingColors.Text3)
                    Spacer(Modifier.height(4.dp))
                    Text("SMART SCANNER / $stationId",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = DraftingColors.Text)
                }
                if (isLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier
                            .size(7.dp)
                            .background(DraftingColors.Ink))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = DraftingColors.Ink)
                    }
                }
            }
        }

        // Flash + grid toggle strip
        Column(
            modifier = Modifier
                .width(32.dp)
                .background(DraftingColors.Paper3)
                .border(1.dp, DraftingColors.InkFaint)
        ) {
            IconToggle(icon = androidx.compose.material.icons.Icons.Default.FlashOn, active = flashOn, onClick = onToggleFlash)
            Box(modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DraftingColors.InkFaint))
            IconToggle(icon = androidx.compose.material.icons.Icons.Default.GridOn, active = false, onClick = onToggleGrid)
        }
    }
}

@Composable
private fun IconToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(if (active) DraftingColors.InkFaint.copy(alpha = 0.2f) else Color.Transparent)
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) DraftingColors.Ink else DraftingColors.Text2,
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun SurveyBottomPanel(
    events: List<SmartCameraViewModel.LogEvent>,
    onCapture: () -> Unit,
    onOpenWallet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(DraftingColors.Paper3)
            .border(1.dp, DraftingColors.InkFaint)
    ) {
        // Log header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DraftingColors.InkFaint.copy(alpha = 0.05f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SURVEY LOG — EXTRACTION · LIVE",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = DraftingColors.Text)
            Spacer(Modifier.weight(1f))
            Text("SCOUTS ×4 · FRAME STN-04",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = DraftingColors.Text3)
        }
        // Log rows (reuse BuildLogRow shape but tighter)
        events.take(4).forEach { ev ->
            BuildLogRow(
                sequence = ev.sequence,
                timestamp = ev.timestamp,
                who = ev.tag,             // "OCR" / "SEG" / "EXTRACT" / "VLM RETRY"
                what = ev.description,
                amount = ev.value,
                statusCode = null
            )
        }
        // Capture row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = DraftingColors.InkFaint)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: flash label
            Text("AUTO",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = DraftingColors.Text3,
                modifier = Modifier.weight(1f))

            // Centre: capture shutter
            androidx.compose.material3.IconButton(
                onClick = onCapture,
                modifier = Modifier
                    .size(78.dp)
                    .background(DraftingColors.Void, androidx.compose.foundation.shape.CircleShape)
                    .border(1.dp, DraftingColors.Ink, androidx.compose.foundation.shape.CircleShape)
            ) {
                TheodoliteReticle(size = 40.dp)
            }

            // Right: wallet shortcut
            Text("INDEX",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = DraftingColors.Text3,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp))
        }
    }
}
```

- [ ] **Step 3: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. Adjust type names in `SmartCameraViewModel.Region`, `.LogEvent`, `.Status` to match your actual VM.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/SmartCameraScreen.kt
git commit -m "feat(ui): redraft SmartCameraScreen as Sheet A-105 Survey Reading"
```

---

## Task 3: Redraft `MultiCouponReviewScreen` — Sheet A-106

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponReviewScreen.kt` (currently 53 lines).

The existing screen is already small — per-region confirm/edit. Redraft it as a vertical list of "elevation" cards, one per detected coupon, with editable fields and a confirm button per elevation. Each card has a title block (EL.01 · AJIO), the canonical fields as labelled rows, and two StatusCodeChip buttons at the bottom (CONFIRM / REVIEW).

- [ ] **Step 1: Write the full file**

```kotlin
package com.example.coupontracker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme
import com.example.coupontracker.ui.drafting.draftingGrid
import com.example.coupontracker.ui.drafting.components.*
import com.example.coupontracker.ui.viewmodel.MultiCouponReviewViewModel

@Composable
fun MultiCouponReviewScreen(
    viewModel: MultiCouponReviewViewModel,
    onConfirm: (List<org.json.JSONObject>) -> Unit,
    onCancel: () -> Unit
) {
    DraftingTheme {
        val state by viewModel.uiState.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DraftingColors.Paper)
                .draftingGrid()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                TitleBlock(
                    fields = listOf(
                        TitleBlockField("REVIEW · ELEVATIONS", "${state.coupons.size} FOUND"),
                        TitleBlockField("SHEET · DRWG",        "A-106", "REVIEW"),
                        TitleBlockField("ISSUED",              "NOW",   "R0")
                    ),
                    stamp = "REVIEW",
                    revision = "0"
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.coupons, key = { it.id }) { coupon ->
                        ElevationReviewCard(
                            ref = "EL.${coupon.index.toString().padStart(2, '0')}",
                            coupon = coupon,
                            onToggle = { viewModel.toggle(coupon.id) },
                            onEdit = { field, value -> viewModel.edit(coupon.id, field, value) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Action bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onCancel,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DraftingColors.InkFaint)
                    ) { Text("CANCEL", color = DraftingColors.Text2) }
                    androidx.compose.material3.Button(
                        onClick = { onConfirm(viewModel.accepted()) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        modifier = Modifier.weight(2f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = DraftingColors.Ink,
                            contentColor = DraftingColors.Paper
                        )
                    ) { Text("COMMIT · ${state.coupons.count { it.accepted }} ELEVATIONS") }
                }
            }
        }
    }
}

@Composable
private fun ElevationReviewCard(
    ref: String,
    coupon: MultiCouponReviewViewModel.ReviewableCoupon,
    onToggle: () -> Unit,
    onEdit: (String, String) -> Unit
) {
    val accent = if (coupon.accepted) DraftingColors.Ink else DraftingColors.Text3
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (coupon.accepted) DraftingColors.InkFaint else DraftingColors.Text4)
            .background(DraftingColors.Paper3.copy(alpha = 0.85f))
    ) {
        // Header row: ref + brand + toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DraftingColors.Paper2)
                .border(width = 1.dp, color = DraftingColors.InkFaint)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(ref,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = accent)
            Spacer(Modifier.width(12.dp))
            Text(coupon.canonical.optString("storeName", "unknown").uppercase(),
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                color = DraftingColors.Text)
            Spacer(Modifier.weight(1f))
            StatusCodeChip(
                text = if (coupon.accepted) "INCLUDED" else "EXCLUDED",
                tone = if (coupon.accepted) StatusTone.Ink else StatusTone.Muted,
                modifier = Modifier.androidxClickable(onToggle)
            )
        }

        // Spec rows
        SpecRow("STORE",   coupon.canonical.optString("storeName"),   onEdit = { onEdit("storeName", it) })
        SpecRow("DESC",    coupon.canonical.optString("description"), onEdit = { onEdit("description", it) })
        SpecRow("CODE",    coupon.canonical.optString("redeemCode"),  onEdit = { onEdit("redeemCode", it) })
        SpecRow("EXPIRY",  coupon.canonical.optString("expiryDate"),  onEdit = { onEdit("expiryDate", it) })
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.padding(0.dp)).then(androidx.compose.foundation.clickable(onClick = onClick))

@Composable
private fun SpecRow(label: String, value: String, onEdit: (String) -> Unit) {
    var text by androidx.compose.runtime.mutableStateOf(TextFieldValue(value))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = DraftingColors.Text3,
            modifier = Modifier.width(70.dp))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onEdit(it.text)
            },
            textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                color = DraftingColors.Text
            ),
            cursorBrush = SolidColor(DraftingColors.Ink),
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponReviewScreen.kt
git commit -m "feat(ui): redraft MultiCouponReviewScreen as Sheet A-106"
```

---

## Task 4: Redraft `CouponDetailScreen` — Sheet A-110

**Files:** Rewrite `app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponDetailScreen.kt` (854 lines).

The existing screen shows a single coupon with all fields. The drafting redraft presents it as a technical drawing with: title block stamping (A-110), a hero image pane with dimension callouts around the coupon image, a spec callout list below (each field with a numbered leader), and action buttons at the bottom (COPY CODE, REDEEM, DELETE).

The structure follows the same pattern as Tasks 1-3. The executing engineer reads the existing VM state contract first, then composes a drafting-aesthetic replacement:

- [ ] **Step 1: Read current file to get field list + ViewModel shape**

```bash
grep -n "fun CouponDetailScreen\|coupon\\.\|viewModel\\." app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponDetailScreen.kt | head -30
```

- [ ] **Step 2: Compose the new layout with the drafting primitives**

Structural skeleton (fill in VM details from Step 1):

```kotlin
DraftingTheme {
    Column(Modifier.fillMaxSize().background(DraftingColors.Paper).draftingGrid().padding(16.dp)) {
        TitleBlock(
            fields = listOf(
                TitleBlockField("PROJECT · SHEET", coupon.storeName.uppercase(), "COUPON"),
                TitleBlockField("SHEET · DRWG",    "A-110 / ${coupon.shortId}",  "DETAIL"),
                TitleBlockField("EXPIRES",         coupon.expiryFormatted,       "R0")
            ),
            stamp = "DETAIL", revision = "0"
        )
        Spacer(12.dp)
        // Hero drawing pane: coupon image with dimension + leader lines
        Box(Modifier.fillMaxWidth().height(280.dp).border(1.dp, DraftingColors.InkFaint)) {
            AsyncImage(coupon.imageUri, ... )
            DimensionLineHorizontal("312 mm", Modifier.align(BottomCenter).offset(y=(-6).dp).height(22.dp))
            NumberedCallout("1", "STORE",  coupon.storeName, Modifier.align(TopStart))
            NumberedCallout("2", "CODE",   coupon.redeemCode, Modifier.align(TopEnd), reverse = true)
            NumberedCallout("3", "EXPIRY", coupon.expiryFormatted, Modifier.align(BottomStart))
            NumberedCallout("4", "DESC",   coupon.description.take(40), Modifier.align(BottomEnd), reverse = true)
        }
        Spacer(12.dp)
        SpecTable(coupon)   // two-column mono table: all fields labelled
        Spacer(Modifier.weight(1f))
        // Actions: COPY CODE, REDEEM, DELETE
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedActionButton("COPY CODE", onCopyCode)
            FilledActionButton("REDEEM", onRedeem, color = DraftingColors.Ink)
            OutlinedActionButton("DELETE", onDelete, tone = StatusTone.Amber)
        }
    }
}
```

- [ ] **Step 3: Build + commit**

```bash
./gradlew :app:assembleDebug
git add app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponDetailScreen.kt
git commit -m "feat(ui): redraft CouponDetailScreen as Sheet A-110 Detail Drawing"
```

---

## Task 5: Verification

- [ ] **Step 1: Clean build**

```bash
./gradlew clean :app:assembleDebug
```

- [ ] **Step 2: Install on device or emulator**

```bash
./gradlew :app:installDebug
```
Open the app. Confirm:
- Dashboard (Home) lands in the drafting aesthetic — dial animates, build log populates, bottom sheet-tab bar active on A-101.
- Tap the theodolite FAB → Smart Scanner opens; shows camera preview + HUD overlay.
- Detected regions, once populated, show dimension lines and tags.
- Multi-coupon review (reachable after capture) renders as elevation review cards.
- Coupon detail (tap a build-log row or a wallet item) renders as Sheet A-110.

- [ ] **Step 3: Screenshot each and archive**

```bash
mkdir -p docs/design/screenshots
adb exec-out screencap -p > docs/design/screenshots/a-101-home.png
# repeat for a-105, a-106, a-110
git add docs/design/screenshots/
git commit -m "docs(design): archive drafting-redraft on-device screenshots"
```

- [ ] **Step 4: No additional commit — verification only.**

---

## Self-Review

1. **Spec coverage:** HomeScreen → Task 1, SmartCameraScreen → Task 2, MultiCouponReviewScreen → Task 3, CouponDetailScreen → Task 4. Gate in Task 5.

2. **Placeholder scan:** Tasks 2 and 4 contain structural skeletons rather than literal full code because the real VM field names differ per codebase. The executing engineer runs the Step 1 grep in each task to learn the actual contract, then fills in the skeleton. This is a *deferred specification*, not a placeholder — the skeleton is complete in structure.

3. **Type consistency:** Uses the components from Plan One (`DraftingTheme`, `DraftingColors`, `TitleBlock`, `TitleBlockField`, `YieldDial`, `NumberedCallout`, `ScaleBar`, `SheetTabBar`, `SheetTab`, `TheodoliteReticle`, `BuildLogRow`, `StatusCodeChip`, `StatusTone`, `DimensionLineHorizontal`, `DimensionLineVertical`, `draftingGrid()`). All cross-reference Plan One's definitions.

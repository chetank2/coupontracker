# Coupon Tracker Design Audit

Audit date: 2026-06-13

## Scope

Checked the active Compose Android app on a Pixel 10 emulator, focused on the first-run home state, search state, settings/model setup, backup flow, and entry points for adding a coupon. Screenshots were saved in this folder.

## Captured Steps

1. `01-start.png` / `06-home-returned.png` - Home, empty state, model not installed.
   - Health: Mixed. The screen explains the model requirement and has clear primary actions, but the floating "Download Model" button overlaps the "Upload Image" action.

2. `02-settings.png` - Search-active home state.
   - Health: Mixed. Search is prominent and easy to dismiss, but the same overlapping bottom action persists while search is active.

3. `05-settings.png` - Settings, scrolled to model/data/developer sections after returning from the backup file picker.
   - Health: Mostly usable. Model setup and backup actions are clear, but developer tools appear in the same top-level settings surface as user data/privacy controls.

4. `03-settings.png` / `04-state-after-back.png` - Android system file picker opened from Settings backup export.
   - Health: Usable but fragile. The app launches the platform picker correctly, but returning from it preserves a mid-settings scroll position, which may disorient users.

## Strengths

- Clear privacy promise: "Offline AI required", "100% On-Device Processing", and "Private by design" are consistent and trust-building.
- Strong empty-state copy: the home state explains that users can scan, import, or manually enter coupon details.
- High-contrast dark theme: primary actions and headings are visually obvious in the captured emulator state.
- Settings actions use recognizable icons and direct labels, especially model download/import and backup import/export.

## Visual Design Notes

### Colors

- The active Compose palette is coherent in dark mode: near-black background `#0A0B0E`, dark surfaces `#121418` / `#1C1F24`, and bright blue accent `#2979FF`.
- The blue accent is clear and accessible-looking against dark surfaces, but it is used for almost every actionable element. This makes primary, secondary, navigation, links, section labels, and icon emphasis feel similar.
- The status bar uses the same bright blue as the primary action color, which creates a strong app-shell stripe. It is recognizable, but visually louder than the rest of the calm dark UI.
- Light and dark palettes exist, but legacy XML colors use a different Material 2 palette: primary `#1976D2`, secondary `#03DAC6`, and 4dp-era styling. This can create drift if old XML screens still appear.

### Typography

- The Compose typography scale is sensible: title large 22sp, title medium 18sp, body medium 14sp, label large 16sp. It reads clearly on the captured Pixel 10 screen.
- The brand references Uber Move in XML styles, but Compose `Typography` does not set a custom `fontFamily`, so active Compose screens likely use the platform/default font unless inherited elsewhere. That weakens brand consistency.
- Headings are legible, but several cards use similar weights and sizes, so hierarchy relies heavily on spacing and blue labels rather than type contrast.
- Some letter spacing in `BrandTypography` is non-zero and one display style is negative. That is fine for Material defaults, but should be used carefully; compact buttons already look dense.

### Padding And Spacing

- The spacing token set is good: 4/8/12/16/24/32/48/64dp, with 16dp as the main content/card padding. This is a solid mobile rhythm.
- Screens mostly use 16dp page padding and 12-16dp component gaps, which gives the app a stable Material feel.
- The home empty state is vertically crowded near the bottom because fixed empty-state content, model card, and extended FAB compete in the same viewport.
- Settings card spacing is comfortable, but the page is long and dense; grouping is clear, yet top-level sections would benefit from stronger separation or collapsible advanced sections.

### Shapes And Elevation

- Compose shapes are consistent: 8dp buttons/inputs, 12dp cards, 16dp dialogs. This matches a modern utility app.
- Legacy XML styles use 4dp corners for many widgets while Compose uses 8-12dp, so mixed surfaces can feel like two generations of UI.
- Elevation is restrained, usually 2dp cards and 4-6dp floating actions. Good choice for a focused tool.

### Visual Recommendations

1. Keep blue as the product accent, but introduce a quieter secondary action treatment for links and lower-priority settings rows.
2. Wire the active Compose typography to the intended brand font, or remove the unused font references from XML to avoid a false design-system contract.
3. Reserve the extended FAB for the installed/ready state only; in the setup state, let the model card own the primary action.
4. Normalize old XML shape/color tokens with the Compose `BrandSpacing`, `BrandShapes`, and `BrandColors` values, or clearly mark XML screens as deprecated.
5. Add bottom content padding equal to FAB height plus margin anywhere a floating action can appear.

## UX Risks

1. The empty home screen has competing primary actions.
   Evidence: `01-start.png`, `06-home-returned.png`. The model card has a full-width "Download Model" button, the empty state has "Scan Coupon" and "Upload Image", and the floating action button also says "Download Model". Because the offline model is required, scan/upload appear actionable but are visually blocked by the floating button and likely not useful yet.

2. The floating action button overlaps content.
   Evidence: `01-start.png`, `02-settings.png`, `06-home-returned.png`. The extended FAB covers the "Upload Image" button. This creates a tap-risk and makes the lower CTA look disabled or broken.

3. Model setup and add-coupon setup are split across too many places.
   Evidence: home screenshots and `05-settings.png`. Users see model setup on Home, can open Settings, and also see import/download inside Settings. The path is understandable, but it asks users to reconcile "Download Model", "Open Settings", and "Import from File" before they have saved their first coupon.

4. Settings mixes user controls with developer/admin surfaces.
   Evidence: `05-settings.png`. "Analytics Dashboard" and "Extraction Learning" appear next to Backup, Privacy, and model setup. This makes the settings page feel internal-tool-like for everyday users.

5. Manual entry likely has a layout collision risk.
   Evidence: `ManualEntryScreen.kt`. The URL field is aligned to `Alignment.BottomCenter` in the same `Box` as `UnifiedCouponForm`, so it may overlay the form's lower fields or save action on small screens and with keyboard open.

## Accessibility Risks

- Overlap at the bottom can hide or partially cover tappable targets, affecting motor accessibility and screen magnification users.
- The duplicate "Download Model" actions may be confusing in screen reader order because one is in the content and another is floating over the page.
- Some status is communicated by color and symbols in Settings, such as green lock/check indicators. Text helps in several places, but the visual language should be checked with TalkBack.
- Screenshot evidence cannot confirm focus order, TalkBack labels, keyboard behavior, or dynamic type reflow.

## Recommendations

1. Remove the floating "Download Model" FAB when the model card is visible, or reserve bottom padding so it never covers empty-state actions.
2. If the model is required before scan/upload, make Home a single setup-first state: show model setup as the only primary action, then reveal scan/upload/manual entry after setup.
3. Move developer tools behind an "Advanced" section or debug flag so Settings feels user-facing by default.
4. Make Manual Entry a single scrollable form and include the optional URL field in that scroll content, above Save or in an expandable "Extract from URL" section.
5. After returning from backup export/import, reset or preserve scroll intentionally and show a small completion/cancel state so the user understands where they landed.

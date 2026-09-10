# Fold Duo Launcher v0.2

This replaces the failed overlay approach from v0.1.

## Why v0.1 failed on this phone
The device reported `Cross-window blur: not currently available`, so an overlay using `FLAG_BLUR_BEHIND` cannot blur Samsung Home on this device in its current state.

## What v0.2 does differently
- Runs as a HOME launcher so it owns the pixels it is animating.
- Uses local `RenderEffect` blur, which does not depend on cross-window blur support.
- Uses the hinge angle to blur and stretch the launcher from the **left edge** during fold/unfold.
- Attempts to create the same live launcher surface on exposed internal secondary displays with `Presentation`.
- Presentation windows request `FLAG_TURN_SCREEN_ON` and `FLAG_KEEP_SCREEN_ON` during the transition.

## First run
1. Install the APK over the old Fold Blur build.
2. Open **Fold Duo Launcher**.
3. Tap **MAKE FOLD DUO MY HOME APP** and select it as the Home app.
4. Press Home and test folding/unfolding.
5. Long-press the launcher to return to the setup screen.

## Platform boundary
The launcher blur/left-edge transform itself does not rely on Samsung's cross-window blur. Android still does not guarantee that an ordinary third-party app can force an inactive physical panel to power on. The app attempts the documented Presentation/window approach, but Samsung ultimately controls the physical display handoff.

# Island

A Dynamic-Island-style pill for Android. A black pill sits over your front camera and morphs into:

- **Notification pop-outs**: app icon or avatar, title and text. Tap to open, swipe up or long-press to dismiss.
- **Now playing**: album art on one side of the camera, animated bars on the other. Tap to expand into a player with progress bar and prev/play/next controls.
- **Charging**: green bolt and battery % when you plug in.

## Build
    ./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
    adb install -r Island.apk

## Setup on the phone
1. Open Island → **Enable** step 1 (Accessibility → Island → On). This draws the overlay above the status bar.
2. **Enable** step 2 (Notification access → Island). This powers pop-outs and music.
3. Use the sliders to line the pill up with your camera hole.

On Android 13+, sideloaded apps can't enable accessibility until you go to
Settings › Apps › Island › ⋮ › **Allow restricted settings**.

## Code map
| File | Role |
|---|---|
| `IslandService.kt` | Accessibility service. Owns the overlay window, priority state machine, gestures |
| `IslandView.kt` | The pill. Spring morph animation, compact / notification / media layouts |
| `IslandNotificationListener.kt` | Notifications and active MediaSession → `IslandBus` |
| `IslandBus.kt` | In-process event bridge and data models |
| `Widgets.kt` | Equalizer bars, progress line, album-art accent color |
| `MainActivity.kt` | Setup and settings screen (live-updates the pill) |

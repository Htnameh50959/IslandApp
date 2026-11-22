# Island for Android

A living pill around your phone's front camera. Music, calls, timers, earbuds, hotspot devices, copied text and more pop out of it, in the spirit of iPhone's Dynamic Island and OriginOS's Origin Island.

Works on Android 9 and newer. Tested on a vivo Y200 (Android 15).

## What it does

| Feature | What it does |
|---|---|
| Music playback | Album art and moving bars around the camera, with a soft glow in the album's colour. Tap to open a player with progress and controls. |
| Videos | When you leave YouTube, Netflix, MX Player, VLC… a wider pill shows the thumbnail, title and play / pause. Tap the right half to play or pause. |
| Timer & stopwatch | A compact live pill with the running time. Stays until the timer ends. |
| Navigation | Live maps / navigation status as a compact pill until you arrive. |
| Call status | Ongoing or ringing calls stay on the island until they finish; calls can be toggled independently. |
| App notifications | All apps are selected by default. Choose apps whose notifications should appear as island cards; unselected ordinary notifications stay in the system shade. |
| Copy & Go | Recognises copied links, numbers and emails and suggests **Open / Share**, **Call / Message / New contact**, **Email / New contact**, or **Search / Translate / Share**. Passwords are shown as dots. |
| Drag & Go | Drag text, a link or a photo to the top of the screen to share or open it (from apps that allow dragging between apps, mostly in split screen). |
| Smart suggestions | Buttons use the real app icons that will handle the content. |
| Earbuds / silent / DND | Shows earbuds name and battery, and silent / vibrate / ring / Do Not Disturb changes. |
| Unlock animation | When a locked phone wakes, a Face ID-style scan; when it unlocks, a green face, fingerprint or open lock showing what unlocked it, also when face unlock keeps you on the lock screen. Animation only — your phone does the real unlock. |
| Charging | Green bolt and battery level when you plug in. |
| Quick Settings tile | Turn the island on or off from the shade, without opening the app. |
| Hidden apps | Pick apps (games, banking, camera) where the island never appears. |
| Preview every pill | A button per mode in **Try it**, so you can check the look without waiting for a real event. |
| Settings import / export | Move all preferences between phones as a JSON file. |
| Video apps | Add your own video apps on top of the built-in list, from settings. |
| Shape & glow | Corner-roundness and glow-strength sliders. |
| Volume pill | Press a volume key and the level appears on the island (the key still works normally). |
| Battery detail | Tap the charging pill for current draw, temperature and time to full. |
| Timers you can set | Long-press the idle island for 5 / 10 / 25-minute timer presets. |

**Music vs video:** apps in a built-in list of video apps (`VIDEO_PACKAGES` in `IslandBus.kt`) get the video pill. Everything else gets the music pill. Android can't tell video from audio, so add an app's package name to that list if it's missing.

**Gestures:** tap expands an activity, long-press shows its controls, swipe up dismisses it and a sideways flick changes song on the open player. Tapping outside folds an open card back. When an activity finishes the island folds back into the camera.

### Fits any phone

On launch the app finds your camera:

- **Punch-hole camera**: when idle, the island is just a small circle over the hole, and everything grows out of it.
- **Notch**: the island covers the notch and grows from it.
- **No cut-out**: the island appears from the top centre of the screen and hides when idle.

If it's slightly off, nudge it with the **Move up or down** and **Move left or right** sliders.

## Install

### Option 1: download the app

1. Download the latest `Island-v*.apk` from the [Releases page](https://github.com/Htnameh50959/IslandApp/releases) on your phone.
2. Open it and allow installing from your browser or file manager when Android asks.

### Option 2: install from a computer

With USB debugging on and the phone connected:

```sh
adb install -r Island.apk
```

### Option 3: build it yourself

Needs the Android SDK and JDK 17 or newer.

```sh
git clone https://github.com/Htnameh50959/IslandApp.git
cd IslandApp
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds are minified with R8 and signed (CI attaches one APK per `v*` tag).

### Why not on the Play Store?

Island uses an AccessibilityService to draw its overlay, and it is designed for
sideloading. Google Play restricts the Accessibility API to accessibility
purposes, so a decorative overlay would be rejected. Install from GitHub
Releases (or an F-Droid-style repository) and read the setup steps above before
turning the service on. A `SYSTEM_ALERT_WINDOW` overlay is not a drop-in
alternative: it cannot draw over the status bar on modern Android, which is
exactly where the island lives.

## Set it up

Open **Island** and go through the three steps:

1. **Show the island**: tap **Enable** and turn on **Island** in Accessibility. This lets it draw above the status bar and notice when you copy something.
2. **Music, calls & timers**: tap **Enable** and allow notification access for **Island**. This powers the music player and live activities (calls, navigation, timers). Ordinary notifications are not shown in the island.
3. **Earbuds pop-ups**: tap **Allow** to grant "Nearby devices" (Bluetooth), so it can show your earbuds' name and battery.

Every feature can be switched on or off in the app. The **Notifications** switch is the master control for notification-based cards; calls, navigation, timers and other live activity notifications also have their own switches. **Copy suggestions** starts off: when you enable it, Android will show its clipboard notice each time you copy something.

## If it isn't working

**"Restricted setting" when turning on Accessibility.** Android 13 and newer block this for apps installed outside the Play Store. Go to **Settings → Apps → Island → ⋮ (top right) → Allow restricted settings**, then try step 1 again.

**The island disappeared after an update or a reboot.** Some phones switch accessibility services off when an app updates. Open Island; if step 1 says "Turn on", enable it again.

**The island stops working after a while (vivo, Xiaomi, Oppo, Samsung…).** The phone is closing it to save battery. Go to **Settings → Battery → Island** and allow background activity, with no restrictions.

**Calls, timers or navigation don't stick.** Turn on **Live activities: calls, navigation, timer & stopwatch**.

**Copying shows nothing.** Make sure **Copy suggestions** is on. Each time it reads a copy, Android shows a small "Island pasted from your clipboard" note. That's Android's privacy notice and is expected.

**Face ID animation doesn't show.** Check that **Face ID animation when the phone wakes and unlocks** is on (there is a **Preview Face ID** button in the app). It shows when the phone is protected by a lock and the screen turns on. If your phone unlocks before Android reports the screen on, no pill can appear.

**The island isn't lined up with my camera.** Use the **Move up or down** and **Move left or right** sliders, or turn off **Find my camera automatically** and size it yourself.

**I can't tap the island.** Tap it after it has finished growing. Touches are switched on once the animation settles.

## Privacy

- Copied text is only kept in memory long enough to show the card. It is never saved or sent anywhere.
- Copy suggestions are **off by default**. When you turn them on, the app briefly takes window focus to read the clipboard, which is why Android shows its "Island pasted from your clipboard" notice.
- With copy suggestions on, copied text is also handed to a build of Notify Bridge (PhoneLink) signed with the same key, through a signature-level permission. That is the only outbound data path; the app itself still has no internet permission.
- Music info and call, navigation and timer notifications are only used to draw the island.
- The app has no internet permission.

## How it works

- `IslandService` is an accessibility service. It draws the island in an overlay window across the top of the screen, which never moves or resizes so the animation stays smooth. A small invisible window sits over the island once it settles and passes taps to it.
- `IslandView` draws the pill. Its size and corners are driven by springs, so a change mid-animation keeps its momentum. The content is scaled to the pill's current size, so it grows out of the camera.
- `IslandNotificationListener` receives live activities (calls, navigation, timers) and the active media session.
- `Suggestions` recognises links, phone numbers and email addresses in copied or dropped text and builds the buttons.

| File | Purpose |
|---|---|
| `IslandService.kt` | Overlay windows, camera detection, what to show when, gestures, copy detection, status pop-ups, Face ID, foreground-app tracking, drag and drop |
| `IslandView.kt` | The island: springs, glow, every view (compact, video, Face ID, live activity, card, status, player…) |
| `IslandNotificationListener.kt` | Live activities and media sessions |
| `Suggestions.kt` | Copy and drop suggestions |
| `IslandBus.kt` | Passes events from the listener to the overlay, and the list of video apps |
| `MainActivity.kt` | Setup and settings screen |
| `Prefs.kt` | Settings |

## Related

- [Custom Island](https://github.com/Htnameh50959/CustomIsland-for-laptop): the same island for the GNOME desktop on Linux.
- [PhoneLink](https://github.com/Htnameh50959/PhoneLink): your phone's notifications on your Linux PC over Bluetooth.

## License

GPL-2.0-or-later. See [LICENSE](LICENSE).

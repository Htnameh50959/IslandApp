# Island for Android

A living pill around your phone's front camera. Notifications, music, earbuds, copied text and more pop out of it, in the spirit of iPhone's Dynamic Island and OriginOS's Origin Island.

Works on Android 9 and newer. Tested on a vivo Y200 (Android 15).

## What it does

| When… | The island… |
|---|---|
| Music is playing | shows album art on one side of the camera and moving bars on the other, with a soft glow in the album's colour. Tap to open a player with a progress bar and controls. |
| A notification arrives | grows out of the camera with the app icon, title and text. Tap to open it. A newer notification takes over with a little bounce. |
| You copy something | offers suggestions with the real app icons: **Open / Share** for a link, **Call / Message / Save** for a phone number, **Email / Save** for an email address, **Search / Translate / Share** for other text. Text that looks like a password is shown as dots. |
| Earbuds or headphones connect | shows their name and battery level. |
| You switch silent, vibrate or ring, or Do Not Disturb | shows the new mode. |
| You plug in the charger | shows a green bolt and the battery level. |
| You drag text, a link or a photo to the top of the screen | becomes a drop target and offers to share it (only from apps that allow dragging between apps, mostly in split screen). |

**Closing:** swipe the island up, flick it sideways, or tap outside it. Tapping an empty part of a card also closes it.

### Fits any phone

On launch the app finds your camera:

- **Punch-hole camera**: when idle, the island is just a small circle over the hole, and everything grows out of it.
- **Notch**: the island covers the notch and grows from it.
- **No cut-out**: the island appears from the top centre of the screen and hides when idle.

If it's slightly off, nudge it with the **Move up or down** and **Move left or right** sliders.

## Install

### Option 1: download the app

1. Download `Island.apk` from the [Releases page](https://github.com/GITHUB_USER/island-app/releases) on your phone.
2. Open it and allow installing from your browser or file manager when Android asks.

### Option 2: install from a computer

With USB debugging on and the phone connected:

```sh
adb install -r Island.apk
```

### Option 3: build it yourself

Needs the Android SDK and JDK 17 or newer.

```sh
git clone https://github.com/GITHUB_USER/island-app.git
cd island-app
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs as-is.

## Set it up

Open **Island** and go through the three steps:

1. **Show the island**: tap **Enable** and turn on **Island** in Accessibility. This lets it draw above the status bar and notice when you copy something.
2. **Notifications & music**: tap **Enable** and allow notification access for **Island**. This powers pop-outs and the music player.
3. **Earbuds pop-ups**: tap **Allow** to grant "Nearby devices" (Bluetooth), so it can show your earbuds' name and battery.

Every feature can be switched on or off in the app.

## If it isn't working

**"Restricted setting" when turning on Accessibility.** Android 13 and newer block this for apps installed outside the Play Store. Go to **Settings → Apps → Island → ⋮ (top right) → Allow restricted settings**, then try step 1 again.

**The island disappeared after an update or a reboot.** Some phones switch accessibility services off when an app updates. Open Island; if step 1 says "Turn on", enable it again.

**The island stops working after a while (vivo, Xiaomi, Oppo, Samsung…).** The phone is closing it to save battery. Go to **Settings → Battery → Island** and allow background activity, with no restrictions.

**Nothing pops out for notifications.** Check that step 2 is green, that **Pop-out notifications** is on, and that Do Not Disturb isn't hiding them.

**Copying shows nothing.** Make sure **Copy suggestions** is on. Each time it reads a copy, Android shows a small "Island pasted from your clipboard" note. That's Android's privacy notice and is expected.

**The island isn't lined up with my camera.** Use the **Move up or down** and **Move left or right** sliders, or turn off **Find my camera automatically** and size it yourself.

**I can't tap the island.** Tap it after it has finished growing. Touches are switched on once the animation settles.

## Privacy

- Copied text is only kept in memory long enough to show the card. It is never saved or sent anywhere.
- Notifications and music info are only used to draw the island.
- The app has no internet permission.

## How it works

- `IslandService` is an accessibility service. It draws the island in an overlay window across the top of the screen, which never moves or resizes so the animation stays smooth. A small invisible window sits over the island once it settles and passes taps to it.
- `IslandView` draws the pill. Its size and corners are driven by springs, so a change mid-animation keeps its momentum. The content is scaled to the pill's current size, so it grows out of the camera.
- `IslandNotificationListener` receives notifications and the active media session.
- `Suggestions` recognises links, phone numbers and email addresses in copied or dropped text and builds the buttons.

| File | Purpose |
|---|---|
| `IslandService.kt` | Overlay windows, camera detection, what to show when, gestures, copy detection, status pop-ups, drag and drop |
| `IslandView.kt` | The island: springs, glow, every view (compact, notification, card, status, player…) |
| `IslandNotificationListener.kt` | Notifications and media sessions |
| `Suggestions.kt` | Copy and drop suggestions |
| `IslandBus.kt` | Passes events from the listener to the overlay |
| `MainActivity.kt` | Setup and settings screen |
| `Prefs.kt` | Settings |

## Related

- [Custom Island](https://github.com/GITHUB_USER/custom-island): the same island for the GNOME desktop on Linux.
- [Notify Bridge](https://github.com/GITHUB_USER/notify-bridge): your phone's notifications on your Linux PC over Bluetooth.

## License

GPL-2.0-or-later. See [LICENSE](LICENSE).

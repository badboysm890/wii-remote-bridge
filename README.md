# Wii Remote Bridge

Use real Nintendo Wii Remotes in Dolphin on Android, with the IR pointer (sensor bar), motion,
Nunchuk and rumble, for up to four players. No DolphinBar and no root.

**[Download the APK](../../releases/latest)**

## How it works

Android's kernel already contains the Wii Remote driver (`hid-wiimote`, built into Google's GKI
kernels). Once paired, the driver splits the remote into separate input devices: buttons, IR
camera, accelerometer and Nunchuk. Android only adopts the one with buttons, so apps never see the
pointer or the motion. The bridge reads all of them and re-creates each remote as one Android game
controller ("Wii Remote 1" to "Wii Remote 4"; a remote keeps its player number across reconnects), with:

| Wii Remote | Android controller |
|---|---|
| A, B, 1, 2, +, −, Home | BUTTON_A, B, Y, X, START, SELECT, MODE |
| D-pad | DPAD keys |
| IR pointer (sensor bar) | AXIS_RX / AXIS_RY |
| Accelerometer (±4 g) | AXIS_Z / AXIS_RZ / AXIS_THROTTLE |
| Shake | BUTTON_THUMBL |
| Nunchuk stick, C, Z | AXIS_X / AXIS_Y, BUTTON_L1, BUTTON_R1 |
| Nunchuk accelerometer, shake | AXIS_RUDDER / AXIS_WHEEL / AXIS_GAS, BUTTON_THUMBR |
| Rumble | the controller's vibrator (Dolphin's "Motor 0") |

Reading the raw devices and creating a virtual controller needs the shell user, which apps get
through [Shizuku](https://shizuku.rikka.app/) without root.

The app also pairs the remote, which Android's Bluetooth settings cannot do: the PIN is a
Bluetooth address written backwards as raw bytes.

## Setup

1. Install and start **Shizuku** (Wireless debugging), then open Wii Remote Bridge and tap **Allow**.
2. **Pair**: press the red **SYNC** button under the battery cover. Afterwards any button reconnects.
   For more players, tap **Pair another** and sync the next remote.
3. **Start the bridge**. It restarts by itself after a reboot once Shizuku is running.
4. Open Dolphin once, then tap **Set up Dolphin**. Each connected remote is mapped to Dolphin's Wii
   Remote 1–4 completely: buttons, pointer, the real accelerometer data, shake, Nunchuk and rumble.
5. Put the sensor bar above or below the screen, then use the **Test** panel to check the pointer,
   motion, buttons and rumble.

## Requirements and limits

- Android 12 or newer, and a kernel with `hid-wiimote` (the app tells you if it is missing).
- If the remote connects but no controller appears after a long uptime, restart the device. The
  driver needs a contiguous block of memory when it starts.
- Retroid handhelds clone new controllers into their own pad layout; the bridge switches that off
  while creating its controller.
- The bridge recovers by itself: it restarts if it stops, starts whenever Shizuku starts, releases
  held buttons when a remote disconnects, and keeps Dolphin pointed at the right controllers.
- Not supported: the remote's speaker (sound plays on the device instead; sending it to the remote
  needs raw Bluetooth access, which needs root), MotionPlus, and the Classic Controller, guitar and
  Balance Board.
- After a reboot, start Shizuku once (non-rooted Android requires that); the bridge follows by itself.

## Build

`native/build.sh` builds the bridge for arm64-v8a and x86_64, then `./gradlew :app:assembleRelease`.

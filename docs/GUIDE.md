# How to use Wii Remote Bridge

This guide takes you from a fresh install to playing Wii games in Dolphin with real Wii Remotes. You
need no DolphinBar and no root. Plan on about five minutes the first time.

**You need:**
- An Android 12+ phone, handheld or TV box. The app checks that its kernel has the Wii Remote driver.
- **Dolphin** for Android (Play Store or dolphin-emu.org).
- A Wii Remote (original or Plus), and optionally a Nunchuk or Classic Controller.
- A **sensor bar** for pointing (see [Sensor bar](#sensor-bar)). Everything except pointing works
  without one.

---

## 1. Install the apps

1. Install **Wii Remote Bridge** from the [latest release](../../../releases/latest) (download the `.apk`
   and open it).
2. Install **[Shizuku](https://shizuku.rikka.app/download/)** (Play Store or GitHub).
3. Install **Dolphin**, open it once and add your games, then close it.

## 2. Start Shizuku (no root)

Shizuku gives Wii Remote Bridge the access it needs to read the remote's sensors. It works through
Android's Wireless debugging.

1. Turn on **Developer options**: Settings → About → tap **Build number** seven times.
2. Connect to **Wi-Fi** (Wireless debugging only works on Wi-Fi).
3. Open **Shizuku** → **Start via Wireless debugging** → **Pairing**. Shizuku shows a notification;
   then open Settings → Developer options → **Wireless debugging** → **Pair device with pairing code**
   and type the code into Shizuku's notification.
4. Back in Shizuku, tap **Start**. It should say **Shizuku is running**.

You pair only once. **After every restart** of your device, open Shizuku and tap **Start** again;
Android requires that without root. Wii Remote Bridge and Dolphin's setup follow by themselves.

## 3. Open Wii Remote Bridge

1. Open **Wii Remote Bridge** and tap **Allow** under *Shizuku*, then **Allow all the time**.
2. Allow Bluetooth and notifications when Android asks.

## 4. Pair the Wii Remote

1. Tap **Pair**.
2. Open the battery cover of the Wii Remote and press the small red **SYNC** button once. The blue
   lights blink.
3. The app says **Paired**, the remote buzzes, and one light stays on: it is player 1.

From now on, **any button** wakes the remote and reconnects it. For more players, tap **Pair
another** and sync the next remote; each one gets its own player number (1–4) and keeps it.

## 5. Dolphin

The app sets Dolphin up **by itself** when a remote connects: every button, the pointer, motion, the
Nunchuk or Classic Controller you plug in, and rumble, for each player. It never touches Dolphin
while a game runs; changes wait until Dolphin is closed. **Set up again** redoes it right away (and
closes Dolphin if it is open).

Start a game in Dolphin. That's it.

## 6. Check everything in the Test panel

At the bottom of the app:
- **Pointer:** green "sensor bar in view" plus a moving dot when you point at the screen.
- **Tilt bars:** move when you tilt; about +1 g on one axis when the remote lies still.
- **Buttons:** names appear as you press them (with "P2:" etc. for other players).
- **Test rumble:** every remote buzzes in turn.
- **Battery:** shown under *Pair*. Below 15% the pointer gets jumpy and the remote drops out: replace
  the batteries.

---

## Sensor bar

The Wii Remote points by looking at two infrared lights with its camera. The "sensor bar" is just
those two lights; it sends nothing. Any of these works:

| Option | Notes |
|---|---|
| **USB sensor bar** | The easiest: a few dollars online ("USB Wii sensor bar"). Plug it into any USB charger or the TV's USB port. |
| **Battery (wireless) sensor bar** | No cable. |
| **Your original Wii sensor bar** | Needs a Wii (or a Wii power adapter mod) to light up. |
| **A DolphinBar** | Works as a plain sensor bar too, if you have one. |
| **Two candles or IR LEDs about 20 cm apart** | Works in a pinch. |

You can't see the lights: infrared is invisible. Check by pointing your phone camera at it (most
front cameras show a faint purple glow), or use the app's Test panel.

**Placement:**
1. Centre it on the screen, **just above or just below** it, facing you.
2. Sit **1.5–3 m (5–10 ft) away**. Closer than about 1 m and the camera loses one of the two lights.
3. Keep **sunlight, lamps and windows** out of view behind the screen; they also emit infrared and
   confuse the camera.
4. Tell Dolphin where it is: Dolphin → Settings → Config → **Wii** → **Sensor Bar Position** (Top or
   Bottom), so the pointer matches where you aim.

The app shows **"Pointer: sensor bar in view"** in green when the remote sees both lights.

---

## Playing tips

- **Sideways games** (Mario Kart, New Super Mario Bros.): hold the remote sideways with the D-pad on
  the left. Motion is real, so tilt steering works as on a Wii.
- **Shake / swing:** the real accelerometer, so swings are as strong as you make them.
- **Nunchuk / Classic Controller:** plug it in before starting the game; the app tells Dolphin.
- The remote's **speaker** sounds play from your device's speakers (the remote speaker needs root).

## Troubleshooting

| Problem | Fix |
|---|---|
| *Shizuku is not running* | Open Shizuku and tap **Start** (needed after every restart). |
| *"No Wii Remote driver"* | This device's system lacks `hid-wiimote`; the remote can't be used here. |
| Remote connects (lights stop blinking) but no controller appears | Restart the device, then press a button on the remote. After a long uptime the driver can't get the memory it needs. |
| Pairing fails | Press **SYNC** again quickly after tapping **Pair**; put fresh batteries in. |
| Pointer doesn't move | Sensor bar powered? 1.5–3 m away? No lamp or window behind the screen? See [Sensor bar](#sensor-bar). |
| Pointer is offset up/down | Set Dolphin → Config → Wii → **Sensor Bar Position** to where the bar is. |
| Buttons work but motion feels off | Tap **Set up again** (closes Dolphin) to rewrite the mapping. |
| Remote keeps disconnecting | Replace the batteries (check the level in the app). |
| Retroid handheld: built-in controls stop working | Restart the bridge in the app; it restores Retroid's controller settings. |

# 1951 Rat Rod — phone relay panel

An Android app that switches the eight relays in your rat rod from your phone,
over a plain USB cable to an Arduino Uno. No WiFi, no cloud, no account.

```
  phone ──USB OTG cable──►  Arduino Uno  ──8 GPIO──►  relay board ──► starter,
                                                                      pump, fan,
                                                                      compressor…
```

---

## Why an app and not a web page

Android Chrome cannot open a USB serial port from a web page:

- **Web Serial** is desktop Chromium only. It does not exist on Android.
- **WebUSB** *is* on Android, but the Uno shows up as a CDC-ACM device and
  Android's `cdc_acm` kernel driver owns that interface, so a page is refused
  access to it.

The UI in `web/index.html` is still ordinary HTML/CSS/JS — it is just loaded
inside a WebView by a ~450-line native app that owns the USB connection and
hands the page a small bridge (`window.RatRodNative`). Same design, one cable,
no browser dependency. The page also still speaks HTTP, so if you ever add a
WiFi module it works there too, unchanged.

---

## Parts

| Part | Notes |
|---|---|
| Arduino Uno (or Nano) | anything with a USB-serial bridge |
| 8-channel relay module | opto-isolated preferred, the kind with a `JD-VCC` jumper |
| Android phone | must support USB host / OTG — nearly all do |
| USB OTG adapter | or a powered OTG hub (recommended, see Power) |
| 12 V → 5 V buck converter | **3 A or more** for the relay coils |
| Fuses | one per circuit, plus a main fuse on the 5 V feed |
| Heatshrink, wire, crimps | 18 AWG for coil feeds, thicker for loads |

Optional but sensible for anything switching a motor: a four-pin automotive
relay or contactor in front of the starter, compressor and fan.

---

## Wiring

### Arduino → relay board

| Relay | Uno pin | Default label in the app |
|---|---|---|
| IN1 | D2 | Starter |
| IN2 | D3 | Ignition |
| IN3 | D4 | Fuel Pump |
| IN4 | D5 | Compressor |
| IN5 | D6 | Fan |
| IN6 | D7 | Lights |
| IN7 | D8 | Acc |
| IN8 | D9 | Spare |

Leave D0/D1 alone — those are the USB serial lines.

Relay numbering, labels and behaviour are all in one place at the top of
`web/index.html` (`const CH = [...]`). Rename `Compressor` to `Horn` and the app
says Horn — or rename any button from the app's own settings panel.

### Relay board power — read this bit

A cheap 8-way board pulls about **70 mA per coil**, so roughly 0.6 A at 5 V with
everything on. The Uno's onboard regulator cannot supply that, and the phone's
USB port definitely cannot.

- Remove the `JD-VCC` jumper on the relay board.
- Feed `JD-VCC` and `GND` from the separate 12 V → 5 V 3 A buck.
- Feed the board's `VCC` from the Uno's 5 V (that is only the opto side, ~5 mA).
- Tie the Uno's `GND` to the buck's `GND`. One common ground, always.

If your board is not opto-isolated, ignore the jumper and just power the whole
board from the buck, sharing ground with the Uno.

### Loads

The relay contacts are usually rated 10 A **resistive**. Motors and compressors
draw several times their running current at startup, so:

- **Starter** — never straight off the module. Drive the existing starter
  solenoid or a 40 A+ automotive relay with it.
- **Compressor / fan** — drive an automotive relay or contactor, fuse the load
  separately.
- Add a flyback diode or RC snubber across DC motor loads so the collapsing
  field does not arc the contacts.

### Power for the board and the phone

The phone is the USB *host*: it puts 5 V on VBUS and expects the Uno to be the
device. If you also feed 5 V into the Uno from the car you get two supplies
pushing against each other.

Pick one:

1. **Powered USB OTG hub (easiest).** Phone → hub → Uno. Power the hub from the
   car's 5 V buck. One cable to the phone, relay board fed from the same buck.
2. **Data-only cable.** Use a charge-blocker or a cable with the red VBUS wire
   cut, so the phone only exchanges data and the car powers the Uno through its
   5 V pin.

Car 12 V is not clean — cranking pulls it to 6 V and load dumps can spike past
40 V. Use a buck rated for automotive input (9–36 V), and fuse it.

---

## Flashing the Arduino

Open `firmware/uno_relay_controller/uno_relay_controller.ino` in the Arduino
IDE, select **Arduino Uno**, upload.

Two settings at the top of the sketch:

```cpp
#define RELAY_ACTIVE_LOW 1   // most cheap boards: 1. If relays switch backwards, use 0.
const uint8_t LINK_HOLD_MASK = 0b00110110;  // ignition, pump, fan, lights
```

The board boots with every relay **off**, before any pin becomes an output, so
there is no power-up clack.

---

## Building the app

### Android Studio
Open the `android/` folder as a project. Let Gradle sync, then **Run** on a
connected phone (USB debugging on), or **Build → Build APK(s)**.

### Command line
```bash
cd android
gradle wrapper          # once, if ./gradlew is missing
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). Point
`local.properties` at your SDK, or set `ANDROID_HOME`.

The build copies `web/index.html` into the APK's assets every time, so there is
only ever one copy of the UI to edit.

### Install on the phone
Copy the APK over and tap it, or `adb install`. Enabling "install unknown apps"
for your file manager is usually needed.

---

## Using it

1. Plug the phone into the Uno. Android asks permission to access the USB
   device — tick "use by default" and allow.
2. The app opens. The pill in the top right reads **USB LINK** when the board is
   answering.
3. Tap a relay to latch it on or off. Hold the **Starter** card for about
   0.7 s — it cranks while you hold, and cuts itself at 4 s no matter what.
4. **ALL OFF** drops everything immediately.
5. The **gear** button renames any of the eight buttons. Names are stored on the
   phone (native `SharedPreferences`, mirrored into `localStorage`) and survive
   an app restart; **RESET** puts the defaults back.

The screen is kept awake while the app is in front, and the app runs full
screen — it is meant to sit on the dash.

---

## How it fails

Things that go wrong in a car, and what happens:

| Situation | Behaviour |
|---|---|
| Phone unplugged mid-drive | Uno cuts everything except ignition, fuel pump, fan, lights after 3 s |
| App killed / phone dies | same as above |
| Cable is fine, Uno crashed | last relay state holds — that is why the **ALL OFF** button and a physical kill switch matter |
| Plug in the cable while running | **the Uno resets** (DTR), relays drop for ~2 s |
| Phone locks screen | app keeps running while foregrounded; if Android kills it, the 3 s fail-safe applies |

The reset-on-connect is worth designing around: don't plug in the phone with the
engine running unless the ignition is on a physical switch.

---

## Serial protocol

115200 baud, newline terminated ASCII. Easy to test with the Arduino IDE's
serial monitor.

| Direction | Frame | Meaning |
|---|---|---|
| → board | `S` | report state |
| → board | `K` | heartbeat, required every 3 s |
| → board | `R3:1` | relay 3 on |
| → board | `X` | all off |
| ← board | `S:01010000` | state, channel 1 first |
| ← board | `A:3:1` | acknowledged |
| ← board | `W:link-lost` | fail-safe fired |
| ← board | `E:CMD` | unknown command |

---

## Safety

This is a 75-year-old car with a phone in the control loop. Treat the app as a
convenience panel, not as a safety system:

- Keep a **physical battery kill switch** within reach, and a physical ignition
  switch. Nothing that keeps the engine running or the car visible at night
  should depend on a USB cable.
- Fuse every circuit at the battery end, sized to the wire.
- Never let the starter solenoid be energised by a 10 A module contact alone.
- Test with the relay board unplugged from the loads first — listen to the
  relays clack, watch the app's LEDs, then connect one load at a time.
- Do not work on this with the battery connected.

---

## Layout

```
web/index.html                              the UI (single file, no build step)
firmware/uno_relay_controller/*.ino          relay driver + fail-safe
android/app/src/main/java/com/ratrod/panel/  MainActivity, RelayLink, Bridge
android/app/build.gradle                     pulls in usb-serial-for-android
```

Third-party: [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) (MIT), via JitPack.

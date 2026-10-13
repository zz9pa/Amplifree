# Amplifree

An unofficial, open-source remote control for **Line 6 AMPLIFi** guitar amplifiers,
for modern Android phones.

> **Not affiliated with, endorsed by, or sponsored by Line 6 or Yamaha Guitar Group.**
> "AMPLIFi" and "Line 6" are trademarks of their respective owners and are used here
> only to identify the hardware this software works with.

## Why this exists

Line 6 has [ended support](https://line6.com/support/page/kb/legacy-products/amps/firehawk-1500/amplifi-and-firehawk-android-compatibility-r1064)
for its AMPLIFi Remote app, and the original app contains only 32-bit native code, so it
does not run on current 64-bit-only Android devices. The amp itself still works perfectly
well. Amplifree aims to keep it usable.

## Status

**Early, experimental work in progress.** It has been developed against a single AMPLIFi 150
and a small number of phones. Expect rough edges, and treat anything that sends commands to
your amp as use-at-your-own-risk.

Goals:

- browse and load the presets stored on the amp, with search
- see and edit the tone that is currently live (amp, effects, on/off switches)
- use the amp's own tuner

Not goals: no cloud features, and nothing that talks to Line 6's servers.

## How it works

Amplifree talks to your amp over Bluetooth (the standard Serial Port Profile). The wire
protocol was worked out by observing the amp's own Bluetooth traffic and by studying the
original app for the purpose of interoperability. Data that is specific to the amp
(its symbol table and its presets) is **read from your own amp at runtime**.

## What this repository does *not* contain

No Line 6 software, firmware, binaries, decompiled or disassembled code, artwork, manuals,
preset content, or recorded amp traffic. The single exception is a list of model display
names (amp, cab and effect names) copied, names only, from Line 6's public knowledge-base page; see
`NOTICE.md` for the source. The repository is checked for this before every publish. If you spot anything that should not be here, please open an issue and it will be
removed straight away.

## Building

- JDK 17 or newer, Android SDK 36
- Gradle 9.3.1 (required by Android Gradle Plugin 9.1.1), or a recent Android Studio

```
cd android
gradle :app:assembleDebug
```

Before using it, pair the amp in Android's Bluetooth settings and make sure it shows as
**connected** (it is an audio device) - Android refuses to open the control channel to a
device that is paired but not connected.

## Layout

| Path | What |
|---|---|
| `android/` | The Android app (Kotlin, Jetpack Compose) |
| `tools/` | Small Python helpers for studying the protocol |

## Licence

Apache License 2.0 - see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

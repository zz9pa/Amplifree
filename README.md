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

**Early, experimental.** It has been developed and tested against a single AMPLIFi 150 and a
Pixel phone. Expect rough edges, and treat anything that sends commands to your amp as
use-at-your-own-risk.

**What works today**

- connects to your paired amp by itself, and recovers when the link drops (app restarted,
  Bluetooth switched off and on, the amp closing a stream)
- browse the tones stored on the amp, with search, and load one
- see and edit the tone that is live: the amp, cabinet, effect and wah models (with search), every
  knob, switch and choice, the signal-path switches and FX mix levels, and the master volume
- knob moves on the amp itself show up in the app
- the amp's own tuner (no microphone)
- save the live tone to a slot on the amp (the slot is backed up first, the write is read back and checked, and the
  last save can be undone), and rename a stored tone
- share tones: export the live tone to a small JSON file and import one someone else made
  (see [docs/TONE_FORMAT.md](docs/TONE_FORMAT.md)); importing changes only the live tone, never the
  amp's stored tones, and asks first if you have unsaved changes
- import Spider V tone files (`.svl6p`), whose models and settings largely match the AMPLIFi's

**Not there yet**

- tone files for the AMPLIFi itself from Line 6's CustomTone site (`.l6t`) and for POD HD, POD Go and Helix cannot
  be imported: they carry numeric ids or models this amp does not have
- other AMPLIFi models and other phones are untested

Not goals: no cloud features, and nothing that talks to Line 6's servers.

## Download

Grab the latest APK from the [Releases page](https://github.com/zz9pa/Amplifree/releases)
(`Amplifree-<version>.apk`, with a `SHA256SUMS` file to check it against). Releases are marked
pre-release while the software is this young.

1. Pair your amp in Android's Bluetooth settings and make sure it shows **Connected**.
2. Open the downloaded APK and allow your browser or file manager to install apps when asked.
3. Open Amplifree, allow the "Nearby devices" permission, and it connects to your amp by itself.

It needs Android 7.0 or newer and asks for Bluetooth only: no internet, no microphone, no location.
Updates install over the top of earlier versions.

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
| `docs/` | The tone file format, and an independent review of the Bluetooth code |

## Licence

Apache License 2.0 - see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

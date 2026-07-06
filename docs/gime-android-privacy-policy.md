# Privacy Policy — GIME for Android

*Last updated: July 7, 2026*

## Overview

GIME for Android ("the App") is a multilingual text input tool for Android
that uses a game controller. The App functions as a system Input Method
(IME). This privacy policy explains how the App handles user data.

## Data Collection

**The App does not collect, store, or transmit any personal data to the
developer or any third party.**

Specifically:

- **No keystroke logging to external servers** — Game controller inputs are
  processed for real-time text input only. They are not recorded for any
  remote transmission, analytics, or telemetry.
- **No analytics or tracking SDKs** — The App contains no analytics SDKs,
  tracking pixels, or telemetry of any kind.
- **No user accounts** — The App does not require sign-in.

## Network Communication

**The App makes no network communication of any kind.** It opens no sockets
and contains no networking code. (A prior VRChat OSC integration and an
on-device translation feature were removed in July 2026; the App is now a
purely local IME.)

## On-Device Storage

The App stores the following data locally on your device only:

- **User dictionary entries** — Custom word entries you add to the user
  dictionary, stored in a Room database.
- **Learning history** — Reading→surface pairs accumulated as you confirm
  conversions, stored in the same Room database for prediction quality
  improvement. Can be reset via the dictionary screen.
- **App settings** — Language mode preferences (which input modes are
  enabled and their cycle order) and UI settings, stored via
  SharedPreferences.

This data never leaves your device and is automatically deleted when you
uninstall the App.

## Permissions

The App declares **no Android runtime permissions**. It makes no network
communication and requires no storage, camera, microphone, or overlay access.
(IME binding is granted by the system via `BIND_INPUT_METHOD` when the user
enables GIME as a keyboard.)

## Third-Party Services

The App does not integrate with any third-party services for data collection
or transmission. It includes the following local-only third-party
components:

- KazumaProject/JapaneseKeyboard (MIT) — Vendored on-device Japanese
  kana-to-kanji conversion engine and dictionary (LOUDS trie + N-gram
  language model)
- CC-CEDICT (CC BY-SA 4.0) — Source data for the Simplified Chinese
  vocabulary and pinyin information used by the abbreviated-pinyin lookup
- libchewing (LGPL v2.1) — Source data for the Traditional Chinese
  vocabulary and zhuyin information used by the abbreviated-zhuyin lookup
- AndroidX libraries (Apache 2.0) — Standard Android UI / database /
  lifecycle support, including Jetpack Compose and Room
- kotlinx-serialization-json (Apache 2.0) — Local JSON parsing for
  dictionary files
- Timber (Apache 2.0) — Local logging facade used by the vendored
  KazumaProject converter

## Children's Privacy

The App does not knowingly collect personal information from children
under 13.

## Changes to This Policy

We may update this privacy policy from time to time. The "Last updated"
date at the top will indicate the most recent revision.

## Contact

For questions about this policy, please open an issue at the App's
GitHub repository.

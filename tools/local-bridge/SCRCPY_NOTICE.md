# scrcpy dependency and protocol

QaLens's embedded desktop mirror uses the official
[scrcpy 5.0 server](https://github.com/Genymobile/scrcpy/releases/tag/v5.0), licensed under
Apache 2.0. The full upstream license is included as [SCRCPY_LICENSE](SCRCPY_LICENSE) and copied
beside the cached server during setup. QaLens does not vendor or modify the server binary/source.
Only an explicitly started mirror downloads/executes it on the approved adb device. The standalone
installer can prepare the same cache before starting the GUI.

The adapter follows upstream's version-specific
[Streamer](https://github.com/Genymobile/scrcpy/blob/v5.0/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java)
and [ControlMessageReader](https://github.com/Genymobile/scrcpy/blob/v5.0/server/src/main/java/com/genymobile/scrcpy/control/ControlMessageReader.java).
This is an internal protocol, not a stable scrcpy SDK contract. `scrcpy_mirror.py` pins the release
URL and SHA-256; `scrcpy-stream.js` implements its metadata/config/keyframe framing and browser
WebCodecs consumer. Upgrades must verify both sides together, continuous touch cancellation,
rotation, session cleanup, decoding limits and concurrent QaLens recording.

The PC adapter disables audio/automatic clipboard synchronization and keeps video in memory.
It captures the whole display, unmasked. The separate QaLens screenshot and `.sal` recorder retain
their existing privacy/consent rules. Native `Open desktop window` uses an independently installed
client (or an explicitly populated cache `native/` bundle) with that client's defaults; the GUI
installer only downloads the server. Keep upstream licenses with any distributed native bundle.

The official 5.0 macOS aarch64 bundle was checksum-verified and run against the disposable API 36
emulator. Encoded embedded HTTP video and native headless MP4 are decoded in FFmpeg. Browser rendering,
native window/hardware-decoder throughput and other operating systems are not certified by those
checks. The old PNG/adb path remains in the repository behind `mirror_flags.ENABLE_LEGACY_MIRROR`.

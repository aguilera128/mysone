# Mysone

Mysone is an Android app that listens in the background and transcribes **on the phone**. It groups what it hears into
dated conversations. If you turn on AI, it writes summaries, tasks, reminders and facts, and answers questions about
what you talked about, citing the conversation each answer comes from. It is inspired by Bee, but needs no wearable.

- **Private by default.** Speech recognition runs on the device (sherpa-onnx), and no audio is stored. AI is off until
  you choose a provider, enter your own API key and accept a notice about what gets sent.
- **Works without AI.** Transcription, conversations and full-text search all work offline.
- **Bring your own AI.** Anthropic (default), Mistral, OpenRouter (any model, from its live catalog), DeepSeek or any
  OpenAI-compatible API. Your key is stored encrypted with the Android Keystore.
- **Call recordings.** Android does not let apps record calls. If your phone's own recorder saves them (for example
  Samsung's `Recordings/Call`), Mysone can transcribe each new recording and then delete it.
- **Who's speaking.** In imported audio and calls, lines are labelled “Speaker 1”, “Speaker 2” or “You”, using an
  optional 26 MB voice model.
- **Languages.** The interface is in English and Spanish. The AI writes in your phone's language. The light Canary
  speech model handles English, Spanish, German and French; Parakeet v3 handles 25 European languages.

> Recording people may be regulated where you live, and may require their consent. You are responsible for how you
> use this app.

## Requirements

- Android 12 (API 31) or later, arm64 or x86_64. Plan for about 1 GB of free RAM while transcribing.
- On first run the app downloads a speech model: Canary 180M (207 MB) or Parakeet TDT 0.6B v3 (670 MB). Both are
  verified by SHA-256.

## Install

Download `mysone-X.Y.Z.apk` from [Releases](https://github.com/aguilera128/mysone/releases) and open it on the phone
(Android will ask you to allow installing apps from that source). Updates install over it and keep your data.

The APK is signed with this certificate (SHA-256), which you can check with `apksigner verify --print-certs`:

```
52:ED:F4:25:91:71:B1:BD:09:C2:81:5B:DC:27:72:B6:74:09:83:93:FF:7F:90:5E:98:4D:6A:0A:B9:9C:BE:F5
```

## Build

You need JDK 21 (the one bundled with Android Studio works) and the Android SDK.

```bash
./gradlew assembleRelease testDebugUnitTest lint
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without a release key the release build is signed with your local **debug** key, which is fine for installing it
yourself. To sign with your own key, set `mysone.release.storeFile`, `mysone.release.storePassword`,
`mysone.release.keyAlias` and `mysone.release.keyPassword` in `~/.gradle/gradle.properties` (never in the repository).
APKs built this way cannot be installed over the official ones from Releases: uninstall first.

Crash reporting with Sentry is off unless you build with a DSN, either as the `SENTRY_DSN` environment variable or the
`mysone.sentryDsn` Gradle property. It never sends transcripts, prompts, answers or keys.

### Variants and tests

| Variant | Package | Use |
|---|---|---|
| `release` | `dev.javieraguilera.mysone` | Daily use |
| `debug` | `.debug` | Development. Supports a fake microphone (`files/debug/mic.pcm`) |
| `qa` | `.qa` | Minified release that may use `http://10.0.2.2` to talk to the fake AI |

```bash
./gradlew connectedDebugAndroidTest   # instrumented tests (Room, FTS5, migrations): emulator only
node tools/mock-llm.mjs               # fake Anthropic/OpenAI API on port 8787 (URL http://10.0.2.2:8787, key test-key)
```

`connectedDebugAndroidTest` installs on **every** device listed by `adb devices`, so set `ANDROID_SERIAL` to your
emulator if a personal phone is plugged in.

## Documentation

The design documents are in Spanish:

- [`docs/arquitectura.md`](docs/arquitectura.md): decisions, data model and the contracts between modules.
- [`docs/investigacion/`](docs/investigacion/): the research behind it, covering background limits on Android,
  on-device speech recognition, LLM costs, privacy law and speaker identification.
- [`AGENTS.md`](AGENTS.md): working notes for contributors, including coding agents.

## Contributing

Issues and pull requests are welcome.

- Run `./gradlew testDebugUnitTest lint` before opening a PR.
- Add every new user-facing text in both `res/values/` (English) and `res/values-es/` (Spanish).
- If you change a prompt in `ai/Prompts.kt`, bump its version label.
- Commit messages follow Conventional Commits.

To report a security problem, use GitHub's private vulnerability reporting on this repository rather than a
public issue.

## Known limitations

- Android only. The app cannot record calls itself, and it cannot hear anything while another app is using the
  microphone.
- After a reboot or an update, Android does not allow background microphone access until you tap “Resume” in the notification.
- Very short single-word replies may be missed.
- Tested mostly on emulators (Android 14 and 16) with a fake AI server. Not yet tested over long periods on real
  phones, with real AI providers, or on manufacturers that aggressively kill background apps.

## License

Mysone is free software under the [GNU General Public License v3.0](LICENSE).

Third-party components:

| Component | License |
|---|---|
| sherpa-onnx | Apache-2.0 |
| Silero VAD | MIT |
| NVIDIA Canary 180M flash and Parakeet TDT 0.6B v3 | CC BY 4.0 |
| 3D-Speaker ERes2Net | Apache-2.0 |

The app lists them in Settings › About.

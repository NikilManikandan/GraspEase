# Grasp Ease Android App – Architecture Overview

This document explains how the app works end-to-end and highlights the responsibilities of the key Kotlin files. It also captures the speech pipeline (Cloud Run token endpoint + gRPC Speech v2) and other subsystems (UI, BLE, native code).

## High-Level Flow
1. **UI & Navigation**: A single-activity Jetpack Compose app (`MainActivity.kt`). Home screen shows connection status, control/exercise buttons, and the Voice Tester card. Control and Exercise screens are simple command pads; Control also runs cloud speech streaming to listen for “grasp/hold/release”.
2. **BLE Control**: `BleController` scans for and connects to the orthosis, discovers the TX characteristic, and writes command bytes (0x01 grasp, 0x02 hold, 0x03 release, 0x04 start rehab). `BleViewModel` exposes state to Compose.
3. **Speech-to-Text (Streaming)**:
   - **Auth**: The app fetches a short-lived bearer token from a Cloud Run token endpoint (`CLOUD_STT_TOKEN_ENDPOINT` in `local.properties`) or uses a provided bearer (`CLOUD_STT_BEARER`). API keys are not used for streaming.
   - **Transport**: gRPC Speech v2 streaming (`StreamingRecognize`) with LINEAR16, 16 kHz mono audio, explicit decoding config, and interim results enabled. Audio is chunked at ~400 ms.
   - **Recognizer**: `projects/fabled-opus-483704-t6/locations/asia-southeast1/recognizers/default`, model `chirp`, language `en-US`.
   - **Voice Tester**: Streams mic audio and displays interim/final transcripts plus diagnostics.
   - **Control Mode**: Streams mic audio, parses transcripts for “grasp/hold/release”, and sends the corresponding BLE command.
4. **Native Code**: TFLite/whisper JNI remains in the tree but the active STT path is the cloud gRPC pipeline.
5. **BuildConfig**: `CLOUD_STT_TOKEN_ENDPOINT`, `CLOUD_STT_BEARER`, and `CLOUD_STT_API_KEY` are populated from `local.properties`/Gradle props and exposed via `BuildConfig`.

## Configuration & Secrets
- `local.properties` (not committed) should include:
  ```
  CLOUD_STT_TOKEN_ENDPOINT=https://<your-cloud-run-service>.run.app/token
  CLOUD_STT_BEARER=
  CLOUD_STT_API_KEY=
  ```
  Rebuild after edits: `./gradlew.bat clean assembleDebug`.
- The Cloud Run token service must run with a Speech-enabled service account (e.g., roles/speech.user or speech.admin). The URL must be exact (`https://.../token`) and reachable by the device (no TLS/hostname errors).

## File-by-File (Kotlin)

### `app/src/main/kotlin/com/graspease/dev/MainActivity.kt`
- Hosts the entire Compose UI and navigation (Home → Control → Exercise).
- Wires ViewModels: `BleViewModel` (BLE) and `VoiceTesterViewModel` (voice tester).
- **Voice Tester Card**: Displays status, transcript, and diagnostics; start/stop actions call into `VoiceTesterViewModel`.
- **Control Screen**: Instantiates `CloudSttController` with the bearer/token endpoint; starts streaming on connect; maps “grasp/hold/release” transcripts to BLE commands and shows live status/error overlay.
- **Exercise Screen**: Static command tiles for rehab/stop.
- **Permission Handling**: Audio and BLE permissions via activity result APIs; auto-requests mic on first launch.
- **Theming/Layout**: Custom dark color scheme, gradient background, cards for connection and modes.

### `app/src/main/kotlin/com/graspease/dev/VoiceTesterViewModel.kt`
- Holds `VoiceTesterState` (status, transcript, diagnostics, latency, error).
- Creates `CloudSttController` and `CloudSttConfig` using `BuildConfig.CLOUD_STT_BEARER` / `CLOUD_STT_TOKEN_ENDPOINT`.
- `runVoiceTest()`: Starts gRPC streaming via `startTranscriptStream`, updates transcript and diagnostics.
- `cancelVoiceTest()`: Stops the stream and resets state.

### `app/src/main/kotlin/com/graspease/dev/voice/CloudSttController.kt`
- Core speech client.
- Auth: Fetches bearer from token endpoint or uses provided bearer; caches it. Builds gRPC stubs with a bearer interceptor.
- Streaming:
  - `startTranscriptStream`: For the tester; `streamingRecognize` with interim results; 400 ms PCM chunks from `AudioRecord`.
  - `start`/`listenAndStream`: For control; same streaming config; parses transcripts for keywords and invokes command callback.
- Unary:
  - `transcribePcm16`: Blocking `recognize` via gRPC for non-streaming use.
- Audio: LINEAR16, 16 kHz mono, explicit decoding config; chunk size ~400 ms.
- Recognizer path is fixed to `asia-southeast1` and recognizer `default`; language `en-US`, model `chirp`.

### `app/src/main/kotlin/com/graspease/dev/voice/Recorder.kt`, `ContinuousVoiceController.kt`, etc.
- Legacy/local recording helpers (not used in the current cloud streaming path).

### `app/src/main/kotlin/com/graspease/dev/tflite/*` and `app/src/main/jni/*`
- JNI/TFLite/Whisper native integration. Present but not used in the active STT flow (cloud gRPC is used instead).

### BLE Stack
- `BleController` (in `MainActivity.kt`): Scans, connects, discovers the TX characteristic, and writes command bytes. Handles permissions and connection status messaging.
- `BleViewModel`: Exposes scan results, connection status, and write APIs to Compose.

## How to Build & Run
1) Set `CLOUD_STT_TOKEN_ENDPOINT` (and optionally `CLOUD_STT_BEARER`) in `local.properties`.
2) `./gradlew.bat clean assembleDebug`
3) Install `app/build/outputs/apk/debug/app-debug.apk` on device/emulator.
4) Voice Tester: tap Start, speak; transcripts and diagnostics should appear live.
5) Control Mode: connect to orthosis, then speak “grasp/hold/release”; status overlay shows interim/final, and BLE command is sent on recognized keywords.

## Common Issues & Remedies
- **403 PERMISSION_DENIED**: Token lacks `speech.recognizers.recognize` or wrong project/region. Fix the service account role and ensure recognizer path matches.
- **Hostname/TLS errors**: Token endpoint URL malformed or missing `https://`; ensure exact Cloud Run URL with `/token`.
- **Missing bearer**: Set `CLOUD_STT_TOKEN_ENDPOINT` or `CLOUD_STT_BEARER` in `local.properties` and rebuild.
- **No transcripts**: Check Diagnostics panel; if blank, verify recognizer, language, and audio format. Interim results are enabled; you should see partials quickly.

## Cloud Run Token Service (recap)
- Minimal Node/Express service using `google-auth-library` to get an access token via ADC.
- Deployed to Cloud Run with a Speech-enabled service account.
- URL is stable across restarts; scales to zero when idle.


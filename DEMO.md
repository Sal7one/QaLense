# QaLens demo walkthrough

Use synthetic evidence to try replay, local uploads, the Android tester flow and the desktop.
For the complete SDK/web/Python setup and team advice, start with [ONBOARDING.md](ONBOARDING.md).
This walkthrough exercises selected paths; it does not prove complete feature or host-app coverage.

## Browser and backend demo

From the repository root, with Python 3.9+ and a modern browser:

```sh
./demo.sh quick
```

Open the modern player at `http://127.0.0.1:8100/web/index-v2.html` and the mock dashboard at
`http://127.0.0.1:8000`. The helper opens the bundled sample at a failing-transfer moment.
Scrub the recording, read its logs/network/insights, and compare the evidence with its score.
The sample deliberately contains failures.

Set viewer **Settings → Backend URL** to `http://127.0.0.1:8000`, then choose **Send to backend**.
Check the dashboard for the received recording and deterministic mock verdict. The mock does not
call an AI model and has no authentication, tenant isolation or production retention policy.

```sh
./demo.sh curl # exercise ping, archive, summary and chunk upload paths
./demo.sh kill # stop the backend/web processes created by this helper
```

Detailed tools: [web](web/README.md), [backend](backend/README.md).

## Android sample demo

Use the [tested prerequisites](CONTRIBUTING.md), configure local `JAVA_HOME`/`ANDROID_HOME`, and
connect one disposable authorized phone/emulator with adb. The helper uses adb's selected device;
if several devices are connected, select the intended one with `ANDROID_SERIAL` first.

```sh
./demo.sh android
```

The helper builds/installs the debug sample, connects it to the mock and prints the sample flow.
With default demo ports:

1. In the banking sample, open Account 2 and Transfer. Enter 1,500 and confirm to trigger the
   synthetic failed network call. No real banking or customer data is used.
2. Tap the QA bubble, choose **Record a session**, reproduce the failure, and use **★ Clip → Last 10s**
   while capture continues. Stop normally and wait for the master and clip to save.
3. Open **More tools → Developer diagnostics** to inspect Network, Logs and Repro. A likely owner is
   an investigative lead. Missing/partial evidence cannot certify app health.
4. In **QaLens Control → Webhook**, set `http://127.0.0.1:8000/webhook` and test the endpoint.
   Choose **Send latest session** or a saved recording's **Webhook** action and check the dashboard.
5. Replay the master/clip on Android or copy it into the desktop/web viewer.

Frame recording is the default. HD needs host `allowUnmaskedVideo=true` and the Android consent
dialog; it has no per-node pixel masks. The sample Settings includes explicit crash/ANR demo actions.
Use those only when intentionally testing a disposable sample; they interrupt the app.

## Desktop inspection and recording demo

```sh
python3 tools/local-bridge/server.py --gui \
  --pipeline-config tools/local-bridge/examples/pipelines.json
```

Open `http://127.0.0.1:8765`. Replay can work unpaired. To inspect the sample:

1. Sample **More → Settings → PC inspector → Start bridge** displays a private pairing token.
2. Desktop **Devices & apps**: scan, select the device, use package `com.qalens.sample`, enter the
   current token and connect. Save the profile; tokens are not persisted.
3. Enable **Receive phone selections**, select a phone component and **Send to PC** beside
   **Copy test tag**. Review its attributes/tree position before **Save JSON**.
4. Run the included **component-summary** pipeline from Automation and inspect its output.
5. In Recordings, check phone files, **Copy to PC**, and **Open replay**. Optionally enable automatic
   copy to collect files completed after enabling, including clips after the master stops.

The workbench preserves app data. Optional scrcpy requires a local installation; file tools operate
through Downloads with a 32 MiB limit. [Desktop guide](tools/local-bridge/README.md) owns pairing,
API, storage and processor details. It is Compose control within a QA build, not universal Appium.

## Verify and evaluate

```sh
./demo.sh test
node web/tools/sal_report.js web/sample.sal # expected exit 1 because the sample has failures
```

The helper runs a convenience subset of Kotlin/web/Python/release-parity checks. It does not run
the complete lint/APK/independent-consumer/device matrix. Use [CONTRIBUTING.md](CONTRIBUTING.md)
for those commands and [HANDOVER.md](HANDOVER.md) for the dated local baseline and open limits.
CLI exit 2 means invalid input or reported partial evidence without an observed failure; exit 0
reflects retained evidence and current rules, not a certification of complete capture.

Before evaluating company apps, wire one real request/write, record a short session, inspect its
coverage, and verify all production variants use the no-op SDK. Review any artifact before sharing.
Use an owned backend for company deployments; the included mock remains a local development tool.

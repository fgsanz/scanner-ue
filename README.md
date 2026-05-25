# Scanner UE Android App

Android app for rugged scanner phones. It captures scanned box barcodes, shows the barcode and decoded product details on screen, and sends each scan to the MatNord demo API over the public internet.

## Features

- Captures barcodes from scanner wedge key events (keyboard-style scanner output) without a visible text input field.
- Also listens for scanner broadcast intents used by common rugged devices.
- Shows on-screen:
  - Last scanned barcode
  - Decoded product information
  - API send status
  - Last API response JSON
- Sends each scan to:
  - `POST https://eod-demo-f7drswf6vq-ma.a.run.app/scan/{productId}`

## Product IDs expected by API

- `PROD-BAN-0912`
- `PROD-MLK-4421`
- `PROD-SRD-3381`
- `PROD-EVO-7711`
- `PROD-CHP-5529`

## Project structure

- `app/src/main/java/com/scannerue/app/MainActivity.kt`: scanner input handling + API integration
- `app/src/main/res/layout/activity_main.xml`: screen UI
- `app/src/main/AndroidManifest.xml`: internet permission + launcher activity

## Build and run

1. Open this folder in Android Studio.
2. Let Android Studio sync Gradle dependencies.
3. Connect your scanner phone by USB (ADB enabled).
4. Click Run and select the connected device.

### Command-line build (optional)

This repo currently does not include Gradle wrapper files (`gradlew`).
If you want command-line builds from this repo:

1. Generate wrapper once from Android Studio (Gradle tool window) or from a machine with Gradle installed.
2. Then run:
   - `./gradlew assembleDebug`
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Scanner setup notes for rugged devices

- Preferred: keyboard wedge mode that sends barcode text + Enter.
- The app captures wedge key events at activity level and uses Enter/Tab as scan terminators.
- If your scanner uses intent broadcast mode instead, configure it to send barcode text in one of these extras:
  - `com.symbol.datawedge.data_string`
  - `data`
  - `barcode`
  - `barcode_string`
  - `scanData`
  - `SCAN_BARCODE1`

## API behavior

- `200 OK`: returns product object with updated `quantity`.
- `404 Not Found`: unknown product ID.
- No authentication required by the provided demo API.

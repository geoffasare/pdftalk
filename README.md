# PDFTalk

An Android app that converts PDF documents to speech using Android's TextToSpeech engine.

## Features

- Pick any PDF using the system file picker (no storage permissions required)
- Extract text from PDF documents using pdfbox-android
- Read aloud using Android TextToSpeech with selectable voices
- Simple, clean interface

## Download

Get the latest APK from [Releases](https://github.com/geoffasare/pdftalk/releases).

## Building from Source

### Prerequisites

- Android Studio (or just Gradle)
- JDK 17+

### Debug Build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### Release Build (Local)

1. Copy `keystore.properties.example` to `keystore.properties`
2. Fill in your keystore details
3. Run:

```bash
./gradlew assembleRelease
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

For release builds during development, you can create your own debug keystore or build debug variants.

## Notes

- The app uses the Storage Access Framework (`ACTION_OPEN_DOCUMENT`) so no storage permissions are required
- Voice availability depends on the device's TTS engine and installed voices
- If PDF text extraction fails for some PDFs (complex layouts), try a different PDF

## License

[Add your license here]

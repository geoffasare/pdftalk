PDF to Speech (Android)

This small Android app lets you pick a PDF, extracts its text with pdfbox-android, and reads it using Android TextToSpeech with selectable voices.

How to run

1. Open the project in Android Studio (open the folder `pdf-to-speech`).
2. Allow Android Studio to sync Gradle and download dependencies.
3. Build and run on a device or emulator (TextToSpeech voices appear when TTS is initialized).

Notes

- The app uses the Storage Access Framework (`ACTION_OPEN_DOCUMENT`) so no storage permissions are required.
- Voice availability depends on the device's TTS engine and installed voices.
- If PDF text extraction fails for some PDFs (complex layouts), try a different PDF.

Files added

- `app/src/main/java/com/example/pdftospeech/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/build.gradle`, `build.gradle`, `settings.gradle`


# Glow Charge Clock

A landscape Android charging clock with a colourful animated gradient, live time/date/battery level, and gyroscope-based parallax.

## Build
Open with Android Studio (JDK 17), or use the GitHub Actions workflow. Every push to `main` triggers a debug APK build. Download it from the workflow run's **Artifacts** section.

## Notes
- Designed for Android 6.0+; target SDK 28 for legacy compatibility.
- Android 10/MIUI may block automatically launching an Activity from a background charging broadcast. If this happens, enable Autostart and set battery restrictions to No restrictions, or open the app manually after connecting power.
- Gyroscope parallax is used when the device has a gyroscope; the clock still works without it.

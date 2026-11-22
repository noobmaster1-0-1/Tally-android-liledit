# Build Tally for Android on Windows

1. Install Android Studio and Android SDK Platform 35 / Build Tools 35.0.0.
2. Use JDK 17 or the Android Studio bundled JDK.
3. Open the folder containing `settings.gradle` in Android Studio and let Gradle sync.
4. Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.
For a cloud build, open **Actions → Build APK** and download the **Tally-apk** artifact from the successful run.

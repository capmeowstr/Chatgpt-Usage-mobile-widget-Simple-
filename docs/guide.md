# Work Usage Widget guide

## Quick start

Download the [v1.2 APK](../downloads/ChatGPT_Work_Usage_v1_2.apk?raw=true), open it on Android, and follow the install prompt. Open **Work Usage** after installation.

### Enter values manually

1. Open the [ChatGPT usage dashboard](https://chatgpt.com/settings/usage) and find the limits you want to display.
2. In **Work Usage**, tap **Enter dashboard values**. Enter the percentages left and earned reset count. Leave any unavailable value blank.
3. Tap **Save**, then **Add to home screen**. Confirm in your launcher. You can also add the widget from the Android widget picker.
4. Update your numbers by tapping **Enter dashboard values** again. The widget shows the time of the last manual entry.

### Connect live sync

Live sync uses the [Codex device-code sign-in flow](https://learn.chatgpt.com/docs/auth#login-on-headless-devices).

1. In ChatGPT security settings, enable device-code sign-in if the official sign-in page asks you to.
2. In **Work Usage**, tap **Connect with a device code**, then **Copy code and open ChatGPT**.
3. On OpenAI’s sign-in page, enter the one-time code you just generated and approve the request.
4. Switch back to **Work Usage** after the browser confirms approval. Leave the app open while it finishes sign-in and loads your values.
5. Compare the widget’s values and reset times with the official usage dashboard. Check the last-sync time when the numbers differ.

Tap the refresh icon to request an update. Android schedules background refreshes at roughly 15-minute intervals while a widget is pinned and the app is connected. The phone can defer those updates to save power.

## Build from source

Use JDK 17, the Android SDK Platform 35, Android SDK Build Tools 35.0.0, and Gradle 8.11.1. Open the repository root in Android Studio and choose the **app** run configuration, or use the command line with Gradle on your PATH.

```sh
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. If you already have another build with a different signature, Android requires you to uninstall that build first. Uninstalling clears that app’s saved values and session.

Build requirements follow the [Android Gradle Plugin 8.9 compatibility table](https://developer.android.com/build/releases/agp-8-9-0-release-notes).

## Troubleshooting

- **Browser approval succeeded and the app shows a sign-in error.** Record the short error line without copying the sign-in URL or one-time code. Manual entry is available if you need a snapshot while troubleshooting.
- **A dash appears.** The value or reset time is unavailable, or the saved reset time has passed. Compare against the official dashboard and refresh.
- **Background updates are delayed.** Open the app or tap the refresh icon to request a new check.

# Work Usage Widget guide

## Quick start

Download the [v1.6 APK](../downloads/ChatGPT_Work_Usage_v1_6.apk?raw=true), open it on Android, and follow the install prompt. Existing users can update in place. Open **Work Usage** after installation.

### Enter values manually

1. Open the [ChatGPT usage dashboard](https://chatgpt.com/settings/usage) and find the limits you want to display.
2. In **Work Usage**, tap **Enter dashboard values**. Enter the percentages left and earned reset count. Leave any unavailable value blank.
3. Tap **Save**, then [add a widget](#add-a-widget).
4. Update your numbers by tapping **Enter dashboard values** again. The widget shows the time of the last manual entry.

### Connect live sync

Live sync uses the [Codex device-code sign-in flow](https://learn.chatgpt.com/docs/auth#login-on-headless-devices).

1. In ChatGPT security settings, enable device-code sign-in if the official sign-in page asks you to.
2. In **Work Usage**, tap **Connect with a device code**, then **Copy code and open ChatGPT**.
3. On OpenAI’s sign-in page, enter the one-time code you just generated and approve the request.
4. Switch back to **Work Usage** after the browser confirms approval. Leave the app open while it finishes sign-in and loads your values.
5. Compare the widget’s values and reset times with the official usage dashboard. Check the last-sync time when the numbers differ.

Tap the refresh icon to request an update. Android schedules background refreshes at roughly 15-minute intervals while a widget is pinned and the app is connected. The phone can defer those updates to save power.

## Add a widget

1. In Work Usage, tap **Add to home screen**.
2. Choose **Full (4 × 2)** for both limits and reset details, **Five-hour (2 × 1)** for the short limit, **Weekly (2 × 1)** for the weekly limit, or **Next session (2 × 1)** for the lower reported allowance and a planner shortcut.
3. Confirm in your launcher. If your launcher uses its own widget picker, touch and hold your home screen, open **Widgets**, and look for Work Usage.

You can pin any combination of the four widgets. The compact widgets share saved values and live updates with the full widget.

## Change the widget style

Open **Widget style** in Work Usage. Tap **Dark**, **Light**, **OLED**, **Ocean**, or **Sunset** to preview the full and compact widgets with sample data. Tap **Apply** to update every pinned Work Usage widget. Your choice is saved on your phone.

## Plan a session

Open **Plan a session** from the dashboard or tap the **Next session** widget.

- Choose a 30-, 60-, 90-, or 120-minute block and select a 10%, 20%, or 30% reserve. Work Usage saves these choices on your phone.
- Use **Now** or **At next reset**. The timeline marks a reset inside the selected block or at its boundary. A later reset appears as a time beneath its track.
- The reserve margin is reported in percentage points. For example, 12% left against a 20% reserve is 8 points below your reserve. Allowances on both views come from the same latest reading, including when you move the start.
- The planner uses reset timing from a successful live reading up to 30 minutes old. Manual and saved readings can still show allowance and reserve. A window whose reported reset has already passed needs a fresh reading.
- **Next session** follows the lower available reported percentage. Its amber value means the reading is below your selected reserve. Its reset time appears when that window's known reset falls inside or at the end of a block starting now. **Usage alerts** have their own settings.

## History and alerts

- **History** shows up to seven days of local readings from successful syncs and manual entries. It keeps the most recent reading in each 15-minute period and starts collecting after you install v1.5. Tap **Clear history** to delete those readings.
- **Usage alerts** are off by default. Choose 10%, 20%, or 30% remaining and allow notifications if Android asks. Alerts are evaluated after a successful live sync. For background checks, keep the app connected and pin a widget. Android can delay background checks.
- Each limit can alert again after your remaining allowance goes above your threshold or a known reset passes. Manual entries are included in history and live sync supplies the alert readings.
- The app dashboard shows your local reset time and a countdown for each limit with a known upcoming reset.

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

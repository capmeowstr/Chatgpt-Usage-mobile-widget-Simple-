# Work Usage Widget v1.6.1

[Download the signed Android APK](../downloads/ChatGPT_Work_Usage_v1_6_1.apk?raw=true) · [SHA-256](../downloads/SHA256SUMS) · Android 8.0+

| Finding | Fix |
| --- | --- |
| Device sign-in codes were copied to the Android clipboard. | Open the official sign-in page and type the code. Android’s secure-window flag protects the code screen. |
| Browser sign-in kept the local listener open while waiting for the user to return. | Validate callbacks against the local address and sign-in state. Close the listener before exchanging the code. Return to Work Usage using Android’s app switcher. |
| An unreadable credential record could repeatedly fail refresh. | Validate the encrypted record and request a fresh sign-in when it cannot be loaded. Disconnect removes the local encryption key as well as the session. |
| A rate-limited usage request could be retried before the server’s requested wait. | Honor HTTP `Retry-After` on quota or token requests used for refresh, with a 30-second minimum and a one-hour cap. A rate-limited reset-credit request backs off independently, so the main allowances can still update. |
| Account and usage preferences needed explicit rules for Android’s device-transfer path. | Exclude all app preferences from cloud backup and device transfer. |

Live sync uses an encrypted Codex session with access broader than the quota display. Manual entry works without an account session. [Privacy and account access](../PRIVACY.md)

[Setup guide](guide.md) · [Previous release](release-notes-v1.6.md)

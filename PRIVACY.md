# Privacy and account access

## Data on your phone

The app saves the displayed allowances, reset times, earned reset count, last-updated time, widget style, and session planner preferences in private app storage. Manual entry saves a snapshot locally and works independently of sign-in.

History keeps up to seven days of readings from syncs and manual entries in private app storage. You can erase it with **Clear history**. A fresh sign-in clears previously synced readings and retains manually entered history. Usage alerts are off by default, and their preferences are stored locally. Android displays enabled usage alerts as private notifications.

Live sync uses a Codex session. The app saves its access and refresh credentials encrypted with an Android Keystore key. Account credentials are broader than the usage counters shown by the widget. Enter one-time codes only on OpenAI’s sign-in page when you started the request yourself.

## Network requests

The app contacts OpenAI’s sign-in service to request, exchange, and renew sign-in credentials. It contacts ChatGPT’s usage endpoints to read the remaining allowance and available reset count. Quota refreshes read data. They do not send prompts, run models, or redeem resets. The code includes no analytics or third-party data collection endpoint.

## Disconnecting

**Disconnect** in Work Usage erases the app’s saved session, cached snapshot, and usage history. It cancels pending usage alerts and refresh jobs. To disconnect account-side access, review the **Codex CLI** entry under **Sign in with ChatGPT** in ChatGPT settings. Disconnecting that entry may affect other Codex CLI sessions.

## Reporting a problem

Keep account emails, session cookies, access and refresh tokens, one-time codes, and URLs containing authorization parameters out of screenshots and reports. The app’s short error message and Android version are enough to identify the failing sign-in step.

# Privacy and account access

## Data on your phone

The app saves the displayed allowances, reset times, earned reset count, last-updated time, widget style, and session planner preferences in private app storage. Manual entry saves a snapshot locally and works independently of sign-in.

History shows the last seven days of readings from syncs and manual entries. The app prunes older readings when it reads or updates history. You can erase it with **Clear history**. When a session ends or a new sign-in starts, the app clears previously synced readings and retains manually entered history. Usage alerts are off by default, and their preferences are stored locally. Android displays enabled usage alerts as private notifications.

Live sync uses a Codex session. The app saves its access and refresh credentials encrypted with an Android Keystore key. The app asks for your consent before a new sign-in because Codex credentials grant broader access than the usage counters shown by the widget. Enter one-time codes only on OpenAI’s sign-in page when you started the request yourself. Work Usage keeps the code off the clipboard and uses Android’s secure-window flag while displaying it. On Android 14 and later the code is also marked sensitive for accessibility services. Trusted accessibility tools can still read it. The app excludes its preferences from Android cloud backup and device transfer.

## Network requests

The app contacts OpenAI’s sign-in service to request, exchange, and renew sign-in credentials. It contacts ChatGPT’s usage endpoints to read the remaining allowance and available reset count. Quota refreshes read data. They do not send prompts, run models, or redeem resets. The code includes no analytics or third-party data collection endpoint.

## Disconnecting

**Disconnect** immediately hides saved usage from the app and queues the widget update. The app clears its local credentials, encryption key, snapshot, and history independently of any in-flight refresh. A late response is discarded. **Clear saved usage** is available while signed out.

This action clears the device’s session. OpenAI manages server-side access. ChatGPT’s [Active sessions documentation](https://help.openai.com/en/articles/20001257-managing-active-sessions-in-chatgpt) says that screen excludes Codex CLI sessions. Contact OpenAI Support if you need help with account-side revocation.

## Reporting a problem

Keep account emails, session cookies, access and refresh tokens, one-time codes, and URLs containing authorization parameters out of screenshots and reports. The app’s short error message and Android version are enough to identify the failing sign-in step.

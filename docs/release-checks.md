# Release checks

## Status

The v1.2 APK passed integrity and signing checks. The widget rendered with sample data and the simulated sign-in tests passed. The project owner confirmed live sync on a physical Android phone.

Live sync currently depends on ChatGPT backend endpoints used by Codex. These endpoints can change. A future integration can be evaluated using the documented [Codex App Server usage read](https://learn.chatgpt.com/docs/app-server).

## Physical-device acceptance

- [x] Finish device-code sign-in and load usage on a physical Android phone. Confirmed by the project owner.
- [ ] Compare both allowance percentages, both reset times, and the earned reset count against the official usage dashboard.
- [ ] Pin the widget. Check manual refresh, background refresh after a phone restart, and an unavailable value.
- [ ] Try **Enter dashboard values** while signed out and confirm that the widget labels it as manually entered.
- [ ] Disconnect and verify that the local snapshot disappears and background refresh stops.
- [x] Verify the v1.2 APK signature, package name, version, and submitted files.

Use sample values for public screenshots and omit sign-in screens from release assets. The [v1.2 release notes](release-notes-v1.2.md) describe this version.

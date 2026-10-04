# iPhone access through a private network

Smart Inbox can run as a Home Screen web app on an iPhone while its Windows host is online. The phone and desktop use the same backend and database. This does not copy the database to a cloud host or run the Java services on iOS.

## First-time setup

1. Install the official [Tailscale Windows client](https://tailscale.com/download/windows) and [iOS app](https://tailscale.com/download/ios). Sign in on both devices with the same personal account.
2. Start Smart Inbox on the PC. Open **手机连接 / Connect iPhone** on the desktop dashboard, then **完成网络设置 / Finish network setup**.
3. If Tailscale asks to enable HTTPS, complete the official consent page and retry setup. The helper configures **Serve**, not public Funnel. It refuses to overwrite another service on port 443.
4. Generate a pairing code on the PC. On the iPhone, connect Tailscale, open the private HTTPS address in Safari, and enter the code and a device name. A code expires after 10 minutes and can be used once.
5. In Safari, use **Share → Add to Home Screen → Open as Web App**. If iOS asks to pair again in the installed app, generate a fresh code; Safari and a Home Screen app can have separate sessions.
6. Turn off Wi-Fi temporarily and verify access over cellular while the PC remains online. A successful local browser test alone does not verify this step.

## Daily use

- The PC must be awake, connected, and running Smart Inbox's backend. Locking the PC is fine if it does not sleep. Standby mode stops the backend, so the phone cannot sync until the PC resumes it.
- Keep Tailscale connected on the iPhone. Devices on school Wi-Fi or cellular do not need to share the PC's Wi-Fi.
- A paired session expires after 30 days. A revoked, expired, or cleared session requires a new pairing code. The PC can revoke a phone from its connection panel.
- Account authorization for email, IBKR, and ChatGPT stays on the PC. The phone can use authorized business features; it cannot manage credentials, backups, account connections, or PC power/runtime controls.
- Data refreshes when the page regains focus and through existing polling. This is not a background iOS sync service. An offline screen does not mean new data has been downloaded.
- Financial reports are generated only on request and continue to use the selected account's available AI access. Private content is not stored in the service worker cache.

## Technical boundary

- Desktop listener: `127.0.0.1:5173`; existing local protections stay enabled.
- Private mobile listener: `127.0.0.1:5174`, started only with valid local configuration.
- Tailscale Serve terminates HTTPS and proxies to the mobile listener. The listener verifies the exact configured Host, same-origin requests, and Tailscale's authenticated user header.
- Pairing adds a `Secure`, `HttpOnly`, `SameSite=Strict` session cookie. Only hashes of session secrets are persisted. Every business API requires pairing and must match the method/path allowlist.
- `.smart-inbox/mobile/` contains local-only configuration and pairing/session metadata and is ignored by Git. Never include it in screenshots, support bundles, or a public repository.
- The setup helper checks existing Serve configuration before and after changing it. Do not substitute Funnel or expose port 5173/8080/8083 to the internet.

## Verification and limitations

Run `node --test scripts/mobile-network.test.mjs scripts/mobile-access.test.mjs scripts/desktop-server.test.mjs scripts/runtime-control.test.mjs` for the server-side access controls. Frontend component tests cover pairing, desktop-only management, and offline states.

Browser responsive tests are useful but do not replace an actual iPhone Safari/Home Screen test. Complete account login, HTTPS consent, and cellular access on the user's devices before reporting the remote setup as fully verified.

References: [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve), [Tailscale iOS setup](https://tailscale.com/docs/install/ios), [Apple Home Screen web apps](https://support.apple.com/guide/iphone/open-as-web-app-iphea86e5236/ios).

# Privacy policy

**Short version: nothing leaves your phone.**

Khayali Pulao is a calculator. It runs entirely on your Android device ("on the edge"), and it is built so that it *cannot* send your data anywhere.

## What the app can access

| Access | Why | Limits |
|---|---|---|
| Android **Accessibility service** | To read the numbers Kite already shows on screen (quantity, average price, LTP, invested / current) when you tap a row, and to draw the floating bubble. | Acts only while Zerodha Kite (`com.zerodha.kite3`) is the app in front. It never types, never taps Buy/Sell and never places orders. The only action it ever sends to Kite is a scroll, and a click on Kite's own **navigation tabs** (bottom bar, Holdings/Positions, watchlist groups). |
| Internet | — | **None.** The app requests no permissions at all; the manifest explicitly *removes* `INTERNET` and `ACCESS_NETWORK_STATE`, and CI fails the build if any permission appears. |

## What the app stores

- Your own settings (FD rate, inflation, ladder steps, tone, bubble size, your custom dream unit…).
- The holding periods **you type in**, keyed by the stock's name, so "vs FD" and LTCG/STCG work.

It does **not** store your holdings, balances, account ID or anything else read from Kite.
Stored settings live in the app's private storage on your phone and are **excluded from Android cloud backup and phone-to-phone transfer** (`allowBackup="false"` + data-extraction rules).

## What the app does not do

No login, no account, no analytics, no crash reporting, no ads, no trackers, no third-party SDKs, no network code (CI checks the source for it).

## Deleting your data

Uninstall the app, or Android Settings → Apps → Khayali Pulao → Storage → Clear data.

## Contact

Open an issue on this repository.

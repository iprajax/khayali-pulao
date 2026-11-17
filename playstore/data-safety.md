# Data safety & App content — Play Console answers

> Play Console → **Policy and programs → App content**. Answers are based on the code: only INTERNET (one-time model download), nothing uploaded, no analytics SDKs, no backup.

## Data safety
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A (no data collected) |
| Do you provide a way for users to request that their data is deleted? | N/A — nothing is collected. (Uninstall / Clear data removes local settings.) |

> **Review before submitting 0.8+.** With the optional Web feature, search words the user types (or the chat composes) are sent to DuckDuckGo, and company symbols to Yahoo Finance. They go straight from the phone to those services — never to the developer — and are off until the user allows it. Google may still treat this as *App activity → In-app search history, shared with third parties, optional*. The cautious answer is to declare exactly that; decide with the current Play policy text in front of you.

Why "No" (0.7 and earlier): on-screen values are processed only in memory on the device and never stored or transmitted; the only network request is downloading the model file from huggingface.co. Settings stay on device and are excluded from backup. Per Google's definition, data processed only on-device and not sent off-device is not "collected".

## Other App content declarations
| Section | Answer |
|---|---|
| Privacy policy | https://github.com/iprajax/khayali-pulao/blob/main/PRIVACY.md |
| Ads | **No ads** |
| App access | **All functionality available without special access.** Note for reviewers: "Use *Try the demo* on the home screen to see every feature with fictional data; the overlay itself needs the Zerodha Kite app." |
| Content rating | Complete the IARC questionnaire → no violence/sex/gambling/UGC → expected **Everyone / 3+** |
| Target audience | **18+** (finance) |
| News app | No |
| Government app | No |
| Financial features | **None of the listed** (no loans, trading, payments, crypto). It's a calculator; it does not provide brokerage or advisory services. |
| Health | No |
| Accessibility API | See `accessibility-declaration.md` |

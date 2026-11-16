# Data safety & App content — Play Console answers

> Play Console → **Policy and programs → App content**. Answers are based on the code: zero permissions, no network, no SDKs, no backup.

## Data safety
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A (no data collected) |
| Do you provide a way for users to request that their data is deleted? | N/A — nothing is collected. (Uninstall / Clear data removes local settings.) |

Why "No": on-screen values are processed only in memory on the device and never stored or transmitted; the app has no internet permission. Settings stay on device and are excluded from backup. Per Google's definition, data processed only on-device and not sent off-device is not "collected".

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

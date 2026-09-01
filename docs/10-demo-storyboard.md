# 10 — 90-second demo storyboard

Record this, in this order. The point of the video is the recovery, not the
happy path.

| Seconds | Picture | Voice |
| --- | --- | --- |
| 0–10 | Both screens: Mac peripheral advertising, A32 on the entry screen | "Controller on Android 13, pump radio on the Mac, decisions in Kotlin." |
| 10–25 | Confirm 1.00 U, Delivered | "Happy path. The UI moves on a pump-confirmed record, not a GATT write callback." |
| 25–40 | Inject Disconnect mid-command, confirm 0.50 U, link drops | "I am going to pull the link after the pump has accepted." |
| 40–70 | App shows Resolving, reconnects, queries | "Retries exhausted. That is not failure. Outcome is unknown. We query the original CommandId." |
| 70–90 | COMPLETED or NEVER_SEEN / reissue prompt. No new identifier. | "Query, then decide. We never allocate a second CommandId for the same dose." |

Do not narrate the stack. Do not claim verification on Android 14. If the
store-reset fault is cleaner on the day, it can replace the disconnect: the
line to hit is "NEVER_SEEN after a reset is STORE_REPLACED, and that is
indeterminate."

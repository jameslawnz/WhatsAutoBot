# Plan: Production hardening + WhatsApp marketing features

Objective: turn the current prototype into a professional, production-ready
personalized WhatsApp marketing app. Modules approved by user: all.

## Modules (build order)

| # | Module | Deliverable | Depends on |
|---|--------|-------------|-----------|
| 1 | security | `Crypto.kt` (Android Keystore AES-GCM); encrypt ContactStore payload + Prefs template/recipients/reply | — |
| 2 | queue-persistence | Durable `WaQueue` (persist deque + running flag); resume after process death | — |
| 3 | compliance | `Throttler.kt`: random jitter, daily quota, send window, cross-campaign dedup; consent gate in UI | queue-persistence |
| 4 | campaign | `CampaignStore.kt` + history screen; auto-create/finish around sends | queue-persistence |
| 5 | personalization | `Templates.kt`: `{name}`, `{phone}`, `{sender}`, `{message}`; shared helper + tests | campaign |
| 6 | automation | Retry/backoff + skip-on-fail + `ErrorLog`; split send/scan/capture into drivers | queue-persistence |
| 7 | quality | Unit tests (Templates, Throttler, CampaignStore, WaQueue), lint config, GitHub Actions CI | all |

## Verification
- `gradle :app:assembleDebug` passes
- `gradle :app:testDebugUnitTest` green
- `gradle :app:lintDebug` clean

## Risks
- Keystore AES-GCM on emulator/device — fallback to plaintext if key unavailable (logged).
- Service split touches send loop — keep behavior identical, rely on build + existing send tests.
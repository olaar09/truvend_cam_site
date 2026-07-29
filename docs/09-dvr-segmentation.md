# DVR periodic segmentation (box-side)

**Status:** Implemented on the Android box · Remote config refresh stubbed (MQTT later)

**UI codename:** user-facing labels say **Keeper** (not “segmentation” / “DVR record split”). Internal code, DB columns, and this doc keep the real name.

The box keeps DVR recordings bounded by periodically forcing a manual stop → start on each main track. That unblocks server-side playback: ContentMgmt/search returns hour-scale segments instead of multi-day files.

**Prerequisite:** DVR **timezone** and **current date/time** must be correct (and match the site timezone in inventory). Wrong clock/zone makes day-based search and playback queries miss segments — see [SYSTEM-ARCHITECTURE §7.3](SYSTEM-ARCHITECTURE.md#73-time-and-timezone-required-for-playback).

## What runs where

| Piece | Location | Notes |
|---|---|---|
| Timer + retries | `dvr/SegmentationScheduler.kt` | Own `HandlerThread`; not in the TCP pipe |
| ISAPI stop/start | `dvr/RecordControlClient.kt` → `IsapiClient.putRecordControl` | LAN Digest; Wi‑Fi bind |
| Local prefs | `ConfigRepository` keys `segmentation_enabled`, `segmentation_interval_hours` | Defaults: on, 1 hour |
| Lifetime host | `ForwarderService` | Wake lock / boot only — forwarder accept loop untouched |
| Remote refresh | `dvr/SiteConfigSource` → `LocalOnlySiteConfigSource` | No-op until enrolment |
| Event clips | `dvr/EventSignalStub` | **Deferred** — design only |

## ISAPI calls

```
PUT http://{dvr}/ISAPI/ContentMgmt/record/control/manual/stop/tracks/{101|201|…}
    (500 ms)
PUT http://{dvr}/ISAPI/ContentMgmt/record/control/manual/start/tracks/{101|201|…}
```

Each track is independent. Failures retry up to 3 times (2 s apart), then wait for the next interval. A miss means one longer segment — not a broken system.

The box does **not** report boundaries to the server.

## Gap measurement (do before shipping)

Bench observation included human delay (~57 s). Code uses a fixed 500 ms pause only.

1. Start the relay (segmentation starts with it).
2. On a **debug** build: Relay screen → **Segment now (debug)**.
3. Watch diagnostic log (`DvrSegmentation` / `RecordControl`) for `stop→start completed in Xms`.
4. On the DVR (or via ContentMgmt/search through the tunnel), confirm a new segment boundary and measure the footage hole.
5. **Pass:** hole under ~10 seconds.
6. If not: `GET /ISAPI/ContentMgmt/record/control/manual/capabilities` (see `RecordControlClient.fetchManualCapabilities`) for a native split/checkpoint that avoids stopping recording.

## Config

| Key | Default | Source today | Source later |
|---|---|---|---|
| `segmentation_enabled` | `true` | Local prefs | Site record + box check-in |
| `segmentation_interval_hours` | `1` | Local prefs | Site record + box check-in |

Changing the interval in the admin UI should take effect on the box’s next check-in once enrolment exists — no re-enrolment.

## Test matrix

| # | Test | Pass |
|---|---|---|
| 1 | Box fires stop → start on schedule (or **Segment now**) | New segment in DVR search |
| 2 | Measure the gap (back-to-back, no manual delay) | Under ~10 s |
| 3 | DVR unreachable during a cycle | Retries then skip; next cycle resumes; no crash |
| 4 | Segment sizes stay bounded | ~bitrate × interval, not multi-day |
| 5 | Continuous scheduled recording still works | Recording resumes after cycle; not left off |

## Explicitly out of scope

On-box event buffering / pre-roll / separate clip upload. When detection exists, send a lightweight signal; the server uses the same dumb-playback pipeline over these bounded segments.

## Reminder — push site config to the box via MQTT (do later)

**Do not build an HTTP poller for this.** Super admin already edits `segmentation_*` in the DB (tru-view Settings). The box still uses local prefs only (`LocalOnlySiteConfigSource`).

When MQTT lands:

1. Keep DB as source of truth (super-admin PATCH unchanged).
2. On save, publish e.g. `sites/{device_id}/config` with `segmentation_enabled` + `segmentation_interval_hours`.
3. Box subscribes over the tunnel, writes prefs via `ConfigRepository.saveSegmentationSettings`, scheduler picks up on the next cycle.
4. Replace or back `SiteConfigSource` with that push path (box still needs a one-time bootstrap for `device_id` + MQTT creds).

Until then: change the interval on the box only by editing local defaults/prefs or rebuilding; dashboard edits do not reach the device.

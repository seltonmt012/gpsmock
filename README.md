# GPS Mock

Android app that feeds a chosen position into the system's test location providers.
Pan the map, hit start, and every app that asks Android where the phone is gets the
position under the crosshair instead of the real one.

## Why another one

Most mock location apps drift back to the real position after a while. The usual causes,
and what this app does about them:

| Cause | Handling |
|---|---|
| Doze freezes the update loop | Foreground service plus a partial wake lock |
| Fixes get discarded as stale | Fresh `elapsedRealtimeNanos` on every push, every 900 ms |
| Only `gps` is mocked | `gps`, `network` and `fused` are all fed — apps read `fused` |
| Position looks frozen | ~1.2 m of jitter and a varying accuracy value |
| System drops the test provider | The loop notices and re-registers in place |
| Process killed | `START_STICKY` plus persisted target, so it resumes itself |
| Reboot | Boot receiver, with a notification fallback when Android 15 blocks the start |

Fields that many mock apps leave unset — `verticalAccuracyMeters`,
`bearingAccuracyDegrees`, `speedAccuracyMetersPerSecond` — are populated, because some
consumers reject a fix without them.

## Setup

1. Install the APK from [Releases](../../releases/latest).
2. Settings → About phone → tap *Build number* seven times.
3. Settings → System → Developer options → **Select mock location app** → GPS Mock.
4. Settings → Apps → GPS Mock → Battery → **Unrestricted**, and turn off
   *Pause app activity if unused*. Without this Android freezes the mock when the screen
   goes off.
5. Settings → Location → Location services → turn off *Google Location Accuracy*.

The in-app **Setup** button walks through the same list.

## Updates

The app checks a manifest on launch and can install a new release itself. Android still
shows its own confirmation dialog, and it refuses any package whose signature does not
match the installed one — so the update path cannot be used to swap in different code.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew assembleRelease
```

Signing reads `keystore.properties` from the project root, which is not in version
control. Without it the release build is unsigned.

## Map data

Map tiles come from OpenStreetMap; place search uses Nominatim.

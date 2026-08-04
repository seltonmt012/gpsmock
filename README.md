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

## Trips

Besides a fixed point, the app can follow a route on a daily schedule: pick a start and a
destination, choose car, bike or foot, and set a departure and a return time. Both legs are
routed separately against the FOSSGIS OSRM servers, because one-way streets make the way
back a different path.

The position is computed as a pure function of the wall clock rather than accumulated tick
by tick. A killed and restarted process therefore resumes at the correct point on the route
instead of teleporting back to where it left off, and a phone that was off during the ride
picks up wherever the schedule says it should be.

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

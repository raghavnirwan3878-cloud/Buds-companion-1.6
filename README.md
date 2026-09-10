# Buds Companion

A small standalone Android app that talks directly to your Realme TechLife
Buds T100 (and other Oppo/Realme-protocol earbuds) over classic Bluetooth
(RFCOMM/SPP) — no Gadgetbridge, no realme Link needed.

It:
- Connects directly using the vendor's own protocol (reverse-engineered from
  Gadgetbridge's AGPLv3-licensed `OppoHeadphonesProtocol`/`OppoHeadphonesSupport`)
- **Subscribes to push battery updates** so levels refresh automatically
  whenever they change, instead of only reading once at connect time
  (this is the gap Gadgetbridge has for this device family)
- Also polls every 5 minutes as a safety-net fallback
- Shows a home-screen widget with Left / Right / Case percentages
- Fires a notification when any battery crosses below a low threshold
- Auto-reconnects with backoff if the connection drops
- Restarts monitoring automatically after a phone reboot

## Building

You'll need [Android Studio](https://developer.android.com/studio) (free).

1. Open Android Studio → **Open** → select this `RealmeBudsCompanion` folder.
2. Let Gradle sync (it will download the Android Gradle Plugin etc. the first time).
3. Plug in your phone (with USB debugging enabled) or use an emulator, then
   press **Run**. Or: **Build → Build Bundle(s) / APK(s) → Build APK(s)** to
   get an installable `.apk` you can sideload.

There's no server, no account, no cloud — everything runs locally on your phone.

## First-time setup

1. Pair your earbuds normally via your phone's system Bluetooth settings
   first (so they show up as a bonded device).
2. Open **Buds Companion** → **Choose earbuds** → pick them from the list.
3. Tap **Start monitoring** and grant the Bluetooth/notification permissions
   it asks for.
4. Add the **Buds Companion** widget to your home screen (long-press home
   screen → Widgets → Buds Companion).

## Notes / limitations

- This only works with earbuds using the same Oppo/Realme classic-Bluetooth
  protocol as the T100 (shares code with T110/T200/T300 and several Oppo
  Enco models in Gadgetbridge). Other earbud families use a different
  protocol entirely and this app won't talk to them.
- The `0x09` byte in the subscription-set payload is a constant observed in
  Gadgetbridge's own implementation (unexplained in their source comments,
  possibly a fixed header/action byte) — kept as-is since it's what's known
  to work.
- If your earbuds don't send push updates for your firmware version, the
  app still works fine off the 5-minute poll fallback — just less
  instantaneous.
- Some Android OEMs (aggressive battery optimizers) may kill background
  services; if the widget stops updating, check that the app is excluded
  from battery optimization in system settings.

## Why this exists

Gadgetbridge only requests battery once at connection time for this earbud
family and never re-polls while connected (confirmed directly in their
source: `initializeDevice()` calls `encodeBatteryReq()` exactly once, with
retries only if the very first read comes back empty). This app fixes that
by using the vendor's own subscription mechanism for live push updates.

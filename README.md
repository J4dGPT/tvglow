# TV Glow

Android TV app that mirrors your screen's edge colors onto a Govee H6167 strip, in real time, over your local network. No cloud, no sync box.

How it works: the app captures the screen at 96x54 (tiny, near-zero CPU), samples color zones around the edge (skipping letterbox bars), smooths them, and streams them to the strip using Govee's LAN "razer" realtime protocol (the same one Govee Desktop and Razer Chroma use). Per-zone color, not one color for the whole strip.

## 1. Before anything

In the Govee Home app: strip settings, turn **LAN Control** on. The TV and the strip must be on the same home network.

## 2. Build the APK (pick one)

**GitHub (no Android Studio needed)**
1. Create a private repo and push this folder.
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push (or run it manually).
3. Download the `TVGlow-apk` artifact and unzip it. You get `app-debug.apk`.

**Android Studio**
Open this folder, wait for Gradle sync, then Build > Build App Bundle(s) / APK(s) > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`.

## 3. Install on the Hisense

1. TV: Settings > Device Preferences > About, click **Build** 7 times to unlock Developer options.
2. Settings > Device Preferences > Developer options: turn on **USB debugging** (and **Network debugging** if listed).
3. Find the TV's IP: Settings > Network & Internet > your network.
4. From your laptop:
   ```
   adb connect <TV_IP>:5555
   adb install app-debug.apk
   ```
   Accept the "Allow debugging" prompt on the TV the first time.

No laptop? Install the **Send Files to TV** app on both phone and TV, send the APK, and open it from the TV's file manager (allow unknown sources when asked).

## 4. Use it

1. Open **TV Glow** from the apps row.
2. **Find strip** (optional, Start does this automatically).
3. **Test direction**: the strip should be red at the bottom of the left side, fading to blue at the end. If red is on the right, toggle **Reverse**. Use **Shift** if the colors start in the wrong spot. If your strip runs along the bottom too, set **Sides: 4**. Press **Stop** to end the test.
4. **Start sync**, allow screen capture, press **Home**, play something.

Settings apply live while syncing. **Stop** hands the strip back to whatever mode the Govee app had it in.

## Limits (honest)

- **Protected apps** (Netflix, Disney+, Prime, etc.) show up black to any capture app, so the strip goes dark. The status line will say so.
- **HDMI inputs** (console, cable box) usually can't be captured on Android TV either.
- **Works:** YouTube, Plex, MX Player, Jellyfin, browser video, Play Store games, the home screen.
- The capture permission resets when the app stops; you approve it again each time you start.
- The razer protocol is undocumented by Govee. It's confirmed on H6167 by community projects, but a firmware update could change it.

## Troubleshooting

- **"No strip found"**: LAN Control off, or the TV and strip are on different networks/VLANs (guest network, client isolation). Type the strip's IP in the address field instead (find it in your router's device list; the strip's MAC is in the Govee app).
- **Start does nothing / "no screen-capture permission screen"**: some TV builds hide the dialog. Grant it once with:
  ```
  adb shell appops set com.jad.tvglow PROJECT_MEDIA allow
  ```
- **Flickery or laggy**: raise **Motion** to Smooth, or drop **Rate** to 15/s.
- **Washed out**: lower **Brightness**; the app also boosts saturation a little by default.

## Code map

- `GoveeProtocol.java`: LAN JSON + razer binary frames (header 0xBB, XOR checksum).
- `GoveeClient.java`: UDP send, multicast discovery on 239.255.255.250:4001.
- `EdgeSampler.java`: letterbox detection, edge zone sampling, ordering, color adjust.
- `CaptureService.java`: MediaProjection capture, smoothing, frame pacing, keep-alive.
- `MainActivity.java`: D-pad friendly settings screen.

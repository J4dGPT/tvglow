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

## WLED (frame-rate sync)

For updates as fast as the TV delivers frames, use an ESP32 running [WLED](https://kno.wled.ge) with an addressable strip (WS2812B / SK6812).

1. Flash WLED, join it to your Wi-Fi, set the LED count in WLED.
2. In TV Glow: **Mode: WLED**, enter the controller's IP under Connection and Save, set **WLED LEDs** to your count.
3. Calibrate with **Test sides** exactly like the Govee strip, then **Rate: 60/s** and **Start sync**.

TV Glow streams WLED's realtime UDP protocol (DRGB/DNRGB, port 21324). WLED goes back to its normal effect 2 seconds after sync stops.

## Turbo realtime (fastest)

**Try Turbo** switches to Govee's realtime protocol (the one Govee Desktop and Razer Chroma use): every frame, all segments go out in a single packet. The segment count must match the Govee app exactly (H6167: 12), otherwise the strip silently drops the packets. If the strip stays dark after **Try Turbo**, press **Stop**, then **Mode** until it says App segments.

## Latency engineering

- Frames are processed and sent the moment they arrive, from a display-priority thread.
- Wi-Fi power save is disabled while syncing (low-latency Wi-Fi lock).
- App segments mode uses an error-driven scheduler: each Govee command can paint any set of segments, so scene changes repaint the whole strip in one command and detail fills in next, within a token-bucket budget the strip can absorb without queueing.
- Letterbox detection ignores subtitles and only moves after the bars are stable, so dark scenes don't make zones jump.
- **Speed** sets how hard the strip is driven (Safe, Fast, Turbo, Max). If the lights start trailing the picture, step down one level.

## Accurate colors

- Zones are sampled as the plain average of each edge region, so hues stay true (no bias toward saturated pixels).
- **Style: Accurate** keeps hue within about 3 degrees; Cozy and Vivid are deliberate looks.
- **Match colors** (Look card, with sync stopped) calibrates the strip to your TV and wall: the TV shows a reference color, the strip shows the same color, and left/right on the remote adjusts it until they match. Steps: white, white tint, sky blue (green response), pink (blue response), violet (red response). OK goes to the next step, BACK to the previous one. Finishing saves the result and switches to Accurate. **Reset colors** returns to neutral.

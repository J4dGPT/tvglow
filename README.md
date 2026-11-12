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

- The video comes first. Budget TVs struggle to decode video and capture the screen at the same time, so frames are read at the strip's update rate (set by **Speed**), not the video's: a 60 fps video at Fast is read about 30 times a second, always the newest frame. The capture thread runs at normal priority, so playback wins when the TV is busy, and packets only go out when a color visibly changed.
- A frame that is read is processed and sent at once, with no queue in between.
- Wi-Fi power save is disabled while syncing.
- App segments mode uses an error-driven scheduler: each Govee command can paint any set of segments, so scene changes repaint the whole strip in one command and detail fills in next, within a token-bucket budget the strip can absorb without queueing.
- Letterbox detection ignores subtitles and only moves after the bars are stable, so dark scenes don't make zones jump.
- **Speed** sets how hard the strip and the TV are driven (Safe, Fast, Turbo, Max). If the lights start trailing the picture, step down one level.
- **Videos stutter while syncing?** Use Speed Safe or Fast and Capture Small or Medium. Large capture and Max speed cost the most.

## Accurate colors

- Zones are sampled as the plain average of each edge region, so hues stay true (no bias toward saturated pixels).
- **Style: Accurate** decodes each screen channel from sRGB to linear light before sending. Screen values are gamma encoded but the strip drives its LEDs linearly, so sending them raw made mid-tones too bright and washed colors toward white. Vivid adds saturation on top of the same decoding; Cozy is a deliberate warm, dim look.
- **Match colors** (Look card, with sync stopped) calibrates the strip to your TV and wall: the TV shows a reference color, the strip shows the same color, and left/right on the remote adjusts it until they match. Steps: white, white tint, cyan, magenta, yellow. Each step moves one corner of an RGB color cube (the HyperHDR approach), so it only affects colors near that corner and dark colors stay true. OK goes to the next step, BACK to the previous one. Finishing saves the result and switches to Accurate. **Reset colors** returns to neutral.

## Self-healing sync

- **Auto-resume:** if sync was on when the app stopped (update, reboot), opening TV Glow asks to resume right away.
- **Strip health:** while syncing, the app asks the strip for its status every 3 seconds and listens for the answer. If the strip goes quiet, it scans the network and reconnects if the strip moved to a new address. The status line shows "strip answering" or "strip not answering".
- **On-screen notices** appear over whatever you're watching: when sync starts, when the strip stops answering, and when the picture can't be captured (protected apps, HDMI inputs).

## Smart touches

- **No popups.** TV Glow never interrupts what you're watching. Everything it has to say shows in the status card in the app, in plain words, with the technical numbers in small print underneath. Messages from buttons clear themselves after a few seconds.
- **Live preview.** The status card shows a small TV with the glow around it: the strip's colors right now, zone by zone, about 12 times a second.
- **Rest glow.** When the picture stays black for a few seconds (protected apps like Netflix, HDMI inputs, or just a black screen), the lights ease into a soft warm glow instead of going dark or complaining, then snap back to the picture the moment it's visible again. Cozy rests on an ember; the other styles on a warm white bias light.
- **Night dim (on by default).** The lights ease down to 60% between 10 and 11 pm, stay there overnight, and ease back up between 6 and 7 am.

## Phone remote

Control TV Glow from your phone instead of the TV remote. The TV app's **Phone remote** card shows a QR code: point your phone's camera at it (same Wi-Fi as the TV) and the remote opens in the browser. On iPhone, tap Share, then Add to Home Screen, and it opens like an app from then on.

- **Sync**: pause and resume. Pausing switches the screen copy off completely, so the TV runs at full speed, and resuming needs no permission prompt. Starting sync the first time after the TV restarts still happens on the TV, because Android asks for permission there.
- **Lights**: on and off, whether or not sync is running.
- **Style, Brightness, Motion, Speed, Night dim, Follow TV**: same settings as on the TV, applied within a second.
- A live preview of the wall, updated a few times a second.

The remote lives on the TV at `http://<TV address>:8080` (8081 or 8765 if 8080 is taken), on your home network only, with no password: anyone on your Wi-Fi can change the lights, nothing more. It starts with the TV and sleeps on a socket the rest of the time.

## Smart power and TV health

- **Follow TV (on by default):** when the TV goes to standby or shuts down, the lights switch off, with or without sync running (while syncing they fade out first). When the TV wakes up, they come back on, and sync fades back into the picture, with no need to open the app. A small companion service starts with the TV to make this work when sync is off; it only brings back lights it switched off, so a strip that was already off stays off. Turn **Follow TV** off to keep the lights on as room lighting while the TV is off.
- **Fade-in:** the lights fade up over 0.7 seconds when sync starts or the TV wakes, instead of snapping on.
- **TV memory** appears in the status line while syncing (free now and lowest seen), so you can tell whether the TV is running short.
- **Free up memory** closes apps idling in the background and shows how much memory it freed. Android doesn't let one app force-stop another; this is the version it allows. Idle apps start fresh next time you open them, and anything on screen or playing is left alone. It helps when the TV is short on memory; it can't speed up the screen capture itself.

## Setting it up for someone else

**Install page:** `docs/index.html` is served by GitHub Pages at **j4dgpt.github.io/tvglow** (enable once: repo Settings → Pages → Deploy from a branch → `main` / `docs`). Typing that address into the Downloader app opens the page and starts the download automatically.

**Setup wizard:** a fresh install opens guided setup, all with the remote:
1. Finds Govee lights on the network (or enter a WLED address).
2. Segment count (known models skip this; otherwise count them in the Govee app).
3. Picks the fastest mode that works by asking "do you see red fading to blue?" (Turbo realtime, then App segments, then single color).
4. Placement from two questions: where the strip starts and which way it runs. `Placement.solve` turns that into Reverse and Shift, then a side-color check with Rotate/Flip confirms it.
5. Pick a look (Accurate, Cozy, Vivid) and start syncing.

**Run setup** on the main screen repeats it any time.

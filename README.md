# TV Cast

Mirror a Google Pixel (or any Android 8+ phone) to a Samsung Smart TV over the same Wi-Fi.
No app is needed on the TV — it uses the TV's built-in **Internet** browser.

## How it works
The app captures the phone screen (Android MediaProjection), encodes frames as JPEG and runs a
small web server on the phone at `http://<phone-ip>:8080`. The TV browser opens that address and
shows a live, full-screen MJPEG stream (with an automatic fallback for browsers that lack MJPEG).

Limits: video only (sound stays on the phone), roughly 15–25 fps with ~0.2–0.5 s delay, so it suits
photos, presentations, apps and browsing better than fast games.

## Build the APK

### Option A — GitHub (no computer software needed)
1. Create a new repository on github.com and upload everything in this folder
   (including the hidden `.github` folder).
2. Open the **Actions** tab; the "Build APK" workflow runs automatically (~3–5 min).
3. Open **Releases**, and download `TVCast.apk` from your phone.

### Option B — Android Studio
Open this folder in Android Studio, let it sync, plug in the phone with USB debugging on, and press Run.

## Install on the Pixel
1. Open the downloaded `TVCast.apk`.
2. Allow "Install unknown apps" for your browser/Files app when prompted.
3. If Play Protect warns about an unknown developer, choose *Install anyway* (it's your own build).

## Use
1. Phone and TV on the same Wi-Fi.
2. Open **TV Cast**, tap **Start sharing**, choose **Entire screen** → **Start**.
3. On the TV: Home → **Internet**, type the address shown in the app (e.g. `http://192.168.1.23:8080`).
4. Bookmark it on the TV for next time and use the browser's full-screen mode.

## Troubleshooting
- **TV page never loads:** check both devices are on the same network (not a guest network; some
  routers have "AP/client isolation" that blocks device-to-device traffic).
- **Address changed:** your router may give the phone a new IP; reserve one in router settings.
- **Choppy:** lower `MAX_LONG_SIDE` (e.g. 960) or `JPEG_QUALITY` in `CastService.java`.

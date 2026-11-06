# JARVIS Mobile (Android)

The phone half of JARVIS. Its main screen is the blue JARVIS HUD, and it

* keeps a private, encrypted link to JARVIS on your PC, so JARVIS can act on the phone
  (call, SMS, open an app or link, find the phone, volume, flashlight, read notifications,
  battery) and you can talk to / command JARVIS on the PC from the phone;
* answers by itself when the PC is off (or JARVIS is not running), using Google's Gemini API
  (free tier, your own key) and a copy of the same long-term memory, merged back into the
  PC's memory the next time they meet;
* keeps running in the background (a small standing notification, restarts after a reboot).

The PC side is in the JARVIS project: `dashboard/phone_bridge.py`, `dashboard/phone_ui.py`,
`actions/phone_control.py`, `memory/memory_sync.py` and the endpoints at the end of
`dashboard/server.py`. The wire format is in `dashboard/PHONE_PROTOCOL.md` there.

## The main screen comes from the PC

The HUD is a web page, `dashboard/static/phone_ui/index.html` on the PC. The app keeps the
last copy it received, so it opens even when the PC is off, and each time it reaches the PC
it asks for a newer one. Edit that file on the PC and every phone shows the new screen the
next time it connects - nothing to reinstall. (A copy is built into the app,
`app/src/main/assets/hud_default.html`, for the very first launch; keep it equal to the PC's
file when you cut a new app release.)

A replacement screen only has to define `window.jarvis` (see `PHONE_PROTOCOL.md`); the app
refuses a download that is damaged, too big, or does not do that.

Changes to the app's own code (a new phone action, say) need a new app version - see below.

## Get the app without Android Studio (recommended)

GitHub can build the APK for you, free, and host the download for anyone you share it with.

1. Make a free account at github.com, then **New repository** (name it `JarvisMobile`, Public).
2. Upload this folder's contents: **Add file > Upload files**, drag everything in (including
   the `app` and `gradle` folders and the hidden `.github` folder). If the hidden folder is
   awkward to drag, use **Add file > Create new file**, type the path
   `.github/workflows/build-apk.yml` and paste the contents of that file.
3. **Releases > Draft a new release**, tag `v1.0.0`, **Publish release**.
4. Open the **Actions** tab; "Build APK" runs for about 5-8 minutes. When it is green the APK
   (`JarvisMobile-1.0.0.apk`) is attached to the release. Send people the release link.
5. To install: open the link on the phone, download the APK, allow "install unknown apps"
   for the browser when asked, and install.

A newer version later: publish a new release with a higher tag (`v1.0.1`). In the app,
Settings > App updates, enter `yourname/JarvisMobile`, and "Check for an app update".

### Signing key (so updates install over the old version)

Without a key the build is debug-signed: it installs, but a newer build cannot replace it
(the phone says the signatures differ) - people must uninstall first. To avoid that, make one
key, once:

1. On Windows run `powershell -ExecutionPolicy Bypass -File .\tools\make-signing-key.ps1`
   (needs Java; Android Studio's bundled Java is found automatically).
2. It writes `jarvis-release.jks` and `github-secrets.txt`. In your repository open
   **Settings > Secrets and variables > Actions > New repository secret** and create the four
   secrets named in `github-secrets.txt`.
3. Delete `github-secrets.txt`. Back up `jarvis-release.jks` somewhere safe and never share it.
   If you lose it, everyone has to uninstall once.

## Build locally instead (optional)

Open the folder in Android Studio and press Run. If Gradle sync says
"Incompatible Gradle JVM version", choose JDK 17 or 21 under Settings > Build Tools > Gradle >
Gradle JDK (the bundled `jbr` is fine).

## Set it up (once per phone)

1. Install **Tailscale** (free) on the PC and the phone, same account. It gives the PC a private
   address (100.x.y.z) that works from anywhere without opening anything on your router.
2. On the PC start JARVIS and press **Remote Control** for a one-time key.
3. In the app press the sliders icon: enter `100.x.y.z:8001` and the key, **Pair**. The app pins
   the PC's certificate at that moment and trusts only that certificate afterwards.
4. Press the five permission buttons (calls/contacts/SMS, notification access, display over other
   apps, battery, background/autostart). The last one opens your phone maker's own autostart
   page (Xiaomi, Oppo/Realme, Vivo, Huawei, Samsung, Infinix/Tecno) if it has one. Without a
   permission the matching action reports a clear error instead of pretending.
5. For the phone brain paste a Gemini API key (https://aistudio.google.com) and Save.

Sharing it with other people: each person pairs with their **own** PC's JARVIS and uses their
own free Gemini key - there are no keys inside the app. Someone without the PC project can
still use the phone brain on its own.

## What to expect

* Answers are honest: if Android gives no way to confirm an action (an app was asked to open, a
  link handed to the browser) the phone reports `verified=false` and JARVIS says it asked but
  cannot confirm.
* Reading the clipboard from the background is blocked by Android, so that action is not offered.
* Notifications are forwarded only while the link is up and only if you turned on Notification
  access. They can contain private text (one-time codes); turn it off if you do not want that.
* "PC off" really means "JARVIS is not running on the PC".
* The ring animation follows what it can see: it "thinks" after you speak, "speaks" while an
  answer arrives, and sleeps when JARVIS sleeps. It does not yet read JARVIS's exact internal state.

## Licence

JARVIS Mobile works with JARVIS by FatihMakes, which is CC BY-NC 4.0 - see `NOTICE.md`.
Non-commercial use only; keep the credit when you share it.

# Dekho

An ad-free home screen for TCL Google TVs. One screen, no scrolling: your streaming apps,
HDMI inputs with live status, one-press brightness, Wi-Fi and headphone status.
Tested on one TCL 55C61B (Android TV 12) only.

## Using it

- **Home** on the remote over any app shows a strip of your apps and inputs along the bottom.
  Press Home again to go to Dekho. Back closes the strip.
- **Bluetooth** connects your paired headphones. Hold it to open the Bluetooth page.
- **Google TV** visits Google's screen. Hold it to make Google TV the home screen again.
  Open Dekho from Apps to switch back.
- **Customise** picks up to six apps, names your HDMI ports (or hides them), and sets up the
  phone remote. It needs only the TV remote, no typing.

## Installing

1. On the TV: Settings, System, About. Press **Android TV OS build** seven times.
2. Back in Settings, System, Developer options: turn on **USB debugging**.
3. Download the latest `.apk` from [Releases](https://github.com/jasonzacmusic/dekho/releases/latest).
   Find the TV's address in Settings, Network and Internet. On a computer with `adb`, on the same Wi-Fi:

   ```bash
   adb connect TV-ADDRESS:5555
   ```

   ```bash
   adb install dekho-1.6.apk
   ```

   The first time, the TV asks to allow the computer. Tick Always allow, then OK.
4. Open Dekho from Apps. Choose Customise, then **Home button helper**, turn on **Dekho Home button**.
5. Press Home on the remote: Dekho opens.

## Phone and computer remote

In Customise, press **Set up phone remote**. The TV asks to allow debugging: tick Always allow, then OK.
Then open the address shown at the top of Customise in any browser on the same Wi-Fi.
It has arrow keys, OK, Back, Home, volume, mute, power, your apps and inputs, and a box for typing
usernames and passwords into the TV. Typing handles English letters, numbers and symbols only. Some sign-in screens, such as YouTube's,
have their own keyboard and can turn capitals and symbols into plain letters and numbers. Use that
app's sign in with your phone option there.

Android TV's own remote service refuses anything running on the TV, so Dekho presses keys through
the TV's debugging connection. That needs USB debugging left on.

**Privacy:** the remote page is plain web traffic on your home Wi-Fi, not encrypted, so what you type
could be read by someone already on your network. The address has a private code in it: anyone with
the address can control the TV. Avoid using it on shared Wi-Fi.

## Building

`gradle assembleRelease` with `ANDROID_HOME` set and `ANDROID_SDK_ROOT` unset.

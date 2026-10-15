# Slingshot Lamp for Android

*Nobody touch the lamp.*

A Jetpack Compose port of Kamran Ahmed's [Slingshot Lamp](https://kamran.fyi/lamp) web toy. All credit for the idea, the look and the original physics, drawing and sound design goes to him. This is a straight port of his canvas / Web Audio code to Android.

- **Flip the wall switch.** Flip it too often and the bulb overheats, flickers, and blows.
- **Pull back the slingshot** and let go to hit the shade, the bulb or the switch.
- **Drag the shade** to swing the lamp.

The room is dark except for the lamp's cone of light, so the headline is only readable where the lamp shines.

## Files

| File | What it does |
|---|---|
| `LampSim.kt` | Physics and game state: pendulum lamp, slingshot spring, pebbles, glass shards, bulb heat/pop. Saves to a `Bundle`. |
| `LampRenderer.kt` | Draws everything on an `android.graphics.Canvas`, including the darkness layer with the light cone cut out of it. |
| `LampSound.kt` | Generated sound effects (filtered noise + pitch-swept tones) mixed on an audio thread and played with `AudioTrack`. No audio files. |
| `LampScreen.kt` | Compose UI: layer stack, touch input, frame loop, wall switch, counters, state restoration via `rememberSaveable`. |
| `MainActivity.kt` | Edge-to-edge activity hosting `LampScreen()`. |
| `ic_launcher_*.xml` | Adaptive launcher icon (background, foreground, monochrome for themed icons). |

## Using it

1. Create an empty Compose project. Requires **minSdk 29**.
2. Put the `.kt` files in your source set. They use the packages `com.inn.lampdemo` and `com.inn.lampdemo.lamp`, so rename them to match your project.
3. Dependencies: the usual Compose BOM + `material3`, `activity-compose`, and `androidx.core:core-ktx` (for `withTranslation` / `withScale`).
4. Optional: put the `ic_launcher_*.xml` files in `res/drawable/` and reference them from `res/mipmap-anydpi/ic_launcher.xml`:

   ```xml
   <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
       <background android:drawable="@drawable/ic_launcher_background" />
       <foreground android:drawable="@drawable/ic_launcher_foreground" />
       <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
   </adaptive-icon>
   ```
5. Optional: give the activity theme a dark window background (`#050607`) to avoid a white flash on launch.

## How it works (short version)

- **Fixed-step physics:** the simulation steps at 120 Hz whatever the display refresh rate. All units are dp, so the original web pixel constants carry over directly.
- **Lighting:** an offscreen layer is filled with near-black, a light cone is erased from it with `PorterDuff.DST_OUT`, and the whole layer is blurred (`RenderEffect`, Android 12+) for soft edges. The headline sits underneath the layer.
- **Sound:** each effect is built from RBJ biquad-filtered noise bursts and oscillators with exponential envelopes, mirroring the original Web Audio graph.
- **State:** switch, bulb, heat, counters, lamp swing, pebbles and shards survive rotation and process death. Debris is moved to fit the new screen size.

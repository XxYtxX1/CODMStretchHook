CODM Stretch Hook v2.0 (LSPosed module)
=======================================
Makes the screen fill the whole panel (stretch) instead of black bars when the
display size uses a different aspect ratio. Pair with the CODM Ultra Stretch module.

Build: push this folder to a GitHub repo -> Actions tab -> "Build CODM Stretch Hook"
-> download CODM-Stretch-Hook.apk from the run's Artifacts or from Releases.
(Or: gradle :app:assembleDebug with Android SDK 34 + JDK 17.)

Use: install the APK -> LSPosed -> Modules -> enable "CODM Stretch Hook" with the
System Framework scope -> reboot. The module's WebUI shows 'Fill hook: Loaded'.
Hook status file: /data/system/codm_stretch_hook.txt
Kill switch: create /data/system/codm_stretch_off (the module's Fill toggle does this).

What changed in v2.0
- The projection method is found by any known name (setProjectionLocked,
  setProjectionInTransactionLocked, ...). If none exists, any "projection" method
  that takes two Rect arguments is hooked instead.
- The status file says what happened:
    msg=  the method that was hooked, or the projection methods that were seen
    sig=  the method and argument types of the first call
    skip= why the last call was not changed: applied, no-offset (no bars),
          not-internal, off, no-rects, bad-rect, error <Type>

Reading the status after you stretch CODM
- skip=applied with events>0: the hook is filling the screen.
- skip=no-offset while stretched: the hook sees no bars, so the bars come from elsewhere.
- msg= says no projection method matched: its list shows the names this Android build uses.

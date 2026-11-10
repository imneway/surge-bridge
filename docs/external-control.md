# External control

Other apps, Tasker or adb can start, stop or toggle the proxy without opening the UI.
`ControlActivity` is transparent, excluded from recents and finishes immediately.

| Action | Effect |
|---|---|
| `io.github.liuw.surgebridge.action.START` | Start with the selected profile |
| `io.github.liuw.surgebridge.action.STOP` | Stop |
| `io.github.liuw.surgebridge.action.TOGGLE` | Stop if running, otherwise start |

Component: `<package>/io.nekohasekai.sfa.bg.ControlActivity`, where `<package>` is
`io.github.liuw.surgebridge` (release) or `io.github.liuw.surgebridge.debug`. Actions are the same for both.

```bash
adb shell am start -n io.github.liuw.surgebridge/io.nekohasekai.sfa.bg.ControlActivity \
    -a io.github.liuw.surgebridge.action.START
```

```kotlin
val pkg = "io.github.liuw.surgebridge"
context.startActivity(
    Intent("io.github.liuw.surgebridge.action.START")
        .setClassName(pkg, "io.nekohasekai.sfa.bg.ControlActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)
```

In VPN mode, if Android has not granted VPN permission to Surge Bridge (or another VPN app
was prepared last), START shows the system consent dialog and starts after approval.
An always-on VPN held by another app opens the main UI instead.

The activity is exported without a permission, so any installed app can start or stop the proxy.

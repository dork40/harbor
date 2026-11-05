# Harbor

Your NAS, at home and away. Harbor is an Android app that connects to your NAS over your home network and switches to Tailscale when you leave. If neither is reachable, it stops and waits instead of hammering the connection.

> **This project is vibe coded.** It was written almost entirely by an AI (Claude), directed in plain language by a person who wanted the app to exist. Nobody has reviewed the code line by line. It works for its author, but expect rough edges and read the code before you trust it with anything important.

## Features

- **Automatic routing:** home network when the VPN is off, Tailscale when it's on. Re-checks only when your network changes
- **Multiple NAS boxes,** each with its own addresses, sign-in, shortcuts and pinned certificate
- **Live dashboard:** CPU, memory, network, storage, disk temperatures
- **Apps:** open any NAS app in an in-app browser, and start, stop, restart or update it where the NAS allows
- **Files:** browse shares, open photos, video and text in the app, upload and download with progress notifications
- **Tidy by itself:** uninstall an app on the NAS and its Harbor shortcut goes too. Stopped apps stay
- **Certificate pinning:** the NAS's TLS certificate is remembered the first time it answers, so another device at the same IP on someone else's network is rejected
- Color schemes, and a monochrome black mode with glass blur

## Supported systems

| System | What works | Tested against |
|---|---|---|
| TrueNAS SCALE / Community | Everything above | Real hardware |
| umbrelOS | Apps, stats, updates, logs, power | Mock server, plus its public API on a real device |
| ZimaOS, CasaOS | Apps, stats, logs, power, browsing and downloading files | Mock server |
| Proxmox VE | Storage, VMs and containers as apps, power | Mock server |
| QNAP QTS / QuTS hero | Stats, power | Mock server |
| Synology DSM | Stats, Docker, files, power | Mock server |
| OpenMediaVault | Stats, drives, power | Mock server |
| Unraid 7.2+ | Array, Docker containers | Mock server |
| ASUSTOR, TerraMaster, UGREEN, Rockstor, XigmaNAS, anything else | Online status, web UI, shortcuts | Generic |

Everything except TrueNAS is **beta**. Those backends were written from each system's published API or source and tested against `tools/mock-nas.py`, not real boxes. Reports and fixes are very welcome.

## Build

You need JDK 17 and the Android SDK (platform 35).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Release builds are signed with your local debug key, so sign it yourself if you plan to distribute it.

`python3 tools/mock-nas.py` starts fake servers for the beta backends. See the file for ports and the test password.

## Privacy

Harbor has no accounts, analytics or servers of its own. It talks only to the NAS addresses you enter, plus a public CDN for app icons. Passwords and API keys are encrypted on the phone with the Android Keystore.

## License

GPL-3.0. See [LICENSE](LICENSE).

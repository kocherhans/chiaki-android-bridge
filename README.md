# chiaki-android-bridge

GPL v3 compliance source for the chiaki-ng JNI bridge used in **Intent Controller**.

Intent Controller routes physical gamepad input to a PlayStation console over
the network using [chiaki-ng](https://github.com/streetpea/chiaki-ng) (GPL v3).
Because the app links against libchiaki, the combined work is subject to GPL v3
copyleft terms. This repository satisfies the "complete corresponding source"
requirement.

## Contents

| Path | Description |
|------|-------------|
| `chiaki-jni.c` | JNI bridge — implements `nativeConnect`, `nativeSendInput`, `nativeDisconnect`, `nativeRegister` |
| `utils.h` | Shared utility header |
| `CMakeLists.txt` | Android NDK build script (CMake 3.22+, NDK 26, arm64-v8a) |
| `curl/` | Prebuilt libcurl static library (arm64-v8a) |
| `kotlin/PsRemotePlayOutput.kt` | Kotlin session manager — drives the JNI bridge |
| `kotlin/PsRemotePlayConfigActivity.kt` | Kotlin pairing UI |
| `chiaki/` | chiaki-ng submodule (see `CHIAKI_VERSION`) |
| `android_openssl/` | KDAB android-openssl submodule |
| `json-c/` | json-c submodule |
| `miniupnp/` | miniupnp submodule |

## Building

Requires Android NDK 26 and CMake 3.22+. Place this directory at
`app/src/main/cpp/` in an Android project and add to `build.gradle.kts`:

```kotlin
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

Clone with submodules:

```bash
git clone --recurse-submodules https://github.com/kocherhans/chiaki-android-bridge.git
```

## License

Copyright © 2026 James Kocherhans

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

See [LICENSE](LICENSE) for the full GPL v3 text.

Third-party dependency licences are listed in [NOTICES](NOTICES).

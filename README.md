# dcRPCspoofer (Discord RPC for Android)

This project provides a native Android implementation of the legacy `discord-rpc` C SDK. It is designed to be a replacement for desktop-focused Discord RPC libraries in Android apps (such as `java-discord-rpc`), enabling Rich Presence functionality on mobile devices when paired with an IPC bridge.

## Features

- **Multi-ABI Support**: Prebuilt binaries and build configuration for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- **Android Optimized IPC**: Uses the Linux abstract socket namespace (`\0discord-ipc-0..9`), matching Discord's Linux behavior but adapted for Android.
- **Modern Android Compatibility**: 
  - **16 KB Page Alignment**: Fully compatible with Android 15 and future hardware.
  - **Symbol Isolation**: Internal C++ symbols are hidden via `-fvisibility=hidden` to prevent conflicts with libraries like LWJGL or JNA.
  - **Static STL**: Linked against `c++_static` to minimize runtime dependencies.
- **JNA Ready**: Struct layouts (`DiscordRichPresence`, `DiscordUser`, etc.) are byte-matched to the official SDK, ensuring safe marshaling with JNA `Structure` classes.

## Project Structure

- `app/src/main/cpp/`: Contains the native C++ source code.
  - `discord-rpc/`: Patched source of the official `discord-rpc` library.
  - `thirdparty/rapidjson/`: Header-only JSON library for IPC frames.
- `app/src/main/jniLibs/`: Prebuilt `.so` binaries for easy integration.

## Getting Started

### Prerequisites
- Android SDK & NDK (Side-by-side)
- Android Studio / Gradle

### Building
The project is set up with Gradle and CMake. To build the native libraries:
```bash
./gradlew :app:assembleDebug
```
The output `.so` files will be placed in `app/build/intermediates/cmake/...` or can be found in `app/src/main/jniLibs` if using prebuilts.

### Usage in Code
Load the library using JNA as you would on desktop:

```java
public interface DiscordRPC extends Library {
    DiscordRPC INSTANCE = Native.load("discord-rpc", DiscordRPC.class);
    // ... method definitions
}
```

On Android, ensure `libdiscord-rpc.so` is included in your APK's `lib/` directory. JNA will automatically resolve the library from the system's native library path.

## Important Note on Android IPC

Stock Android devices do **not** have the Discord desktop app listening on Unix sockets. For `Discord_Initialize` to connect, you must run a bridge or proxy on the device that exposes the `discord-ipc-0` abstract socket. Without a listener, the library will initialize successfully but `Discord_RunCallbacks` will quietly retry in the background without crashing the app.

## License
This project uses code from the [official discord-rpc](https://github.com/discord/discord-rpc) repository, licensed under the MIT License.

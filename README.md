# dnd-game

Kotlin Multiplatform / Compose Multiplatform: Android, iOS, Desktop (JVM), Web (Wasm).

## Project structure

- `shared` вЂ” shared Compose UI, resources, logic, and platform implementations (`androidMain`, `iosMain`, `jvmMain`, `wasmJsMain`). Produces the `Shared` framework for iOS.
- `androidApp` вЂ” Android application, manifest, launcher resources, and `MainActivity`. Uses AGP's built-in Kotlin support.
- `desktopApp` вЂ” JVM application entry point, Compose Hot Reload, and native distribution packaging.
- `webApp` вЂ” browser entry point and HTML shell for Kotlin/Wasm.
- `iosApp` вЂ” Xcode application and SwiftUI host for the shared UI.
- `gradle/libs.versions.toml` вЂ” dependency and plugin versions.

Application modules depend on `shared`. Only application modules define executable entry points for Android, Desktop, and Web. The iOS UIKit bridge remains in `shared` so Swift can call it from the generated framework.

## Requirements

Use JDK 21 for Gradle and Desktop development (including Compose Hot Reload). Install Android SDK 37 to build Android. Android minimum SDK is 24; target SDK is 36. Compose UI 1.12.1 and Lifecycle 2.11.0 require compile SDK 37.

For iOS, use macOS and Xcode 26.4. Set the signing team in the Xcode project before running on a device.

The web application requires a browser with WasmGC support. This project targets Kotlin/Wasm only, without a JavaScript fallback.

## Build and run

Commands below use the Windows wrapper. On macOS/Linux, replace `.\gradlew.bat` with `./gradlew`.

```powershell
# Android debug APK
.\gradlew.bat :androidApp:assembleDebug

# Desktop
.\gradlew.bat :desktopApp:run

# Desktop with Compose Hot Reload
.\gradlew.bat :desktopApp:hotRun

# Web development server
.\gradlew.bat :webApp:wasmJsBrowserDevelopmentRun

# Web production distribution
.\gradlew.bat :webApp:wasmJsBrowserDistribution

# Check the three platforms available on Windows
.\gradlew.bat :desktopApp:classes :webApp:wasmJsBrowserDistribution :androidApp:assembleDebug
```

The production web files are in `webApp/build/dist/wasmJs/productionExecutable`. Serve this directory over HTTP; configure the server to serve `.wasm` files as `application/wasm`.

Open `iosApp/iosApp.xcodeproj` in Xcode to build and run iOS. Its build phase invokes `:shared:embedAndSignAppleFrameworkForXcode`.

## Version policy

Kotlin 2.4.21, Compose Multiplatform 1.12.2, AGP 9.3.1, and Gradle 9.7.0 follow the published KMP compatibility range. AGP and Gradle are intentionally kept within that range rather than independently updated to their newest releases.

Material3 has a separate release cycle. This project uses `1.12.0-alpha03`, the version listed for Compose Multiplatform 1.12.2. Android Compose UI tooling uses Jetpack Compose 1.12.1 to match Compose Multiplatform's Android artifacts; there is no separate Android BOM overriding shared UI dependencies.

References:

- [Recommended KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Compose compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [Compose Multiplatform 1.12.2](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html)

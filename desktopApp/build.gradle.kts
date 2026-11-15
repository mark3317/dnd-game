import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
}

compose.desktop {
    application {
        mainClass = "ru.markn.dnd.MainKt"
        nativeDistributions {
            // Build a review copy without overwriting an application currently being tested.
            providers.gradleProperty("dnd.desktopCheckpoint").orNull?.let { checkpoint ->
                require(checkpoint.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid desktop checkpoint name" }
                outputBaseDir.set(layout.buildDirectory.dir("compose/checkpoints/$checkpoint"))
            }
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ru.markn.dnd"
            packageVersion = "1.0.0"
        }
    }
}

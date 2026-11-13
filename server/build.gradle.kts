plugins {
    alias(libs.plugins.kotlinJvm)
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(projects.game)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization)
    testImplementation(kotlin("test"))
}
application { mainClass.set("ru.markn.dnd.server.ServerKt") }

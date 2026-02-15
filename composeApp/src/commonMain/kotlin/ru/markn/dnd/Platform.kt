package ru.markn.dnd

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
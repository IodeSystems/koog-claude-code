plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation(libs.koog.prompt.executor.clients)
    implementation(libs.koog.agents.tools)
    implementation(libs.koog.prompt.model)
    implementation(libs.koog.prompt.llm)

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)
    implementation(libs.logback)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

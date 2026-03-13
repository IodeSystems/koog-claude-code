plugins {
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("ai.koog.x.claude.sample.MainKt")
}

dependencies {
    implementation(project(":koog-claude-code"))
    implementation(libs.koog.agents)
    implementation(libs.clikt)
    implementation(libs.tshell)
    implementation(libs.kotlin.logging)
    implementation(libs.logback)
}

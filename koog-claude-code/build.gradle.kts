plugins {
    kotlin("plugin.serialization")
    `maven-publish`
    signing
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

    testImplementation(libs.testng)
}

tasks.test {
    useTestNG()
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("koog-claude-code")
                description.set(project.description)
                url.set("https://github.com/IodeSystems/koog-claude-code")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://www.opensource.org/licenses/mit-license.php")
                    }
                }
                developers {
                    developer {
                        id.set("nthalk")
                        name.set("Carl Taylor")
                        email.set("carl@etaylor.me")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:IodeSystems/koog-claude-code.git")
                    developerConnection.set("scm:git:git@github.com:IodeSystems/koog-claude-code.git")
                    url.set("https://github.com/IodeSystems/koog-claude-code")
                }
            }
        }
    }
}

val requireSign = !rootProject.hasProperty("skipSigning")
signing {
    isRequired = requireSign
    useGpgCmd()
    sign(publishing.publications)
}
val signingTasks = tasks.withType<Sign>()
signingTasks.configureEach {
    onlyIf { requireSign }
}
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(signingTasks)
}

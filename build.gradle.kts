import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0.0"

apply(plugin = "kotlin")

repositories {
    mavenCentral()
}

dependencies {
    // Telegram Bot API
    implementation("org.telegram:telegrambots:6.9.7.1")
    
    // Logging
    implementation("org.apache.logging.log4j:log4j-core:2.23.1")
    implementation("org.apache.logging.log4j:log4j-api-kotlin:1.2.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    
    // Ktor for WebSocket and HTTP client
    implementation("io.ktor:ktor-client-websockets:2.3.9")
    implementation("io.ktor:ktor-client-cio:2.3.9")
    implementation("io.ktor:ktor-io:2.3.9")
    
    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    
    // Jackson for JSON processing
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }
}

application {
    mainClass.set("MainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Ensure the JAR manifest includes the main class for Docker/java -jar
tasks.withType<Jar> {
    manifest {
        attributes("Main-Class" to "MainKt")
    }
}

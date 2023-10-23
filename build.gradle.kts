import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.10"
    application
}

group = "org.example"
version = ""

apply(plugin = "kotlin")

repositories {
    mavenCentral()
}

dependencies {
    implementation ("org.telegram:telegrambots:6.8.0")
    implementation("name.remal.gradle-plugins.lombok:lombok:2.2.1")
    // https://mvnrepository.com/artifact/org.apache.logging.log4j/log4j-core
    implementation("org.apache.logging.log4j:log4j-core:2.20.0")

    implementation("org.apache.logging.log4j:log4j-api-kotlin:1.2.0")
    implementation("org.apache.logging.log4j:log4j-api-kotlin-parent:1.2.0")
    implementation("org.slf4j:slf4j-api:2.0.7")
    // https://mvnrepository.com/artifact/io.ktor/ktor-client-core-jvm
    implementation("io.ktor:ktor-client-websockets:2.3.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("io.ktor:ktor-io:2.3.2")
    // https://mvnrepository.com/artifact/io.ktor/ktor-client-cio-jvm
    implementation("io.ktor:ktor-client-cio-jvm:2.3.5")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.14.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")

    //implementation("org.apache.logging.log4j:log4j-api-kotlin:1.2.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}

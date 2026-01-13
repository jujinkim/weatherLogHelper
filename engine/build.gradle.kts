plugins {
    kotlin("jvm") version "1.9.24"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "internal.wlh"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
}

application {
    mainClass.set("wlh.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks {
    shadowJar {
        archiveBaseName.set("wlh-engine")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())
        isZip64 = true
    }

    build {
        dependsOn(shadowJar)
    }
}

plugins {
    id("net.fabricmc.fabric-loom") version "1.19.4"
    java
}

val minecraftVersion = "26.3"
val loaderVersion = "0.20.1"
val fabricApiVersion = "0.166.2+26.3"

base {
    archivesName.set("stonyperms-bridge-fabric")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("me.lucko:fabric-permissions-api:0.7.0")

    implementation(project(":core"))
    include(project(":core"))
    include("com.google.re2j:re2j:1.8")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

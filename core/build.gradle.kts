plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

dependencies {
    api("com.google.code.gson:gson:2.15.0")
    api("com.google.re2j:re2j:1.8")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    implementation("com.zaxxer:HikariCP:7.1.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:4.0.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.postgresql:postgresql:42.7.14")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

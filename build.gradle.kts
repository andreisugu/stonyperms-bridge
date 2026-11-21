val releaseVersion = providers.gradleProperty("releaseVersion").getOrElse("0.2.0-SNAPSHOT")

allprojects {
    group = "dev.stonyperms"
    version = releaseVersion
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: pixel-sampling recognisers that work on any PixelSource.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":solver"))
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Regenerates the synthetic screenshot fixtures in src/test/resources/fixtures.
tasks.register<JavaExec>("generateFixtures") {
    group = "verification"
    description = "Renders synthetic screenshot fixtures for the vision tests"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.gridhelper.vision.testing.FixtureGenerator")
    args(layout.projectDirectory.dir("src/test/resources/fixtures").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

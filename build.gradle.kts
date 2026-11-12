plugins {
    java
    application
}

group = "dev.mcspb"
version = "0.1.0"

repositories { mavenCentral() }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application { mainClass = "dev.mcspb.cli.Main" }
tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Run the loopback API for the Windows desktop app"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.mcspb.api.ApiServerMain"
}

plugins {
    base
}

// The single source of the release version: the mod metadata, JAR manifests and artifact names derive from it.
val releaseVersion = "1.0.0"

subprojects {
    group = "dev.wiktorfila.filasignal"
    version = releaseVersion

    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(25)
            options.compilerArgs.add("-Xlint:all")
        }

        tasks.withType<Jar>().configureEach {
            manifest.attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "License" to "GPL-3.0-only",
            )
        }
    }
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

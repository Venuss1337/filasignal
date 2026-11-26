plugins {
    application
}

dependencies {
    implementation(project(":filascript-core"))
}

application {
    mainClass.set("dev.wiktorfila.filasignal.cli.FilaScriptCli")
}

// The downloadable distribution: bin/ launchers, lib/ JARs, the license, notices, usage and an example.
distributions {
    main {
        contents {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("filascript-cli/README.md"))
            from(rootProject.file("examples/hello.fsig")) { into("examples") }
        }
    }
}

plugins {
    `java-library`
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

// Interpreter throughput; see tools/benchmarks/Run-Benchmarks.ps1. -PfilaBenchOut=DIR, -PfilaBenchIterations=N
val languageBenchmark by tasks.registering(Test::class) {
    description = "Measures interpreter throughput outside Minecraft."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    outputs.upToDateWhen { false }
    systemProperty("filasignal.benchmark.out", providers.gradleProperty("filaBenchOut")
        .getOrElse(layout.buildDirectory.dir("benchmark").get().asFile.absolutePath))
    systemProperty("filasignal.benchmark.iterations", providers.gradleProperty("filaBenchIterations").getOrElse("200"))
}

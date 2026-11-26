plugins {
    id("net.fabricmc.fabric-loom") version "1.17.21"
}

val minecraftVersion = "26.3"
val loaderVersion = "0.20.1"
val fabricApiVersion = "0.166.2+26.3"

loom {
    splitEnvironmentSourceSets()
    accessWidenerPath.set(file("src/main/resources/filasignal-editor.classtweaker"))

    mods {
        create("filasignal") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets["client"])
        }
    }
}

repositories {
    maven("https://staging.alexiil.uk/maven/") {
        content { includeGroup("io.github.cottonmc") }
    }
}

dependencies {
    implementation("io.github.cottonmc:LibGui:18.0.1+26.3-rc-2") { isTransitive = false }
    include("io.github.cottonmc:LibGui:18.0.1+26.3-rc-2") { isTransitive = false }
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation(project(":filascript-core"))
    include(project(":filascript-core"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("filasignal-gametest")
        enableClientGameTests.set(providers.gradleProperty("filaClientTests").map(String::toBoolean).orElse(false))
    }
}

// Server GameTests assume a fresh world; devices left in the saved test world would otherwise accumulate across runs.
tasks.matching { it.name == "runGameTest" }.configureEach {
    doFirst {
        delete(layout.projectDirectory.dir("build/run/gameTest/world"))
    }
    // Optional, for verification scripts and investigations:
    //   -PfilaTestFilter=GLOB     run only matching tests, such as filasignal-gametest:*chunk_unload*
    //   -PfilaTestReport=FILE     write a JUnit XML report (absolute path)
    //   -PfilaTrace=true          log program activation, detachment and unloading
    //   -PfilaBenchProfile=NAME   run the server benchmark (tools/benchmarks/Run-Benchmarks.ps1 sets these)
    //   -PfilaBenchOut=DIR, -PfilaBenchWarmup=TICKS, -PfilaBenchTicks=TICKS, -PfilaHeap=SIZE
    val options = this as JavaForkOptions
    providers.gradleProperty("filaTestFilter").orNull?.let { options.systemProperty("fabric-api.gametest.filter", it) }
    providers.gradleProperty("filaTestReport").orNull?.let { options.systemProperty("fabric-api.gametest.report-file", it) }
    providers.gradleProperty("filaTrace").orNull?.let { options.systemProperty("filasignal.traceLifecycle", it) }
    providers.gradleProperty("filaBenchProfile").orNull?.let { options.systemProperty("filasignal.benchmark.profile", it) }
    providers.gradleProperty("filaBenchOut").orNull?.let { options.systemProperty("filasignal.benchmark.out", it) }
    providers.gradleProperty("filaBenchWarmup").orNull?.let { options.systemProperty("filasignal.benchmark.warmup", it) }
    providers.gradleProperty("filaBenchTicks").orNull?.let { options.systemProperty("filasignal.benchmark.ticks", it) }
    providers.gradleProperty("filaHeap").orNull?.let { options.maxHeapSize = it }
}

// The release JAR is filasignal-<version>+mc<minecraft>.jar. As in the Fabric example template, the license and the
// third-party notices are shipped inside it under mod-specific names.
tasks.jar {
    archiveBaseName.set("filasignal")
    archiveVersion.set("${project.version}+mc$minecraftVersion")
    from(rootProject.file("LICENSE")) {
        rename { "LICENSE_filasignal" }
    }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) {
        rename { "THIRD-PARTY-NOTICES_filasignal.md" }
    }
}

tasks.processResources {
    val metadata = mapOf(
        "version" to project.version,
        "minecraftVersion" to minecraftVersion,
        "loaderVersion" to loaderVersion,
        "fabricApiVersion" to fabricApiVersion,
    )
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") {
        expand(metadata)
    }
    // Beginner starter programs are the verified examples, packaged for the in-game editor.
    from(rootProject.file("examples")) {
        include("starter-*.fsig")
        into("assets/filasignal/starters")
    }
}

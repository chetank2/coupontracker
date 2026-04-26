plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.example.coupontracker.tools.ExtractionToolCli")
}

sourceSets {
    main {
        kotlin {
            // Source-share the pure-JVM preprocessing package from the app module.
            // Both this module and :app compile the same .kt files; parity is by
            // construction.
            srcDir("../app/src/main/kotlin/com/example/coupontracker/preprocessing")
            srcDir("src/main/kotlin")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.register<Jar>("extractionToolJar") {
    archiveBaseName.set("extraction-tool")
    manifest { attributes["Main-Class"] = "com.example.coupontracker.tools.ExtractionToolCli" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

kotlin {
    jvmToolchain(17)
}

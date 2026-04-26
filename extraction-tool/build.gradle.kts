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
            // Source-share only pure-JVM app code. Do not pull broad Android
            // packages into this module; Android leakage here breaks the Mac tool.
            srcDir("../app/src/main/kotlin")
            srcDir("src/main/kotlin")
            include("com/example/coupontracker/preprocessing/*.kt")
            include("com/example/coupontracker/llm/CouponSchemaKeys.kt")
            include("com/example/coupontracker/contract/*.kt")
            include("com/example/coupontracker/schema/SchemaDefinition.kt")
            include("com/example/coupontracker/schema/CouponSchema.kt")
            include("com/example/coupontracker/schema/PromptGenerator.kt")
            include("com/example/coupontracker/tools/*.kt")
        }
    }
}

dependencies {
    implementation("org.json:json:20231013")
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

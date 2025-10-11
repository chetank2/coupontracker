#!/usr/bin/env kotlin

/**
 * Regenerate GBNF grammar from schema definition
 * 
 * Usage:
 *   kotlin scripts/regenerate_grammar.kts
 * 
 * Or via Gradle:
 *   ./gradlew regenerateGrammar
 */

@file:Import("../app/src/main/kotlin/com/example/coupontracker/schema/SchemaDefinition.kt")
@file:Import("../app/src/main/kotlin/com/example/coupontracker/schema/CouponSchema.kt")
@file:Import("../app/src/main/kotlin/com/example/coupontracker/schema/GBNFGenerator.kt")

import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.GBNFGenerator
import java.io.File

println("Regenerating GBNF grammar from schema...")

// Generate grammar
val schema = CouponSchema.SCHEMA
val grammar = GBNFGenerator.generate(schema)

// Validate generated grammar
if (!GBNFGenerator.validateGrammar(grammar)) {
    System.err.println("ERROR: Generated grammar is invalid!")
    kotlin.system.exitProcess(1)
}

// Write to assets
val outputPath = File("app/src/main/assets/coupon_schema.gbnf")
outputPath.parentFile.mkdirs()
outputPath.writeText(grammar)

println("✓ Grammar generated: ${outputPath.absolutePath}")
println("✓ Grammar size: ${grammar.length} bytes")
println("✓ Schema version: ${schema.version}")
println()
println("Grammar preview:")
println("=" .repeat(60))
println(grammar.lines().take(20).joinToString("\n"))
if (grammar.lines().size > 20) {
    println("... (${grammar.lines().size - 20} more lines)")
}
println("=" .repeat(60))


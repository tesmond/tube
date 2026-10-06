plugins {
    `kotlin-dsl`
}

// Keep in step with agp in gradle/libs.versions.toml.
val agpVersion = "9.4.0"

dependencies {
    // Full AGP (not just gradle-api) on buildSrc's classpath so the Android plugin and the instrumentation API
    // load from the same classloader; compileOnly caused NoClassDefFoundError for AsmClassVisitorFactory at runtime.
    implementation("com.android.tools.build:gradle:$agpVersion")
    implementation("org.ow2.asm:asm-commons:9.7")
}

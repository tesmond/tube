plugins {
    `kotlin-dsl`
}

// Keep in step with agp in gradle/libs.versions.toml.
val agpVersion = "9.4.0"

dependencies {
    compileOnly("com.android.tools.build:gradle-api:$agpVersion")
    implementation("org.ow2.asm:asm-commons:9.7")
}

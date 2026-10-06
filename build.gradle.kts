plugins {
    // com.android.application is applied versionless in :app; AGP comes from buildSrc's classpath.
    alias(libs.plugins.kotlin.compose) apply false
}

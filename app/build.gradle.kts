import java.util.Properties

plugins {
    jacoco
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Customer ApiKey from local.properties (GWENT_API_KEY=...) or -PGWENT_API_KEY. Never committed.
val gwentApiKey: String = run {
    // Default verification must never bake a developer's local key into its build artefacts.
    if (providers.gradleProperty("GMC_VERIFY").orNull == "true") return@run ""
    val p = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) }
    p.getProperty("GWENT_API_KEY") ?: (project.findProperty("GWENT_API_KEY") as String?) ?: ""
}

android {
    namespace = "org.bayton.gmc"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.bayton.gmc"
        minSdk = 28
        targetSdk = 37
        versionCode = 20
        versionName = "3.0"

        // OAuth: Schooble B2C public-client compatibility configuration.
        // These are public identifiers, not secrets.
        buildConfigField("String", "B2C_AUTHORITY",
            "\"https://SchoobleAD.b2clogin.com/tfp/SchoobleAD.onmicrosoft.com/B2C_1_2024_Paritor_SignIn_MFA/\"")
        buildConfigField("String", "B2C_CLIENT_ID", "\"98279e33-f96d-41af-b983-2efe03938c8c\"")
        buildConfigField("String", "API_BASE",
            "\"https://paritorxperiosapiservicev27-bcexaegzbgfde9br.uksouth-01.azurewebsites.net/\"")

        // Keyless baseline. Build types below can override this for personal-device testing;
        // release currently embeds a supplied local key and must not be distributed.
        buildConfigField("String", "DEV_API_KEY", "\"\"")
        buildConfigField("Boolean", "USE_MOCK", "false")
    }

    buildTypes {
        debug {
            // Keep a reproducible coverage report available to both local checks and CI.
            enableUnitTestCoverage = true
            // -PGMC_LIVE=true -> hit the REAL Paritor API (default debug uses the seeded mock).
            val live = providers.gradleProperty("GMC_VERIFY").orNull != "true" &&
                (project.findProperty("GMC_LIVE") as String?) == "true"
            if (live) {
                buildConfigField("Boolean", "USE_MOCK", "false")
                // API_BASE stays the real endpoint from defaultConfig.
            } else {
                buildConfigField("String", "API_BASE", "\"http://10.0.2.2:8099/\"")
                buildConfigField("Boolean", "USE_MOCK", "true")
            }
            buildConfigField("String", "DEV_API_KEY", "\"$gwentApiKey\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a personal build installs without a release keystore.
            // Replace with a real release signing config before any wider distribution.
            signingConfig = signingConfigs.getByName("debug")
            // TEMPORARY personal-device stopgap. The customer key
            // is baked into the release whenever gitignored local.properties provides it, so an Android
            // Studio "Build APK" / "Generate Signed APK" release includes it with no command-line flag.
            // The key is never committed; a machine without it (CI, a clean checkout) builds keyless.
            // Do NOT distribute a baked build.
            if (gwentApiKey.isNotBlank()) {
                buildConfigField("String", "DEV_API_KEY", "\"$gwentApiKey\"")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.androidx.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.androidx.browser)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    // WorkManager's DB is inspected in integration tests; match its runtime dependency.
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.mockito)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Robolectric loads Android-backed app classes through its sandbox classloader. Without this,
// JaCoCo silently reports those exercised classes as uncovered, producing a misleading baseline.
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

// Android Studio/Gradle's conventional check also retains coverage, not only the CI wrapper.
val verifyCoverage = tasks.register<Exec>("verifyCoverage") {
    group = "verification"
    description = "Check the reviewed coverage floors after all debug unit tests."
    dependsOn("createDebugUnitTestCoverageReport")
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/check-coverage.py")
}
tasks.named("check") { dependsOn(verifyCoverage) }

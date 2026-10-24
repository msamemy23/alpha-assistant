import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
// -PfriendsBuild=true makes a side-by-side beta copy: its own app id (so it installs next to another copy with its own, empty data), phone control off,
// no shop details or project IDs built in, and "Beta" in its name. See Profile.kt.
val friendsBuild = project.hasProperty("friendsBuild")
// The owner's own business details come from an untracked owner.properties file next to the root build.gradle.kts (see owner.properties.example).
// A build without that file, and the friends build, are generic: no shop name, address, phone or project IDs anywhere in the app file.
val ownerProps = Properties()
rootProject.file("owner.properties").takeIf { it.exists() }?.inputStream()?.use { ownerProps.load(it) }
android {
    namespace = "com.alphainternational.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.alphainternational.assistant" + (if(friendsBuild) ".beta" else "")
        // The launcher shortcuts name the app they open (res/xml/shortcuts.xml), and the friends copy has its own id.
        minSdk = 30
        targetSdk = 35
        versionCode = 83
        versionName = "0.20.0" +(if(friendsBuild) "-beta" else "")
        buildConfigField("boolean", "FRIENDS", friendsBuild.toString())
        // The owner's own business details live only here, and are empty in the friends build so they are not inside that app file at all.
        fun owner(key: String) = "\"" + (if(friendsBuild) "" else (ownerProps.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")) + "\""
        buildConfigField("String", "SHOP_NAME", owner("shop.name"))
        buildConfigField("String", "SHOP_ADDRESS", owner("shop.address"))
        buildConfigField("String", "SHOP_PHONE", owner("shop.phone"))
        buildConfigField("String", "SUPABASE_PROJECT", owner("supabase.project"))
        buildConfigField("String", "SUPABASE_ORG", owner("supabase.org"))
        manifestPlaceholders["appLabel"] = if(friendsBuild) "Alpha Assistant Beta" else "Alpha Assistant"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if(System.getenv("ALPHA_SIGNING_STORE")!=null) {
            create("alphaPersonal") {
                storeFile=file(System.getenv("ALPHA_SIGNING_STORE"))
                storePassword=System.getenv("ALPHA_SIGNING_PASSWORD")
                keyAlias="alpha"
                keyPassword=System.getenv("ALPHA_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            // Upgrade the existing isolated test install without replacing its signing identity or clearing data.
            signingConfigs.findByName("alphaPersonal")?.let { signingConfig=it }
        }
        release {
            isMinifyEnabled=!project.hasProperty("alphaFastBuild")
            isShrinkResources=isMinifyEnabled
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
            signingConfig=signingConfigs.findByName("alphaPersonal")
        }
    }
    splits {
        abi {isEnable=true;reset();include("arm64-v8a","x86_64");isUniversalApk=false}
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("androidx.media3:media3-transformer:1.8.0")
    implementation("androidx.media3:media3-effect:1.8.0")
    implementation("androidx.media3:media3-common:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

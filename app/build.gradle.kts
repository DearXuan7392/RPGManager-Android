import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release 签名信息放在工程根目录的 keystore.properties（不进版本库）。
// 文件不存在时 release 仍可构建，只是输出未签名包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.isFile

// 把 git submodule 里的公共核心复制进 assets，供内嵌 Node 直接 require('./core')
val coreAssetsRoot = layout.buildDirectory.dir("generated/core-assets")
val copySharedCore = tasks.register<Copy>("copySharedCore") {
    from(rootProject.file("core"))
    include("index.js", "package.json")
    into(coreAssetsRoot.map { it.dir("nodehost/core") })
}

android {
    namespace = "com.dearxuan.rpgmanager"
    compileSdk {
        version = release(37)
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(coreAssetsRoot.get().asFile)
        }
    }

    defaultConfig {
        applicationId = "com.dearxuan.rpgmanager"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 内嵌的 Node 运行时（libnode.so）目前只提供 arm64-v8a 的预编译包
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // libnode.so 有 48MB，放在 APK 里直接映射而不解压，省一份磁盘占用
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

tasks.named("preBuild") {
    dependsOn(copySharedCore)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
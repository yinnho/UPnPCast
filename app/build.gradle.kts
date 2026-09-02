import java.util.Properties

// 加载本地配置
val localPropertiesFile = rootProject.file("gradle.properties.local")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

android {
    namespace = "com.yinnho.upnpcast"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        
        // Version information setting
        version = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Add ProGuard consumer rules
        consumerProguardFiles("consumer-rules.pro")
    }

    // Test configuration
    testOptions {
        unitTests {
            // Library code logs through android.util.Log; unit tests only
            // need the calls to be no-ops
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
            }
        }
    }
    
    // Simplified lint configuration
    lint {
        abortOnError = false
    }

    // Simplified build type configuration
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    
    // Java version configuration
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    // Library doesn't need ViewBinding
    buildFeatures {
        buildConfig = false
    }

    // 简化的发布配置，适合JitPack
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Core dependencies, using Version Catalog
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // 添加RecyclerView支持，用于本地视频选择器
    implementation(libs.androidx.recyclerview)
    
    // Local file server for local casting
    implementation(libs.nanohttpd)
    
    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockito.core) 
    testImplementation(libs.mockito.junit.jupiter)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// 简化的发布配置 - 支持Maven Central
afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "OSSRH"
                val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
                credentials {
                    username = localProperties.getProperty("ossrhUsername") 
                        ?: project.findProperty("ossrhUsername") as String? ?: ""
                    password = localProperties.getProperty("ossrhPassword") 
                        ?: project.findProperty("ossrhPassword") as String? ?: ""
                }
            }
            // 本地仓库用于生成Central Portal bundle
            maven {
                name = "Local"
                url = uri(layout.buildDirectory.dir("repo").get().asFile)
            }
        }
        
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                
                groupId = "com.yinnho.upnpcast"
                artifactId = "upnpcast"
                version = "1.3.0"
                
                pom {
                    name.set("UPnPCast")
                    description.set("Android DLNA/UPnP casting library with a coroutine-first Kotlin API: SSDP device discovery, playback and volume control, local-file streaming with Range support, and external subtitles via CastOptions.")
                    url.set("https://github.com/yinnho/UPnPCast")
                    
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set("yinnho")
                            name.set("Yinnho")
                            email.set("4505225@qq.com")
                        }
                    }
                    
                    scm {
                        connection.set("scm:git:https://github.com/yinnho/UPnPCast.git")
                        developerConnection.set("scm:git:ssh://git@github.com:yinnho/UPnPCast.git")
                        url.set("https://github.com/yinnho/UPnPCast")
                    }
                }
            }
        }
    }
    
    // 签名配置 - 仅在本地开发环境启用
    signing {
        // 只有在有GPG配置且不在CI环境时才启用签名
        val isCI = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
        val hasGpgConfig = localProperties.getProperty("signing.gnupg.keyName") != null 
            || project.hasProperty("signing.gnupg.keyName")
        
        if (!isCI && hasGpgConfig) {
            // 强制使用GPG命令行工具以确保标准签名格式
            useGpgCmd()
            sign(publishing.publications["release"])
        }
    }
}
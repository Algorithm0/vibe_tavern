plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // api: the app constructs OkHttpEngine, whose constructor takes an OkHttpClient.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}

sourceSets {
    test {
        resources.srcDir("../../fixtures")
    }
}

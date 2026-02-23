import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":tun-safe-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("kotlinxCoroutines")}")
    implementation("io.netty:netty-transport:${property("netty")}")
    implementation("io.netty:netty-handler:${property("netty")}")
    implementation("io.netty:netty-codec:${property("netty")}")
    implementation("ch.qos.logback:logback-classic:${property("logbackClassic")}")
    implementation("ch.qos.logback:logback-core:${property("logbackClassic")}")
}

application {
    mainClass.set("com.jun.tun.safe.bootstrap.BootstrapKt")
}

tasks.jar {
    archiveBaseName.set("bootstrap")

    manifest {
        attributes["Main-Class"] = "com.jun.tun.safe.bootstrap.BootstrapKt"
    }

    from({
        configurations.runtimeClasspath.get().map {
            if (it.isDirectory) it else zipTree(it)
        }
    })

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "17"
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

dependencies {

     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("kotlinxCoroutines")}")
    implementation("io.netty:netty-transport:${property("netty")}")
    implementation("io.netty:netty-handler:${property("netty")}")
    implementation("io.netty:netty-codec:${property("netty")}")
    implementation("com.google.code.gson:gson:${property("gson")}")
    implementation("cn.hutool:hutool-all:${property("hutool")}")
    implementation("org.slf4j:slf4j-api:${property("slf4j")}")
    implementation(kotlin("reflect"))
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
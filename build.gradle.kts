apply(from = "${project.rootDir}/constants.gradle.kts")

plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.4.5"
	id("io.spring.dependency-management") version "1.1.7"
}

allprojects {
	group = "${property("group")}"
	version = "${property("version")}"

	repositories {
		mavenCentral()
		maven("https://maven.aliyun.com/repository/public")
		maven("https://maven.aliyun.com/repository/google")
		maven("https://maven.aliyun.com/repository/gradle-plugin")
	}
}

subprojects {

	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "kotlin-spring")
	apply(plugin = "io.spring.dependency-management")

	if (name.endsWith("bootstrap-")) {
		apply(plugin = "org.springframework.boot")
	}

	apply(from = "${project.rootDir}/constants.gradle.kts")

	dependencyManagement  {
		imports {
			mavenBom("org.springframework.boot:spring-boot-dependencies:${property("springBoot")}")
			mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
			mavenBom("org.springframework.ai:spring-ai-bom:${property("springAi")}")
			mavenBom("com.alibaba.cloud.ai:spring-ai-alibaba-bom:${property("alibabaAi")}")
		}
	}

	tasks.withType<Test> {
		useJUnitPlatform()
	}
}

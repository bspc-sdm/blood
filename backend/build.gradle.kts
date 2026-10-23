import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "3.5.16"
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
}

group = "com.lifolo"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// JPA entities must be open for Hibernate proxies
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    mavenCentral()
}

val kotlinVersion = "2.3.21"
val jobrunrVersion = "8.8.2"
val springdocVersion = "2.8.17"
val firebaseAdminVersion = "9.10.0"
val sentryVersion = "8.60.0"
val awsSdkVersion = "2.55.12"
val mockkVersion = "1.14.9"
val springmockkVersion = "4.0.2"

dependencies {
    // Version management: Spring Boot BOM + Kotlin BOM (platforms, so the newer Kotlin wins over Boot's default)
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))
    implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.jobrunr:jobrunr-spring-boot-3-starter:$jobrunrVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    implementation("com.google.firebase:firebase-admin:$firebaseAdminVersion")
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:$sentryVersion")
    implementation("software.amazon.awssdk:sesv2")
    implementation("software.amazon.awssdk:s3")
    // QR codes of the share pages (P1: /api/share?rxqr=, ?shopqr=, /shop/{code})
    implementation("io.nayuki:qrcodegen:1.8.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // R2 tests: MinIO (S3 API) in Docker, like PostgreSQL and Redis
    testImplementation("org.testcontainers:minio")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("com.ninja-squad:springmockk:$springmockkVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // the authorization sweep fuzzes every action with ~1 MB bodies; the default 512 MB ran out once the pay actions came
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

// a plain jar is not needed; bootJar is the deployable
tasks.named<Jar>("jar") {
    enabled = false
}

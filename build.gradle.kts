plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "llamabendb"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Copies the built Nuxt frontend into its own output dir, which the jar
// bundles under /static/. Runs as NO-SOURCE (skipped) when frontend/dist
// does not exist yet. bootJar repackages the plain jar, so it picks the
// frontend up automatically.
val copyFrontendDist = tasks.register<Copy>("copyFrontendDist") {
    from("frontend/dist")
    into(layout.buildDirectory.dir("frontend-dist"))
}

// The frontend lands in the main resources output, so it ends up under
// BOOT-INF/classes/static/ in both the plain jar and the boot jar.
tasks.named<ProcessResources>("processResources") {
    dependsOn(copyFrontendDist)
    from(layout.buildDirectory.dir("frontend-dist")) {
        into("static")
    }
}

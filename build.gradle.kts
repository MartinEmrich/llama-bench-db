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

// Installs frontend dependencies from the committed lockfile (requires node
// >= 20 and npm on PATH). The marker file lives inside node_modules so it
// vanishes with it; tracking a single file keeps the up-to-date check cheap.
val npmInstall = tasks.register<Exec>("npmInstall") {
    workingDir = layout.projectDirectory.dir("frontend").asFile
    commandLine("npm", "ci", "--no-audit", "--no-fund")
    inputs.file("frontend/package.json")
    inputs.file("frontend/package-lock.json")
    outputs.file(layout.projectDirectory.file("frontend/node_modules/.gradle-npm-ci"))
    // Writes the marker via the task receiver so the action captures nothing
    // from script scope (configuration cache forbids script object refs).
    doLast { outputs.files.singleFile.writeText("ok\n") }
}

// Builds the Nuxt SPA: `nuxt build` plus scripts/gen-entry.mjs, which boots
// the built server on port 39871 and captures the SPA entry into dist/.
val nuxtBuild = tasks.register<Exec>("nuxtBuild") {
    dependsOn(npmInstall)
    workingDir = layout.projectDirectory.dir("frontend").asFile
    commandLine("npm", "run", "build")
    inputs.files(fileTree("frontend") {
        exclude("dist/**", ".output/**", "node_modules/**")
    })
    outputs.dir(layout.projectDirectory.dir("frontend/dist"))
}

// Copies the built Nuxt frontend into its own output dir, which the jar
// bundles under /static/. bootJar repackages the plain jar, so it picks the
// frontend up automatically.
val copyFrontendDist = tasks.register<Copy>("copyFrontendDist") {
    dependsOn(nuxtBuild)
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

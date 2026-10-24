/*
 * Copyright 2026 Chet Mancini
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    `java-library`
    application
    `maven-publish`
    signing
    alias(libs.plugins.shadow)
}

group = "io.github.chetmancini"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

// slf4j-simple is a runtime binding for the CLI only; it must never reach the library POM.
val cli: Configuration = configurations.create("cli")

dependencies {
    cli(libs.slf4j.simple)
    api(libs.slf4j.api)
    implementation(libs.picocli)
    implementation(libs.gson)
    implementation(libs.informix.jdbc)
    implementation(libs.mssql.jdbc)
    implementation(libs.redshift.jdbc)
    implementation(libs.mysql.connector)
    implementation(libs.postgresql)
    implementation(libs.snowflake.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.h2)
}

// End-to-end tests against real databases in Testcontainers. Needs Docker, so it is a separate
// source set and task that `test`, `check` and `build` do not depend on: run `./gradlew e2eTest`.
val e2eTest: SourceSet = sourceSets.create("e2eTest").apply {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[e2eTest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[e2eTest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "e2eTestImplementation"(platform(libs.junit.bom))
    "e2eTestImplementation"(platform(libs.testcontainers.bom))
    "e2eTestImplementation"(libs.junit.jupiter)
    "e2eTestImplementation"(libs.assertj.core)
    "e2eTestImplementation"(libs.testcontainers.junit.jupiter)
    "e2eTestImplementation"(libs.testcontainers.postgresql)
    "e2eTestImplementation"(libs.testcontainers.mysql)
    "e2eTestRuntimeOnly"(libs.junit.platform.launcher)
}

application {
    mainClass = "io.github.chetmancini.pouroversql.cli.PouroverCommand"
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.shadowJar {
    configurations = listOf(project.configurations.runtimeClasspath.get(), cli)
}

tasks.run.configure {
    classpath += cli
}

tasks.startScripts {
    classpath = files(tasks.jar, configurations.runtimeClasspath, cli)
}

distributions {
    main {
        contents {
            into("lib") {
                from(cli)
                duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("e2eTest") {
    group = "verification"
    description = "Runs Postgres and MySQL end-to-end tests in Testcontainers (requires Docker; skipped without it)."
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.register("fatJar") {
    group = "build"
    description = "Assembles a runnable fat jar containing all runtime dependencies."
    dependsOn(tasks.shadowJar)
}

publishing {
    repositories {
        // Local dry-run target used by the release workflow; nothing is published remotely.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "pourover-sql"
            from(components["java"])
            pom {
                name = "Pour Over SQL"
                description = "A flexible SQL querying library"
                url = "https://github.com/chetmancini/pourover-sql"
                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "chetmancini"
                        name = "Chet Mancini"
                        email = "chetmancini@gmail.com"
                    }
                }
                scm {
                    connection = "scm:git:git://github.com/chetmancini/pourover-sql.git"
                    developerConnection = "scm:git:ssh://github.com/chetmancini/pourover-sql.git"
                    url = "https://github.com/chetmancini/pourover-sql"
                }
            }
        }
    }
}

// The shadow fat jar stays a plain build artifact (fatJar/shadowJar); it is not published.
(components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(
    configurations["shadowRuntimeElements"]
) {
    skip()
}

signing {
    // Only sign when a key is supplied; no remote publishing target is configured.
    val signingKey = providers.gradleProperty("signingKey")
    isRequired = false
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingPassword").orNull)
        sign(publishing.publications["mavenJava"])
    }
}

// Doclint disabled: the legacy sources have many missing-comment javadoc errors under JDK 21.
tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

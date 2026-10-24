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
    java
    application
}

group = "io.github.chetmancini"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation(libs.h2) // compile-time: the examples build an H2 JdbcDataSource
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Checks that the snippets marked in the example sources appear verbatim in README.md.
tasks.test {
    useJUnitPlatform()
    inputs.file(rootProject.file("README.md"))
    systemProperty("readme.path", rootProject.file("README.md").absolutePath)
    systemProperty("examples.sources", file("src/main/java").absolutePath)
}

// Pick the example with: ./gradlew :examples:run -PmainClass=io.github.chetmancini.pouroversql.examples.ListExample
application {
    mainClass = providers.gradleProperty("mainClass")
        .orElse("io.github.chetmancini.pouroversql.examples.ListExample")
}

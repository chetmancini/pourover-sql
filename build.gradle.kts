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
    maven {
        url = uri("https://s3.amazonaws.com/redshift-maven-repository/release")
    }
}

dependencies {
    api(libs.slf4j.api)
    implementation(libs.slf4j.simple)
    implementation(libs.commons.cli)
    implementation(libs.commons.dbutils)
    implementation(libs.commons.text)
    implementation(libs.commons.collections4)
    implementation(libs.gson)
    implementation(libs.guava)
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
}

application {
    mainClass = "com.simondata.pouroversql.ExtractorRunner"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("fatJar") {
    group = "build"
    description = "Assembles a runnable fat jar containing all runtime dependencies."
    dependsOn(tasks.shadowJar)
}

publishing {
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

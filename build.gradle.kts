plugins {
    base
}

allprojects {
    group = "de.bigmichi1.graalpy"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JacocoPluginExtension> {
        toolVersion = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("jacoco").get().requiredVersion
    }

    // At least 90 % of lines covered by each module's own tests; `check` fails below that.
    tasks.withType<JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = "0.90".toBigDecimal()
                }
            }
        }
    }

    tasks.withType<Test>().configureEach {
        finalizedBy(tasks.withType<JacocoReport>())
    }

    tasks.withType<JacocoReport>().configureEach {
        reports { xml.required = true }
    }

    tasks.named("check") {
        dependsOn(tasks.withType<JacocoCoverageVerification>())
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 21
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Stock OpenJDK runs Truffle in interpreter-only mode; silence the warning in tests.
        systemProperty("polyglot.engine.WarnInterpreterOnly", "false")
        // Truffle uses native access and sun.misc.Unsafe; grant both to keep JDK 24+ quiet.
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

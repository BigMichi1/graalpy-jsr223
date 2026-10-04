description = "JSR-223 (javax.script) ScriptEngine for Python backed by GraalPy"

dependencies {
    api(libs.graalpy.polyglot)
    // python-community is a POM-only aggregate pulling the GraalPy language and its resources.
    implementation(libs.graalpy.python)
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "de.bigmichi1.graalpy.jsr223",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

/*
 * Performance and memory checks, outside `check` on purpose: they take minutes and measure the
 * machine as much as the code. `./gradlew :graalpy-scriptengine:jmh` runs the JMH benchmarks
 * (arguments after `--args`, e.g. `--args="-f 1 Eval"`), `./gradlew :graalpy-scriptengine:leakCheck`
 * the heap check.
 */
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "jmhImplementation"(libs.jmh.core)
    "jmhAnnotationProcessor"(libs.jmh.generator.annprocess)
    // GraalJS, CIB seven's usual JavaScript engine, as the baseline the Python numbers are read against.
    "jmhImplementation"(libs.graalpy.js)
    "jmhImplementation"(libs.graalpy.js.scriptengine)
    "jmhRuntimeOnly"(libs.slf4j.simple)
}

val perfJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--sun-misc-unsafe-memory-access=allow",
    "-Dpolyglot.engine.WarnInterpreterOnly=false",
)

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks of the GraalPy script engine."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    jvmArgs(perfJvmArgs)
    val reports = layout.buildDirectory.dir("reports/jmh")
    args("-rf", "json", "-rff", reports.get().file("results.json").asFile.absolutePath)
    doFirst { reports.get().asFile.mkdirs() }
}

tasks.register<JavaExec>("leakCheck") {
    group = "verification"
    description = "Evaluates many scripts and fails if the heap keeps growing."
    classpath = jmh.runtimeClasspath
    mainClass = "de.bigmichi1.graalpy.jsr223.perf.LeakCheck"
    jvmArgs(perfJvmArgs)
    // -PleakCheck.rounds=30 -PleakCheck.only=factories narrows a run to one workload.
    listOf("leakCheck.rounds", "leakCheck.only").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

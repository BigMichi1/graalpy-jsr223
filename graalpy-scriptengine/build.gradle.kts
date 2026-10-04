description = "JSR-223 (javax.script) ScriptEngine for Python backed by GraalPy"

dependencies {
    api(libs.graalpy.polyglot)
    // python-community is a POM-only aggregate pulling the GraalPy language and its resources.
    implementation(libs.graalpy.python)

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

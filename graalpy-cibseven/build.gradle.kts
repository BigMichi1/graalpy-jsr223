description = "CIB seven process engine plugin for the GraalPy JSR-223 script engine"

dependencies {
    api(project(":graalpy-scriptengine"))
    compileOnly(libs.cibseven.engine)
    // Spin support is optional: the plugin only activates it when Spin is on the classpath.
    compileOnly(libs.cibseven.spin.core)

    testImplementation(libs.cibseven.engine)
    testImplementation(libs.cibseven.spin.plugin)
    testImplementation(libs.cibseven.spin.json)
    testImplementation(libs.h2)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "de.bigmichi1.graalpy.cibseven")
    }
}

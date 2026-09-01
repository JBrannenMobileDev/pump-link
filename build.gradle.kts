plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The dependency direction in docs/07-architecture.md is the architecture. A
// rule that only exists in prose is a rule that gets violated during a
// refactor and noticed during review, if at all, so it is asserted here
// instead. :domain having an empty edge set is the load-bearing entry.
val allowedEdges: Map<String, Set<String>> = mapOf(
    ":app" to setOf(":domain", ":data", ":presentation"),
    ":data" to setOf(":domain", ":protocol", ":simulator"),
    ":presentation" to setOf(":domain"),
    ":simulator" to setOf(":protocol"),
    ":protocol" to emptySet(),
    ":domain" to emptySet(),
)

// These four must stay runnable on a bare JVM, which is what lets most of the
// scenario table — and the MVI exhaustiveness rule — run in CI with no device.
val pureJvmModules = setOf(":protocol", ":domain", ":presentation", ":simulator")

val observedEdges = mutableMapOf<String, Set<String>>()
val androidPluginUsage = mutableMapOf<String, Boolean>()

subprojects {
    afterEvaluate {
        observedEdges[path] = configurations
            .filter { it.name in setOf("api", "implementation", "compileOnly", "runtimeOnly") }
            .flatMap { it.dependencies }
            .filterIsInstance<ProjectDependency>()
            .map { it.path }
            .toSet()

        androidPluginUsage[path] = plugins.any { it.javaClass.name.startsWith("com.android.build") }
    }
}

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if a module depends on something the architecture forbids."

    val edges = observedEdges
    val android = androidPluginUsage
    val allowed = allowedEdges
    val pure = pureJvmModules

    doLast {
        val problems = buildList {
            for ((module, deps) in edges.toSortedMap()) {
                val permitted = allowed[module]
                if (permitted == null) {
                    add("$module is not listed in allowedEdges; add it deliberately")
                    continue
                }
                (deps - permitted).sorted().forEach {
                    add("$module must not depend on $it")
                }
            }
            for (module in pure.sorted()) {
                if (android[module] == true) {
                    add("$module applies an Android plugin but must stay JVM-testable")
                }
            }
        }

        if (problems.isNotEmpty()) {
            error(problems.joinToString(separator = "\n  ", prefix = "Module boundary violations:\n  "))
        }
        logger.lifecycle("Module boundaries hold across ${edges.size} modules.")
    }
}

tasks.register("check") {
    group = "verification"
    dependsOn("checkModuleBoundaries")
    dependsOn(subprojects.map { "${it.path}:check" })
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.library) apply false
}
tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if a module declares a dependency that violates the mesh architecture boundaries."
    notCompatibleWithConfigurationCache("Reads project dependency configurations directly, which the configuration cache does not support.")

    doLast {
        val forbidden = mapOf(
            ":signalling" to listOf(":transport"),
            ":transport" to listOf(":signalling"),
            ":app" to listOf(":signalling", ":transport"),
        )

        val violations = mutableListOf<String>()

        forbidden.forEach { (modulePath, forbiddenTargets) ->
            val moduleProject = project(modulePath)
            val implementationDeps = moduleProject.configurations
                .findByName("implementation")
                ?.dependencies
                ?.filterIsInstance<ProjectDependency>()
                ?.map { it.path }
                ?: emptyList()

            forbiddenTargets.forEach { forbiddenPath ->
                if (forbiddenPath in implementationDeps) {
                    violations += "$modulePath must not depend on $forbiddenPath"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Architecture boundary violated:\n" +
                        violations.joinToString("\n") { "  - $it" }
            )
        }
    }
}

project(":app") {
    afterEvaluate {
        tasks.named("check") {
            dependsOn(rootProject.tasks.named("checkModuleBoundaries"))
        }
    }
}
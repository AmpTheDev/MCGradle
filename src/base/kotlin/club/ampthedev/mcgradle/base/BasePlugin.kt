package club.ampthedev.mcgradle.base

import club.ampthedev.mcgradle.base.tasks.BaseTask
import club.ampthedev.mcgradle.base.utils.*
import club.ampthedev.mcgradle.base.utils.mcpconfig.MCPConfigUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import kotlin.reflect.KClass

abstract class BasePlugin<T : BaseExtension> : Plugin<Project> {
    abstract val extension: T
    protected lateinit var project: Project
    private val tasks = hashMapOf<String, Pair<BaseTask, BaseTask.() -> Unit>>()

    open fun preSetup(project: Project) {
        project.plugins.apply("java")
        project.plugins.apply("idea")
        this.project = project
        project.plugin = this
        project.extensions.add(EXTENSION_NAME, extension)
        project.configurations.create(CONFIGURATION_MC_DEPS)
        project.configurations.create(CONFIGURATION_MCP_MAPS)
        project.configurations.create(CONFIGURATION_MCP_DATA)
    }

    fun <T : BaseTask> task(name: String, task: KClass<T>, setup: T.() -> Unit = {}): T {
        val t = project.tasks.create(name, task.java)
        tasks[name] = Pair(t, castTo(setup))
        return t
    }

    open fun setup(project: Project) {
        project.repositories.mavenCentral()
        project.repositories.maven { it.setUrl("https://files.minecraftforge.net/maven") }
        project.repositories.maven { it.setUrl("https://libraries.minecraft.net") }

        project.addReplacements(mapOf(
                CACHE_DIR to "${project.gradle.gradleUserHomeDir.absolutePath}/caches/mcgradle",
                PROJECT_DIR to project.projectDir.absolutePath,
                MC_VERSION to extension.version,
                MAPPING_CHANNEL to extension.mappingChannel,
                MAPPING_VERSION to extension.mappingVersion,
                RUN_DIRECTORY to extension.runDirectory,
                BUILD_DIR to project.buildDir.absolutePath
        ))

        StartSourceGenerator.init(project)

        // Add dependencies
        for (element in project.getVersionJson().getAsJsonArray("libraries")) {
            val obj = element.asJsonObject
            if (shouldIncludeDependency(obj)) {
                project.dependencies.add(CONFIGURATION_MC_DEPS, getDependencyString(obj))
            }
        }

        // Add MCP stuff
        project.dependencies.add(CONFIGURATION_MCP_DATA, mapOf(
                "group" to "de.oceanlabs.mcp",
                "name" to "mcp_${extension.mappingChannel}",
                "version" to extension.mappingVersion,
                "ext" to "zip"
        ))

        if (project.newConfig) {
            project.dependencies.add(CONFIGURATION_MCP_MAPS, mapOf(
                "group" to "de.oceanlabs.mcp",
                "name" to "mcp_config",
                "version" to extension.version,
                "ext" to "zip"
            ))
            MCPConfigUtils.init(project)
        } else {
            project.dependencies.add(CONFIGURATION_MCP_MAPS, mapOf(
                "group" to "de.oceanlabs.mcp",
                "name" to "mcp",
                "version" to extension.version,
                "classifier" to "srg",
                "ext" to "zip"
            ))
        }

        // Set up tasks
        val setUp = hashMapOf<String, Boolean>()

        for (task in tasks) {
            task.value.setupTask(setUp)
        }
    }

    private fun Pair<BaseTask, BaseTask.() -> Unit>.setupTask(setupTasks: MutableMap<String, Boolean>) {
        if (setupTasks[first.name] != true) {
            for (t in first.dependsOn) {
                if (t is String) {
                    tasks[t]?.setupTask(setupTasks)
                }
            }
            first.setup()
            second(first)
        }
    }

    override fun apply(target: Project) {
        target.reset()
        preSetup(target)
        target.afterEvaluate {
            setup(it)
        }
    }
}
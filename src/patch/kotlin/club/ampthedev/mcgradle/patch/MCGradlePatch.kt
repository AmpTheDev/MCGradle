package club.ampthedev.mcgradle.patch

import club.ampthedev.mcgradle.base.BasePlugin
import club.ampthedev.mcgradle.base.tasks.TaskType
import club.ampthedev.mcgradle.base.tasks.impl.*
import club.ampthedev.mcgradle.base.utils.*
import club.ampthedev.mcgradle.base.utils.mcpconfig.MCPConfigUtils
import club.ampthedev.mcgradle.patch.tasks.*
import club.ampthedev.mcgradle.patch.utils.*
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import java.io.File

class MCGradlePatch : BasePlugin<PatchExtension>() {
    override val extension by lazy { PatchExtension(project) }

    override fun preSetup(project: Project) {
        super.preSetup(project)
        task(DOWNLOAD_CLIENT, TaskDownloadClient::class)
        task(DOWNLOAD_SERVER, TaskDownloadServer::class)
        task(SPLIT_SERVER, TaskSplitServer::class)
        task(MERGE_JARS, TaskMergeJars::class)
        task(GENERATE_MAPPINGS, TaskGenerateMappings::class)
        task(DEOBF_JAR, TaskDeobf::class) {
            dependsOn(MERGE_JARS, GENERATE_MAPPINGS)
        }
        task(DECOMP, TaskDecomp::class)
        task(APPLY_MCP_PATCHES, TaskApplyPatches::class) {
            input = File(project.string(DECOMP_JAR))
            output = File(project.string(PATCHED_JAR))
            patches = File(project.string(if (project.newConfig) MCP_PATCHES_NEW else MCP_PATCHES))
            mcpPatch = true
            dependsOn(DECOMP)
        }
        task(GEN_START, TaskGenStart::class)
        task(DOWNLOAD_NATIVES, TaskDownloadNatives::class)
        task(SOURCE_DEOBF, TaskSourceDeobf::class)
        task(APPLY_MOD_PATCHES, TaskApplyPatches::class) {
            input = project.mcgFile(SOURCE_MAPPED_JAR)
            output = File(temporaryDir, MOD_PATCHED_JAR)
            patches = extension.patchDir
            prepareDirectory(patches)
            dependsOn(SOURCE_DEOBF)
        }
        task(DOWNLOAD_ASSET_INDEX, TaskDownloadAssetIndex::class)
        task(DOWNLOAD_ASSETS, TaskDownloadAssets::class)
        task(COPY_SOURCES, TaskCopySource::class)
        task(GENERATE_PATCHES, TaskGeneratePatches::class)
        task(RECOMPILE_CLEAN_TASK, TaskRecompile::class)
        task(REOBFUSCATE_JAR, TaskReobf::class) {
            input = (project.tasks.getByName("jar") as Jar).archiveFile.get().asFile
            output = File(temporaryDir, "reobf.jar")
            dependsOn("jar", DEOBF_JAR)
        }
        task(GENERATE_BIN_PATCHES, TaskGenerateBinPatches::class)
        task(GENERATE_ARTIFACTS, TaskGenerateArtifacts::class)
        task(GEN_CLIENT_RUN, TaskCreateRunConfig::class) {
            configName = "Minecraft Client"
            mainClass = "club.ampthedev.mcgradle.Start"
            vmOptions.addAll(extension.jvmargs)
            args.addAll(extension.args)
            workingDirectory = File(extension.runDirectory)
            beforeRunTasks += arrayOf(DOWNLOAD_NATIVES, DOWNLOAD_ASSETS, GEN_START)
        }
        task(GEN_SERVER_RUN, TaskCreateRunConfig::class) {
            configName = "Minecraft Server"
            mainClass = "club.ampthedev.mcgradle.Start"
            vmOptions.addAll(extension.jvmargs)
            args.add("--server")
            args.addAll(extension.args)
            workingDirectory = File(extension.runDirectory)
            beforeRunTasks += arrayOf(DOWNLOAD_NATIVES, DOWNLOAD_ASSETS, GEN_START)
        }
        val genConfigs = project.task(GEN_RUNS)
        genConfigs.group = TaskType.MAIN.groupName
        genConfigs.dependsOn(GEN_CLIENT_RUN, GEN_SERVER_RUN)
        val setupTask = project.task(SETUP_DEV)
        setupTask.group = TaskType.MAIN.groupName
        setupTask.dependsOn(COPY_SOURCES, GEN_START, DOWNLOAD_NATIVES, DOWNLOAD_ASSETS)
        val setupTask2 = project.task(SETUP_CI)
        setupTask2.group = TaskType.MAIN.groupName
        setupTask2.dependsOn(COPY_SOURCES, GEN_START)
        project.tasks.getByName("idea").dependsOn(GEN_RUNS)

        val javaPlugin = project.convention.getPlugin(JavaPluginConvention::class.java)
        val sourceSets = javaPlugin.sourceSets
        val main = extension.sourceSet ?: sourceSets.getByName("main")
        val extraSrcRoot = File(project.projectDir, "src/minecraft")
        main.java.srcDirs(File(extraSrcRoot, "java"))
        main.resources.srcDirs(File(extraSrcRoot, "resources"))
    }

    override fun setup(project: Project) {
        super.setup(project)
        project.tasks.getByName("jar").finalizedBy(GENERATE_ARTIFACTS)
        val javaPlugin = project.convention.getPlugin(JavaPluginConvention::class.java)
        val sourceSets = javaPlugin.sourceSets
        val main = extension.sourceSet ?: sourceSets.getByName("main")
        project.configurations.getByName(main.implementationConfigurationName)
            .extendsFrom(project.configurations.getByName(CONFIGURATION_MC_DEPS))
    }
}
package club.ampthedev.mcgradle.user

import club.ampthedev.mcgradle.base.BasePlugin
import club.ampthedev.mcgradle.base.tasks.TaskType
import club.ampthedev.mcgradle.base.tasks.impl.*
import club.ampthedev.mcgradle.base.utils.*
import club.ampthedev.mcgradle.user.tasks.TaskApplyBinaryPatches
import club.ampthedev.mcgradle.user.tasks.TaskGenerateStartLib
import club.ampthedev.mcgradle.user.tasks.TaskInjectClasses
import club.ampthedev.mcgradle.user.utils.*
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.zip.ZipFile

class MCGradleUser : BasePlugin<UserExtension>() {
    override val extension by lazy { UserExtension(project) }

    override fun preSetup(project: Project) {
        super.preSetup(project)
        project.configurations.create(CONFIGURATION_MOD)
        task(GENERATE_MAPPINGS, TaskGenerateMappings::class)
        task(DOWNLOAD_CLIENT, TaskDownloadClient::class)
        task(DOWNLOAD_SERVER, TaskDownloadServer::class)
        task(SPLIT_SERVER, TaskSplitServer::class)
        task(MERGE_JARS, TaskMergeJars::class)
        task(INJECT_CLASSES_2, TaskInjectClasses::class) {
            mcJar = project.mcgFile(MERGED_JAR)
            if (!project.vanilla) {
                output = project.mcgFile(INJECTED)
            }
            dependsOn(MERGE_JARS)
        }
        task(DEOBF_JAR, TaskDeobf::class) {
            if (!project.vanilla) {
                jar = project.mcgFile(INJECTED)
                out = project.mcgFile(INJECTED_DEOBF)
                dependsOn(INJECT_CLASSES_2)
            }
        }
        task(DECOMP, TaskDecomp::class) {
            if (!project.vanilla) {
                input = project.mcgFile(INJECTED_DEOBF)
                output = project.mcgFile(INJECTED_DECOMP)
            }
        }
        task(APPLY_MCP_PATCHES, TaskApplyPatches::class) {
            input = project.mcgFile(if (project.vanilla) DECOMP_JAR else INJECTED_DECOMP)
            output = project.mcgFile(if (project.vanilla) PATCHED_JAR else INJECTED_PATCHED)
            patches = project.mcgFile(if (project.newConfig) MCP_PATCHES_NEW else MCP_PATCHES)
            mcpPatch = true
            dependsOn(DECOMP)
        }
        task(SOURCE_DEOBF, TaskSourceDeobf::class) {
            if (!project.vanilla) {
                input = project.mcgFile(INJECTED_PATCHED)
                output = project.mcgFile(INJECTED_SOURCE_MAPPED)
            }
        }
        task(RECOMPILE_CLEAN_TASK, TaskRecompile::class) {
            if (!project.vanilla) {
                sourceJar = project.mcgFile(INJECTED_SOURCE_MAPPED)
                outputJar = project.mcgFile(INJECTED_RECOMPILED)
                classpath.files += project.configurations.getByName(CONFIGURATION_MOD)
            } else {
                outputJar = project.mcgFile(DECOMP_BIN)
            }
        }
        task(BINPATCH_MERGED, TaskApplyBinaryPatches::class) {
            if (project.vanilla) return@task
            input = project.mcgFile(INJECTED_RECOMPILED)
            output = project.mcgFile(DECOMP_BIN)
            patchLzmaPath = "binpatches.dev.lzma"
            patchRoot = "merged"
            dependsOn(RECOMPILE_CLEAN_TASK)
        }

        task(BINPATCH_CLIENT, TaskApplyBinaryPatches::class) {
            if (project.vanilla) return@task
            input = project.mcgFile(CLIENT_JAR)
            output = project.mcgFile(PATCHED_CLIENT)
            patchLzmaPath = "binpatches.lzma"
            patchRoot = "client"
            dependsOn(DOWNLOAD_CLIENT)
        }
        task(BINPATCH_SERVER, TaskApplyBinaryPatches::class) {
            if (project.vanilla) return@task
            input = project.mcgFile(SPLIT_SERVER_JAR)
            output = project.mcgFile(PATCHED_SERVER)
            patchLzmaPath = "binpatches.lzma"
            patchRoot = "server"
            dependsOn(SPLIT_SERVER)
        }
        task(INJECT_CLASSES, TaskInjectClasses::class) {
            dependsOn(MERGE_PATCHED_JARS)
        }
        task(MERGE_PATCHED_JARS, TaskMergeJars::class) {
            if (!project.vanilla) {
                clientJar = project.mcgFile(PATCHED_CLIENT)
                serverJar = project.mcgFile(PATCHED_SERVER)
                mergedJar = project.mcgFile(PATCHED_MERGED)
                dependsOn(BINPATCH_CLIENT, BINPATCH_SERVER)
            }
        }
        task(MCP_DEOBF, TaskDeobf::class) {
            if (!project.vanilla) {
                jar = project.mcgFile(PATCHED_INJECTED)
                dependsOn(INJECT_CLASSES, MERGE_PATCHED_JARS)
            } else {
                dependsOn(MERGE_JARS)
            }
            out = project.mcgFile(DEOBF_MCP)
            srg = project.mcgFile(NOTCH_MCP)
        }
        task(GEN_START, TaskGenerateStartLib::class)
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
        task(REOBF_JAR, TaskDeobf::class) {
            jar = (project.tasks.getByName("jar") as Jar).archiveFile.get().asFile
            srg = project.mcgFile(MCP_NOTCH)
            skipExceptor = true
            out = jar
            dependsOn("jar")
        }
        val genConfigs = project.task(GEN_RUNS)
        genConfigs.group = TaskType.MAIN.groupName
        genConfigs.dependsOn(GEN_CLIENT_RUN, GEN_SERVER_RUN)
        project.tasks.getByName("idea").dependsOn(GEN_RUNS)
        task(DOWNLOAD_NATIVES, TaskDownloadNatives::class)
        task(DOWNLOAD_ASSET_INDEX, TaskDownloadAssetIndex::class)
        task(DOWNLOAD_ASSETS, TaskDownloadAssets::class)
    }

    override fun setup(project: Project) {
        project.tasks.getByName("jar").finalizedBy(REOBF_JAR)
        project.repositories.mavenCentral()
        project.repositories.maven { it.setUrl("https://maven.minecraftforge.net/") }
        project.repositories.maven { it.setUrl("https://libraries.minecraft.net") }
        var modJar: File? = null
        val conf = project.configurations.getByName(CONFIGURATION_MOD)
        var used = false
        for (f in conf) {
            used = true
            val isMod = try {
                ZipFile(f).use {
                    it.getEntry("binpatches.lzma") != null && it.getEntry("binpatches.dev.lzma") != null
                }
            } catch (e: Throwable) {
                continue
            }
            if (isMod) {
                if (modJar != null) {
                    throw GradleException("You can only have one $CONFIGURATION_MOD dependency")
                }
                modJar = f
            }
        }
        if (modJar == null && used) {
            throw GradleException("No valid $CONFIGURATION_MOD dependency declared")
        }
        if (modJar != null) {
            project.addReplacement(MOD_JAR, modJar.absolutePath)
            project.addReplacement(MOD_HASH, modJar.hash("SHA-256"))
            for (f in conf) {
                if (f != modJar) {
                    project.dependencies.add("implementation", project.files(f))
                }
            }
        }
        project.addReplacement(
            REPO, if (project.vanilla) {
                VANILLA_REPO
            } else MOD_REPO
        )
        super.setup(project)

        project.repositories.maven { it.url = project.mcgFile(REPO).toURI() }

        project.dependencies
            .add("implementation", project.files(castTo<TaskGenerateStartLib>(project.tasks.getByName(GEN_START)).library))
        val recompFile = project.mcgFile(DECOMP_BIN)
        val tasks = project.gradle.startParameter.taskNames
        when {
            recompFile.exists() -> {
                project.dependencies.add("implementation", MCSRC_DEP)
            }
            project.mcgFile(DEOBF_MCP).exists() -> {
                project.dependencies.add("implementation", MCBIN_DEP)
            }
            !(tasks.contains(SETUP_CI) || tasks.contains(SETUP_DEV) || tasks.contains(SETUP_DECOMP)) -> println("Please run a setup task ASAP.")
        }
        project.configurations.getByName("implementation").extendsFrom(project.configurations.getByName(CONFIGURATION_MC_DEPS))

        val setupCI = project.tasks.create(SETUP_DEV)
        setupCI.group = TaskType.MAIN.groupName
        setupCI.dependsOn(MCP_DEOBF, GEN_START)

        val setupDev = project.tasks.create(SETUP_CI)
        setupDev.group = TaskType.MAIN.groupName
        setupDev.dependsOn(MCP_DEOBF, GEN_START, DOWNLOAD_NATIVES, DOWNLOAD_ASSETS)

        val copySourceJar = project.tasks.create(COPY_SOURCE, Copy::class.java)
        copySourceJar.group = TaskType.OTHER.groupName
        copySourceJar.dependsOn(SOURCE_DEOBF)
        copySourceJar.from(project.mcgFile(if (project.vanilla) SOURCE_MAPPED_JAR else INJECTED_SOURCE_MAPPED))
        copySourceJar.into(DECOMP_SRC)

        /*val setupDecomp = project.tasks.create(SETUP_DECOMP)
        setupDecomp.group = TaskType.MAIN.groupName
        setupDecomp.dependsOn(
            COPY_SOURCE,
            DOWNLOAD_NATIVES,
            DOWNLOAD_ASSETS,
            GEN_START,
            if (project.vanilla) RECOMPILE_CLEAN_TASK else BINPATCH_MERGED
        )*/
    }
}
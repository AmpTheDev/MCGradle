package club.ampthedev.mcgradle.base.utils.mcpconfig

import club.ampthedev.mcgradle.base.utils.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import java.io.File
import java.net.URL

object MCPConfigUtils {
    lateinit var config: JsonObject
    private val data = hashMapOf<String, File>()

    fun runTask(
        project: Project,
        type: String,
        output: File,
        log: File,
        vararg options: Pair<String, Any>,
        input: File? = null
    ) {
        val functionObj = config["functions"]?.asJsonObject?.get(type)?.asJsonObject ?: error("Invalid function")
        val args = arrayListOf<String>(
            File(System.getProperty("java.home"), "bin/java").absolutePath
        )
        if (functionObj.has("jvmargs")) {
            for (arg in functionObj["jvmargs"].asJsonArray) {
                args += arg.asString
            }
        }
        val file = getTaskJar(project, type)
        args += arrayListOf(
            "-jar",
            file.absolutePath
        )
        for (arg in functionObj["args"].asJsonArray ?: error("Invalid function")) {
            var processed = arg.asString
                .replace("{output}", output.absolutePath)
                .replace("{log}", log.absolutePath)
                .replace("{version}", config["version"].asString)
            if (input != null) processed = processed.replace("{input}", input.absolutePath)
            for (f in data) {
                processed = processed.replace("{${f.key}}", f.value.absolutePath)
            }
            for (option in options) {
                processed = processed.replace("{${option.first}}", option.second.toString())
            }
            args += processed
        }
        val process = ProcessBuilder()
            .redirectError(log)
            .redirectOutput(log)
            .command(args)
            .start()
            .waitFor()
        if (process != 0) {
            error("Failed to run $type")
        }
    }

    fun getTaskJar(project: Project, type: String): File {
        val functionObj = config["functions"]?.asJsonObject?.get(type)?.asJsonObject ?: error("Invalid function")
        val version = functionObj["version"]?.asString ?: error("Invalid function")
        val parts = version.split(":")

        var url = "${parts[0].replace(
            '.',
            '/'
        )}/${parts[1]}/${parts[2]}/${parts[1]}-${parts[2]}"
        if (parts.size > 3) {
            url += "-${parts[3]}"
        }
        url += if (parts.size > 4) {
            ".${parts[4]}"
        } else {
            ".jar"
        }
        val file = project.mcgFile("$CACHE_DIR/utils/$url")

        prepareDirectory(file.parentFile)
        if (!file.exists()) {
            val repo = if (functionObj.has("repo")) {
                functionObj["repo"].asString
            } else {
                "https://maven.minecraftforge.net/"
            }.removeSuffix("/").replace("http://", "https://")
            try {
                URL("$repo/$url").openConnection().apply {
                    setRequestProperty("User-Agent", USER_AGENT)
                    file.outputStream().use {
                        getInputStream().copyTo(it)
                    }
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

        return file
    }

    fun init(project: Project) {
        val mappingsDir = project.mcgFile(MAPPINGS_DIRECTORY)
        prepareDirectory(mappingsDir)
        for (file in project.configurations.getByName(CONFIGURATION_MCP_DATA)) {
            project.zipTree(file).visit(ExtractingFileVisitor(mappingsDir))
        }
        for (file in project.configurations.getByName(CONFIGURATION_MCP_MAPS)) {
            project.zipTree(file).visit(ExtractingFileVisitor(mappingsDir))
        }

        if (project.newConfig) {
            val configFile = project.mcgFile("$MAPPINGS_DIRECTORY/config.json")
            config = configFile.bufferedReader().use { JsonParser().parse(it) }.asJsonObject
            for (element in config.getAsJsonObject("data").entrySet()) {
                if (element.value.isJsonPrimitive) {
                    data[element.key] = project.mcgFile("$MAPPINGS_DIRECTORY/${element.value.asString}")
                }
            }
        }
    }
}

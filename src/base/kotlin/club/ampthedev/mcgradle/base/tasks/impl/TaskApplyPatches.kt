package club.ampthedev.mcgradle.base.tasks.impl

import club.ampthedev.mcgradle.base.tasks.ZipEditTask
import club.ampthedev.mcgradle.base.utils.ContextualPatch
import club.ampthedev.mcgradle.base.utils.MCPCleanup
import com.github.abrarsyed.jastyle.ASFormatter
import com.github.abrarsyed.jastyle.OptParser
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.zip.ZipEntry

open class TaskApplyPatches : ZipEditTask() {
    @InputDirectory
    lateinit var patches: File

    @Input
    var mcpPatch = false

    private val loadedPatches = hashMapOf<String, File>()
    private var formatter = ASFormatter()

    override fun initTask() {
        loadedPatches.clear()
        formatter = ASFormatter()
        formatter.isUseProperInnerClassIndenting = false
        val parser = OptParser(formatter)
        for (line in TaskApplyPatches::class.java.getResourceAsStream("/astyle.cfg").bufferedReader()
            .use { it.readLines() }) {
            if (line.isNotEmpty() && !line.startsWith("#")) {
                parser.parseOption(line.trim())
            }
        }
        project.fileTree(patches).visit {
            if (!it.isDirectory) {
                if (!it.name.endsWith(".patch")) return@visit
                val patchFile: File = it.file
                var fileName = patchFile.path
                fileName = fileName.substring(patches.path.length + 1)
                fileName = fileName.substring(0, fileName.length - ".java.patch".length)
                fileName = fileName.replace('.', '/').replace(File.separatorChar, '/')
                fileName += ".java"
                loadedPatches[fileName] = patchFile
            }
        }
    }

    override fun edit(entry: ZipEntry, bytes: ByteArray): ByteArray {
        if (entry.name.endsWith(".java")) {
            var content = bytes.toString(Charsets.UTF_8)
            if (mcpPatch) {
                content = content.replace(TRAILING, "")
                content = content.replace(NEWLINES, System.getProperty("line.separator"))
            }
            val patchFile = loadedPatches[entry.name]
            if (patchFile != null) {
                val provider = ContextProvider(content)
                val patch = ContextualPatch.create(patchFile.bufferedReader().use { it.readText() }, provider)
                    .setAccessC14N(true)
                val errors = patch.patch(false)

                var error: Throwable? = null
                for (rep in errors) {
                    if (!rep.status.isSuccess) {
                        logger.error("Patching failed: ${rep.target}")
                        for (hunk in rep.hunks) {
                            if (!hunk.status.isSuccess) {
                                logger.error("Hunk ${hunk.hunkID} failed! ${rep.failure.message}")
                                logger.error(hunk.hunk.lines.joinToString("\n"))
                            }
                        }
                        error = rep.failure
                    }
                }
                if (error != null) {
                    throw error
                }
                content = provider.getAsString()
            }

            if (mcpPatch) {
                // Reformat the file and fix some source issues
                content = content.stripComments()
                content = content.fixImports()
                content = MCPCleanup.cleanup(content)
                content = MCPCleanup.fixOGL(content)
                StringReader(content).use { reader ->
                    StringWriter().use { writer ->
                        formatter.format(reader, writer)
                        writer.flush()
                        content = writer.toString()
                    }
                }
            }

            return content.toByteArray()
        }
        return bytes
    }

    private class ContextProvider(file: String) : ContextualPatch.IContextProvider {
        private var d = file.lines()

        override fun getData(target: String?): MutableList<String> {
            val out = ArrayList<String>(d.size + 5)
            out.addAll(d)
            return out
        }

        override fun setData(target: String?, data: MutableList<String>) {
            d = data
        }

        fun getAsString() = d.joinToString("\n")
    }

    private enum class CommentState {
        CODE,
        STRING,
        CHAR,
        SINGLE_LINE,
        MULTI_LINE
    }

    private fun String.fixImports(): String {
        var text = this
        val match = PACKAGE.matcher(text)
        if (match.find()) {
            val pack = match.group(1)
            val match2 = IMPORT.matcher(text)
            while (match2.find()) {
                if (match2.group(1) == pack) {
                    text = text.replace(match2.group(), "")
                }
            }
        }
        return text
    }

    private fun String.stripComments(): String {
        var state = CommentState.CODE
        var i = 0
        var text: String
        StringWriter(length).use {
            while (i < length) {
                if (state == CommentState.CODE) {
                    it.write(this[i++].toString())
                } else if (state == CommentState.STRING || state == CommentState.CHAR) {
                    it.write(this[i++].toString())
                    val end = if (state == CommentState.STRING) {
                        '"'
                    } else {
                        '\''
                    }
                    while (i < length && this[i] != end) {
                        if (this[i] == '\\') {
                            it.write(this[i++].toString())
                        }
                        if (i >= length) {
                            break
                        }
                        it.write(this[i++].toString())
                    }
                } else if (state == CommentState.SINGLE_LINE) {
                    i += 2
                    while (i < length && this[i] != '\n' && this[i] != '\r') {
                        i++
                    }
                } else {
                    i += 2
                    while (i < length && (this[i] != '*' || this[i + 1] != '/')) {
                        i++
                    }
                    i += 2
                }
                var newState: CommentState? = null
                if (i < length) {
                    if (this[i] == '"') {
                        newState = CommentState.STRING
                    } else if (this[i] == '\'') {
                        newState = CommentState.CHAR
                    }
                }
                if (i + 1 < length && newState == null) {
                    if (this[i] == '/' && this[i + 1] == '/') {
                        newState = CommentState.SINGLE_LINE
                    } else if (this[i] == '/' && this[i + 1] == '*') {
                        newState = CommentState.MULTI_LINE
                    }
                }
                if (newState == null) {
                    newState = CommentState.CODE
                }
                state = newState
            }
            text = it.toString()
        }
        text = text.replace(TRAILING, "")
        text = text.replace(NEWLINES, System.getProperty("line.separator"))
        return text
    }

    companion object {
        private val TRAILING = "(?m)[ \\t]+\$".toRegex()
        private val NEWLINES = "(?m)^(\\r\\n|\\r|\\n){2,}".toRegex()
        private val PACKAGE = "(?m)^package ([\\w.]+);$".toPattern()
        private val IMPORT = "(?m)^import (?:([\\w.]*?)\\.)?(?:[\\w]+);(?:\\r\\n|\\r|\\n)".toPattern()
    }
}
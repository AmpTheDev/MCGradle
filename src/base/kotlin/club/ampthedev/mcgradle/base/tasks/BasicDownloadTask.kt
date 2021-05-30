package club.ampthedev.mcgradle.base.tasks

import club.ampthedev.mcgradle.base.utils.prepareDirectory
import club.ampthedev.mcgradle.base.utils.sha1
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URL

abstract class BasicDownloadTask(type: TaskType = TaskType.OTHER, vararg deps: String) : BaseTask(type, *deps) {
    abstract val url: String?
    abstract val dest: File?
    @Input
    open val sha1: String? = null

    protected open fun afterDownload() {}

    @TaskAction
    fun startDownload() {
        if (dest == null || url == null) {
            throw GradleException("Task wasn't set up correctly")
        }
        download(url!!, dest!!, sha1)
        afterDownload()
    }

    companion object {
        fun download(url: String, dest: File, sha1: String? = null, tries: Int = 0) {
            if (sha1 != null && dest.exists()) {
                if (sha1.equals(dest.sha1(), ignoreCase = true)) return
            }
            if (tries > 10) {
                throw GradleException("Failed to download $url")
            }
            val conn = URL(url).openConnection()
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:69.0) Gecko/20100101 Firefox/69.0")
            prepareDirectory(dest.parentFile)
            conn.getInputStream().use {
                dest.outputStream().use { out ->
                    out.write(it.readBytes())
                }
            }
            if (sha1 != null) {
                download(url, dest, sha1, tries + 1)
            }
        }
    }
}
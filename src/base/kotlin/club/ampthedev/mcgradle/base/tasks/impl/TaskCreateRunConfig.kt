package club.ampthedev.mcgradle.base.tasks.impl

import club.ampthedev.mcgradle.base.tasks.BaseTask
import club.ampthedev.mcgradle.base.utils.castTo
import club.ampthedev.mcgradle.base.utils.prepareDirectory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.plugins.ide.idea.model.IdeaModel
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys.*
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

open class TaskCreateRunConfig : BaseTask() {
    @Input
    lateinit var configName: String

    @Input
    lateinit var mainClass: String

    @Input
    val vmOptions = arrayListOf<String>()

    @Input
    val args = arrayListOf<String>()

    @Input
    var workingDirectory: File = project.projectDir.absoluteFile

    @Input
    val beforeRunTasks = arrayListOf<String>()

    @TaskAction
    fun generate() {
        var root = project.projectDir.canonicalFile
        var file: File? = null
        while (file == null && root != project.rootDir.canonicalFile.parentFile) {
            file = File(root, ".idea/workspace.xml")
            if (!file.exists()) {
                file = null
                for (f in root.listFiles() ?: error("...")) {
                    if (f.isFile && f.name.endsWith(".iws")) {
                        file = f
                        break
                    }
                }
            }
            root = root.parentFile ?: break
        }
        if (file == null || !file.exists()) {
            error("Couldn't find IntelliJ workspace file")
        }

        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()
        val doc = docBuilder.parse(file)

        var rootElement: Element? = null
        val list = doc.getElementsByTagName("component")
        for (i in 0 until list.length) {
            val e = list.item(i) as Element
            if (e.getAttribute("name") == "RunManager") {
                rootElement = e
                break
            }
        }

        if (rootElement == null) error("Couldn't find run manager")

        val children = rootElement.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child?.nodeName == "configuration" && child.attributes?.getNamedItem("name")?.nodeValue == configName) {
                rootElement.removeChild(child)
            }
        }

        val child = rootElement.addElement(
            "configuration",
            "name" to configName,
            "type" to "Application",
            "factoryName" to "Application",
            "default" to "false"
        )

        child.addElement(
            "extension", "name" to "coverage",
            "enabled" to "false",
            "sample_coverage" to "true",
            "runner" to "idea"
        )
        child.addElement("option", "name" to "MAIN_CLASS_NAME", "value" to mainClass)
        child.addElement("option", "name" to "PROGRAM_PARAMETERS", "value" to args.joinToString(" "))
        child.addElement("option", "name" to "VM_PARAMETERS", "value" to vmOptions.joinToString(" "))
        prepareDirectory(workingDirectory)
        child.addElement(
            "option",
            "name" to "WORKING_DIRECTORY",
            "value" to workingDirectory.absolutePath
        )
        val model = castTo<IdeaModel>(project.extensions.getByName("idea"))
        child.addElement(
            "module",
            "name" to "${model.module.name}.main"
        )
        val child2 = child.addElement("method", "v" to "2")
        for (task in beforeRunTasks) {
            child2.addElement(
                "option",
                "name" to "Gradle.BeforeRunTask",
                "enabled" to "true",
                "tasks" to task,
                "externalProjectPath" to project.projectDir.absolutePath
            )
        }
        child2.addElement("option", "name" to "Make", "enabled" to "true")

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(METHOD, "xml")
        transformer.setOutputProperty(INDENT, "yes")
        transformer.setOutputProperty(ENCODING, "UTF-8")
        transformer.setOutputProperty("{https://xml.apache.org/xslt}indent-amount", "4")

        val source = DOMSource(doc)
        val result = StreamResult(file)

        transformer.transform(source, result)
    }

    private fun Element.addElement(name: String, vararg pairs: Pair<String, String>): Element {
        val doc = ownerDocument ?: this as Document
        val e = doc.createElement(name)
        for (entry in pairs) {
            e.setAttribute(entry.first, entry.second)
        }
        appendChild(e)
        return e
    }
}
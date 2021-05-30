package club.ampthedev.mcgradle.base.tasks.impl

import club.ampthedev.mcgradle.base.tasks.BaseTask
import club.ampthedev.mcgradle.base.utils.*
import club.ampthedev.mcgradle.base.utils.mcpconfig.MCPConfigUtils
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InnerClassNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

open class TaskMergeJars : BaseTask(SPLIT_SERVER, DOWNLOAD_CLIENT) {
    @InputFile
    lateinit var clientJar: File

    @InputFile
    lateinit var serverJar: File

    @OutputFile
    lateinit var mergedJar: File

    @TaskAction
    fun merge() {
        if (project.newConfig) {
            MCPConfigUtils.runTask(
                project, "merge", mergedJar, File(temporaryDir, "merge.log"),
                "client" to clientJar.absolutePath,
                "server" to serverJar.absolutePath
            )
        } else {
            ZipFile(clientJar).use { cJar ->
                ZipFile(serverJar).use { sJar ->
                    ZipOutputStream(mergedJar.outputStream()).use { out ->
                        val resources = hashSetOf<String>()
                        val clientClasses = cJar.getClasses(out, resources)
                        val serverClasses = sJar.getClasses(out, resources)
                        val added = hashSetOf<String>()
                        for (entry in clientClasses) {
                            val name = entry.key
                            val entry1 = entry.value
                            val entry2 = serverClasses[name]
                            if (entry2 == null) {
                                entry1.copyClass(cJar, out, true)
                                added.add(name)
                                continue
                            }
                            serverClasses.remove(name)
                            val data1 = cJar.getInputStream(entry1).use { it.readBytes() }
                            val data2 = sJar.getInputStream(entry2).use { it.readBytes() }
                            val data = process(data1, data2)
                            val entry3 = ZipEntry(entry1.name)
                            out.putNextEntry(entry3)
                            out.write(data)
                            added.add(name)
                        }
                        for (entry in serverClasses) {
                            entry.value.copyClass(sJar, out, false)
                        }

                        for (name in arrayOf(SIDE_CLASS.name, SIDE_ENUM_CLASS.name)) {
                            val name1 = name.replace('.', '/')
                            val path = "$name1.class"
                            val entry1 = ZipEntry(path)
                            if (!added.contains(name1)) {
                                out.putNextEntry(entry1)
                                out.write(name1.bytes)
                            }
                        }
                    }
                }
            }
        }
    }

    private val String.bytes: ByteArray
        get() = TaskMergeJars::class.java.getResourceAsStream("/${this.replace('.', '/')}.class").use { it.readBytes() }

    private val ByteArray.node: ClassNode
        get() {
            val reader = ClassReader(this)
            val node = ClassNode()
            reader.accept(node, 0)
            return node
        }

    private fun process(data1: ByteArray, data2: ByteArray): ByteArray {
        val node1 = data1.node
        val node2 = data2.node

        processFields(node1, node2)
        processMethods(node1, node2)
        processInnerClasses(node1, node2)

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node1.accept(writer)
        return writer.toByteArray()
    }

    private fun InnerClassNode.matches(o2: InnerClassNode): Boolean {
        if (innerName == null && o2.innerName != null) return false
        if (innerName != null && innerName != o2.innerName) return false
        if (name == null && o2.name != null) return false
        if (name != null && name != o2.name) return false
        return if (outerName == null && o2.outerName != null) false else outerName == null || outerName != o2.outerName
    }

    private fun List<InnerClassNode>.doesNotContainInner(node: InnerClassNode): Boolean {
        for (n in this) {
            if (n.matches(node)) return false
        }
        return true
    }

    private fun processInnerClasses(c1: ClassNode, c2: ClassNode) {
        val i1 = c1.innerClasses
        val i2 = c2.innerClasses
        for (innerClassNode in i1) {
            if (i2.doesNotContainInner(innerClassNode)) i2.add(innerClassNode)
        }
        for (innerClassNode in i2) {
            if (i1.doesNotContainInner(innerClassNode)) i1.add(innerClassNode)
        }
    }

    private fun processMethods(c1: ClassNode, c2: ClassNode) {
        val m1 = c1.methods
        val m2 = c2.methods
        val all = linkedSetOf<Method>()

        var pos1 = 0
        var pos2 = 0
        val len1 = m1.size
        val len2 = m2.size
        var name1 = ""
        var lastName = name1
        var name2: String
        while (pos1 < len1 || pos2 < len2) {
            do {
                if (pos2 >= len2) {
                    break
                }
                val sm = m2[pos2]
                name2 = sm.name
                if (name2 != lastName && pos1 != len1) {
                    break
                }
                val m = Method(sm)
                m.server = true
                all.add(m)
                pos2++
            } while (pos2 < len2)
            do {
                if (pos1 >= len1) {
                    break
                }

                val cm = m1[pos1]
                lastName = name1
                name1 = cm.name
                if (name1 != lastName && pos2 != len2) {
                    break
                }
                val m = Method(cm)
                m.client = true
                all.add(m)
                pos1++
            } while (pos1 < len1)
        }

        m1.clear()
        m2.clear()

        for (m in all) {
            m1.add(m.node)
            m2.add(m.node)
            if (!(m.server && m.client)) {
                if (m.node.visibleAnnotations == null) m.node.visibleAnnotations = arrayListOf()
                m.node.visibleAnnotations.add(createAnnotation(m.client))
            }
        }
    }

    private fun processFields(c1: ClassNode, c2: ClassNode) {
        val f1 = c1.fields
        val f2 = c2.fields

        var serverFieldID = 0

        repeat(f1.size) { clientFieldID ->
            val cf = f1[clientFieldID]
            if (serverFieldID < f2.size) {
                val sf = f2[serverFieldID]
                if (cf.name != sf.name) {
                    var found = false
                    for (i in serverFieldID + 1 until f2.size) {
                        if (cf.name == f2[i].name) {
                            found = true
                            break
                        }
                    }

                    if (found) {
                        found = false
                        for (i in clientFieldID + 1 until f1.size) {
                            if (sf.name == f1[i].name) {
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            if (sf.visibleAnnotations == null) sf.visibleAnnotations = arrayListOf()
                            sf.visibleAnnotations.add(createAnnotation(false))
                            f1.add(clientFieldID, sf)
                        }
                    } else {
                        if (cf.visibleAnnotations == null) cf.visibleAnnotations = arrayListOf()
                        cf.visibleAnnotations.add(createAnnotation(true))
                        f2.add(serverFieldID, cf)
                    }
                }
            } else {
                if (cf.visibleAnnotations == null) cf.visibleAnnotations = arrayListOf()
                cf.visibleAnnotations.add(createAnnotation(true))
                f2.add(serverFieldID, cf)
            }
            serverFieldID++
        }
        if (f2.size != f1.size) {
            for (x in f1.size until f2.size) {
                val sf = f2[x]
                if (sf.visibleAnnotations == null) sf.visibleAnnotations = arrayListOf()
                sf.visibleAnnotations.add(createAnnotation(true))
                f1.add(x, sf)
            }
        }
    }

    private fun ZipEntry.copyClass(jar: ZipFile, out: ZipOutputStream, clientOnly: Boolean) {
        val reader = ClassReader(jar.getInputStream(this).use { it.readBytes() })
        val node = ClassNode()
        reader.accept(node, 0)
        if (node.visibleAnnotations == null) node.visibleAnnotations = arrayListOf()
        node.visibleAnnotations.add(createAnnotation(clientOnly))
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val entry = ZipEntry(name)
        out.putNextEntry(entry)
        out.write(writer.toByteArray())
    }

    private fun ZipFile.getClasses(out: ZipOutputStream, resources: MutableSet<String>): MutableMap<String, ZipEntry> {
        val rv = hashMapOf<String, ZipEntry>()
        for (entry in entries()) {
            val name = entry.name
            if (name == "META-INF/MANIFEST.MF") continue
            if (entry.isDirectory) continue
            if (!name.endsWith(".class") || name.startsWith(".")) {
                if (!resources.contains(name)) {
                    val entry1 = ZipEntry(name)
                    out.putNextEntry(entry1)
                    out.write(getInputStream(entry).use { it.readBytes() })
                    resources.add(name)
                }
            } else {
                rv[name.replace(".class", "")] = entry
            }
        }
        return rv
    }

    private fun createAnnotation(client: Boolean): AnnotationNode {
        val node = AnnotationNode(Type.getDescriptor(SIDE_CLASS))
        node.values =
            arrayListOf<Any>("value", arrayOf(Type.getDescriptor(SIDE_ENUM_CLASS), if (client) "CLIENT" else "SERVER"))
        return node
    }

    override fun setup() {
        if (!::clientJar.isInitialized) clientJar = File(project.string(CLIENT_JAR))
        if (!::serverJar.isInitialized) serverJar = File(project.string(SPLIT_SERVER_JAR))
        if (!::mergedJar.isInitialized) mergedJar = File(project.string(MERGED_JAR))
    }

    companion object {
        val SIDE_ENUM_CLASS = Side::class.java
        val SIDE_CLASS = SideOnly::class.java
    }

    private class Method(val node: MethodNode, var client: Boolean = false, var server: Boolean = false) {
        override fun equals(other: Any?): Boolean {
            if (other == null || other !is Method) {
                return false
            }
            if (node.name == other.node.name && node.desc == other.node.desc) {
                other.client = client or other.client
                other.server = server or other.server
                client = client or other.client
                server = server or other.server
            }
            return node.name == other.node.name && node.desc == other.node.desc
        }

        override fun hashCode() = Objects.hash(node.name, node.desc)
    }
}
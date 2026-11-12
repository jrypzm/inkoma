package inkoma.extension

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.SourceValue
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.tree.IincInsnNode
import java.util.logging.Level
import java.util.logging.Logger
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Class hierarchy built from class-file bytes only: the extension's own classes, then the host application's
 * classpath, then the JDK. Nothing is loaded or initialised, so no extension code runs while it is used.
 */
class ClassHierarchy(private val extensionClasses: Map<String, ByteArray>, private val host: ClassLoader) {
    data class Info(val superName: String?, val isInterface: Boolean, val interfaces: List<String>)

    private val cache = ConcurrentHashMap<String, Info>()
    private val missing = Info(null, false, emptyList())

    fun info(name: String): Info? {
        val cached = cache.getOrPut(name) { read(name) ?: missing }
        return if (cached === missing) null else cached
    }

    private fun read(name: String): Info? {
        val bytes = extensionClasses[name]
            ?: host.getResourceAsStream("$name.class")?.use { it.readBytes() }
            ?: jdkClass(name)
            ?: return null
        val reader = ClassReader(bytes)
        return Info(reader.superName, reader.access and Opcodes.ACC_INTERFACE != 0, reader.interfaces.toList())
    }

    private fun jdkClass(name: String): ByteArray? {
        if (!(name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/"))) return null
        return runCatching {
            val modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules")
            Files.list(modules).use { stream ->
                stream.map { it.resolve("$name.class") }.filter { Files.exists(it) }.findFirst().orElse(null)?.let { Files.readAllBytes(it) }
            }
        }.getOrNull()
    }

    fun isInterface(name: String): Boolean = info(name)?.isInterface ?: false

    /** Superclass chain starting with [name] itself; unknown classes are treated as direct children of Object. */
    fun superChain(name: String): List<String> {
        val chain = ArrayList<String>()
        var current: String? = name
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            chain.add(current)
            current = if (current == OBJECT) null else info(current)?.superName ?: OBJECT
        }
        if (chain.last() != OBJECT) chain.add(OBJECT)
        return chain
    }

    /** True if a value of type [from] can be assigned to [to] (class or interface). */
    fun isAssignable(to: String, from: String): Boolean {
        if (to == from || to == OBJECT) return true
        val toIsInterface = isInterface(to)
        val visited = HashSet<String>()
        val queue = ArrayDeque<String>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!visited.add(c)) continue
            if (c == to) return true
            val i = info(c) ?: continue
            i.superName?.let { queue.add(it) }
            if (toIsInterface) queue.addAll(i.interfaces)
        }
        return false
    }

    /** Same contract as [ClassWriter.getCommonSuperClass]. */
    fun commonSuperClass(a: String, b: String): String {
        if (a == b) return a
        if (isInterface(a) || isInterface(b)) {
            if (isAssignable(a, b)) return a
            if (isAssignable(b, a)) return b
            return OBJECT
        }
        val ancestorsOfA = superChain(a).toHashSet()
        return superChain(b).firstOrNull { it in ancestorsOfA } ?: OBJECT
    }

    companion object {
        const val OBJECT = "java/lang/Object"
    }
}

/**
 * Rewrites every class of a jar with freshly computed StackMapTable frames. dex2jar's own frame computation falls
 * back to the nonexistent type "java/util/Object" for classes outside the dex, which the JVM verifier rejects
 * ("Bad type on operand stack"). Frames are recomputed here against the real hierarchy instead.
 */
object FrameRecomputer {
    fun readClasses(jar: File): Map<String, ByteArray> = ZipFile(jar).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".class") }
            .associate { it.name.removeSuffix(".class") to zip.getInputStream(it).use { s -> s.readBytes() } }
    }

    fun recompute(input: File, output: File, host: ClassLoader) {
        val classes = readClasses(input)
        val hierarchy = ClassHierarchy(classes, host)
        val nodes = classes.mapValues { (_, bytes) ->
            ClassNode().also { ClassReader(bytes).accept(it, ClassReader.SKIP_FRAMES) }
        }
        synthesizeConstructors(nodes)
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val data = if (entry.name.endsWith(".class")) {
                        write(nodes.getValue(entry.name.removeSuffix(".class")), hierarchy)
                    } else {
                        zip.getInputStream(entry).use { it.readBytes() }
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(data)
                    out.closeEntry()
                }
            }
        }
    }

    /**
     * R8 deletes constructors that only forward to the superclass constructor and calls that superclass
     * constructor directly on the new object (valid on ART, not on the JVM). After the patched NewTransformer,
     * such sites read `new T; invokespecial T.<init>(D)` although T has no `<init>(D)`. Add forwarding
     * constructors to T (and to intermediate superclasses, up to the nearest ancestor that declares D), which
     * restores exactly the behaviour of the constructors R8 removed. Returns the number of constructors added.
     */
    fun synthesizeConstructors(nodes: Map<String, ClassNode>): Int {
        var added = 0
        fun hasCtor(node: ClassNode, desc: String) = node.methods.any { it.name == "<init>" && it.desc == desc }
        fun ensure(owner: String, desc: String) {
            val node = nodes[owner] ?: return // host or JDK class: constructor exists there
            if (hasCtor(node, desc)) return
            val superName = node.superName ?: return
            ensure(superName, desc)
            val ctor = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, "<init>", desc, null, null)
            ctor.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            var slot = 1
            for (arg in Type.getArgumentTypes(desc)) {
                ctor.instructions.add(VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot))
                slot += arg.size
            }
            ctor.instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", desc, false))
            ctor.instructions.add(InsnNode(Opcodes.RETURN))
            node.methods.add(ctor)
            added++
        }
        val needed = LinkedHashSet<Pair<String, String>>()
        // Inside constructors R8 may also call a grand-superclass <init> on `this`, skipping removed trivial
        // constructors. The JVM only allows this/direct-superclass <init> calls there, so retarget the call to the
        // direct superclass; the forwarding constructors are synthesized below.
        for (node in nodes.values) {
            val superName = node.superName ?: continue
            for (method in node.methods) {
                if (method.name != "<init>") continue
                val calls = method.instructions.toArray().filterIsInstance<MethodInsnNode>().filter {
                    it.opcode == Opcodes.INVOKESPECIAL && it.name == "<init>" && it.owner != node.name && it.owner != superName
                }
                if (calls.isEmpty()) continue
                val frames = analyze(node.name, method, SourceInterpreter()) ?: continue
                for (call in calls) {
                    val frame = frames[method.instructions.indexOf(call)] ?: continue
                    val receiver = frame.getStack(frame.stackSize - Type.getArgumentTypes(call.desc).size - 1)
                    val isThis = receiver.insns.isNotEmpty() && receiver.insns.all { it is VarInsnNode && it.opcode == Opcodes.ALOAD && it.`var` == 0 }
                    if (isThis) call.owner = superName
                }
            }
        }
        // Same pattern when dex2jar did not merge NEW with its constructor call (the object is kept in a local first):
        // `new T; astore; ...; aload; invokespecial S.<init>`. Trace each receiver back to its NEW and use T.
        for (node in nodes.values) {
            for (method in node.methods) {
                val newTypes = method.instructions.toArray().filter { it.opcode == Opcodes.NEW }.map { (it as TypeInsnNode).desc }.toSet()
                if (newTypes.isEmpty()) continue
                val initOwners = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                    .filter { it.opcode == Opcodes.INVOKESPECIAL && it.name == "<init>" }
                if (initOwners.isEmpty() || (newTypes.size == 1 && initOwners.all { it.owner in newTypes })) continue
                val frames = analyze(node.name, method, OriginInterpreter()) ?: continue
                for (call in initOwners) {
                    if (call.owner in newTypes && newTypes.size == 1) continue
                    val frame = frames[method.instructions.indexOf(call)] ?: continue
                    val receiver = frame.getStack(frame.stackSize - Type.getArgumentTypes(call.desc).size - 1)
                    val origins = receiver.insns
                    if (origins.isNotEmpty() && origins.all { it.opcode == Opcodes.NEW }) {
                        val types = origins.map { (it as TypeInsnNode).desc }.toSet()
                        if (types.size == 1 && types.first() != call.owner) call.owner = types.first()
                    }
                }
            }
        }
        for (node in nodes.values) {
            for (method in node.methods) {
                for (insn in method.instructions) {
                    if (insn is MethodInsnNode && insn.opcode == Opcodes.INVOKESPECIAL && insn.name == "<init>") {
                        needed.add(insn.owner to insn.desc)
                    }
                }
            }
        }
        needed.forEach { (owner, desc) -> ensure(owner, desc) }
        return added
    }

    /**
     * Data-flow analysis with safe bounds: dex2jar's max stack/locals can be too small for the analyzer, which would
     * otherwise fail and leave the method unfixed. Frames are recomputed when writing, so the bounds don't persist.
     */
    private fun analyze(owner: String, method: MethodNode, interpreter: SourceInterpreter): Array<Frame<SourceValue>?>? {
        var locals = Type.getArgumentsAndReturnSizes(method.desc) shr 2
        for (insn in method.instructions) {
            when (insn) {
                is VarInsnNode -> locals = maxOf(locals, insn.`var` + if (insn.opcode == Opcodes.LLOAD || insn.opcode == Opcodes.DLOAD || insn.opcode == Opcodes.LSTORE || insn.opcode == Opcodes.DSTORE) 2 else 1)
                is IincInsnNode -> locals = maxOf(locals, insn.`var` + 1)
            }
        }
        val savedStack = method.maxStack
        val savedLocals = method.maxLocals
        method.maxLocals = maxOf(savedLocals, locals)
        method.maxStack = maxOf(savedStack, 512)
        return try {
            Analyzer(interpreter).analyze(owner, method)
        } catch (e: Exception) {
            Logger.getLogger("FrameRecomputer").log(Level.WARNING, "Could not analyze $owner.${method.name}${method.desc}", e)
            null
        } finally {
            method.maxStack = savedStack
            method.maxLocals = savedLocals
        }
    }

    /** SourceInterpreter that keeps a value's origin through loads, stores and DUPs. */
    private class OriginInterpreter : SourceInterpreter(Opcodes.ASM9) {
        override fun copyOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue = value
    }

    private fun write(node: ClassNode, hierarchy: ClassHierarchy): ByteArray {
        // Java 8 class files: required for default/static interface methods; frames are computed below.
        node.version = maxOf(node.version and 0xFFFF, Opcodes.V1_8)
        val writer = object : ClassWriter(COMPUTE_FRAMES) {
            override fun getCommonSuperClass(type1: String, type2: String): String = hierarchy.commonSuperClass(type1, type2)
        }
        node.accept(writer)
        return writer.toByteArray()
    }
}

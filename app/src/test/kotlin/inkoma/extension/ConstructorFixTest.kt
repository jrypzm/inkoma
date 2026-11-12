package inkoma.extension

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reproduces the two R8 patterns that dex2jar output violates on the JVM ("Bad type on operand stack",
 * "Bad <init> method call") and checks that FrameRecomputer makes them verify and behave as on Android.
 */
class ConstructorFixTest {
    private fun cls(name: String, superName: String, block: ClassWriter.() -> Unit = {}): ByteArray =
        ClassWriter(ClassWriter.COMPUTE_FRAMES).apply {
            visit(V1_8, ACC_PUBLIC, name, null, superName, null)
            block()
            visitEnd()
        }.toByteArray()

    @Test
    fun r8ConstructorPatternsVerifyAfterFix() {
        // Base(String) stores the value; Mid extends Base with its trivial constructor removed by R8.
        val base = cls("fix/Base", "java/lang/Object") {
            visitField(ACC_PUBLIC, "v", "Ljava/lang/String;", null, null).visitEnd()
            visitMethod(ACC_PROTECTED, "<init>", "(Ljava/lang/String;)V", null, null).apply {
                visitCode(); visitVarInsn(ALOAD, 0); visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(ALOAD, 0); visitVarInsn(ALOAD, 1); visitFieldInsn(PUTFIELD, "fix/Base", "v", "Ljava/lang/String;")
                visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        val mid = cls("fix/Mid", "fix/Base")
        // Leaf extends Mid; its constructor calls Base.<init> on `this` (grand-superclass call).
        val leaf = cls("fix/Leaf", "fix/Mid") {
            visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null).apply {
                visitCode(); visitVarInsn(ALOAD, 0); visitVarInsn(ALOAD, 1)
                visitMethodInsn(INVOKESPECIAL, "fix/Base", "<init>", "(Ljava/lang/String;)V", false)
                visitInsn(RETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        // Factory.make() does `new Mid; Mid.<init>(String)` although Mid declares no constructor (patched dex2jar output).
        val factory = cls("fix/Factory", "java/lang/Object") {
            visitMethod(ACC_PUBLIC or ACC_STATIC, "make", "()Ljava/lang/String;", null, null).apply {
                visitCode(); visitTypeInsn(NEW, "fix/Mid"); visitInsn(DUP); visitLdcInsn("mid")
                visitMethodInsn(INVOKESPECIAL, "fix/Mid", "<init>", "(Ljava/lang/String;)V", false)
                visitFieldInsn(GETFIELD, "fix/Base", "v", "Ljava/lang/String;")
                visitTypeInsn(NEW, "fix/Leaf"); visitInsn(DUP); visitLdcInsn("leaf")
                visitMethodInsn(INVOKESPECIAL, "fix/Leaf", "<init>", "(Ljava/lang/String;)V", false)
                visitFieldInsn(GETFIELD, "fix/Base", "v", "Ljava/lang/String;")
                visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                // Unmerged variant: `new Mid; astore; aload; invokespecial Base.<init>` (receiver kept in a local).
                visitTypeInsn(NEW, "fix/Mid"); visitVarInsn(ASTORE, 0); visitVarInsn(ALOAD, 0); visitLdcInsn("!")
                visitMethodInsn(INVOKESPECIAL, "fix/Base", "<init>", "(Ljava/lang/String;)V", false)
                visitVarInsn(ALOAD, 0); visitFieldInsn(GETFIELD, "fix/Base", "v", "Ljava/lang/String;")
                visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                visitInsn(ARETURN); visitMaxs(0, 0); visitEnd()
            }
        }
        val work = Files.createTempDirectory("ctor").toFile()
        val input = File(work, "in.jar")
        ZipOutputStream(input.outputStream()).use { zos ->
            mapOf("fix/Base" to base, "fix/Mid" to mid, "fix/Leaf" to leaf, "fix/Factory" to factory).forEach { (n, b) ->
                zos.putNextEntry(ZipEntry("$n.class")); zos.write(b); zos.closeEntry()
            }
        }
        val output = File(work, "out.jar")
        FrameRecomputer.recompute(input, output, javaClass.classLoader)
        URLClassLoader(arrayOf(output.toURI().toURL()), javaClass.classLoader).use { loader ->
            listOf("fix.Base", "fix.Mid", "fix.Leaf", "fix.Factory").forEach { Class.forName(it, false, loader).declaredMethods }
            val result = Class.forName("fix.Factory", true, loader).getMethod("make").invoke(null)
            assertEquals("midleaf!", result)
        }
        work.deleteRecursively()
    }
}

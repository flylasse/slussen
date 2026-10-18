package slussen.architecture

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import slussen.architecture.Sources.failOn
import slussen.architecture.Sources.requireScope
import java.io.File

/**
 * The bytecode rules B-01 to B-07 of ADR-001 §6.3, read with ASM from the compiled classes of the main source sets of
 * every production module (every module except `:testing` and `:architecture-tests`), which build-logic hands to this
 * test task as inputs (ADR-001 §6.4). They see resolved references: fully qualified calls, inlined functions and calls
 * through `kotlin.io` extensions alike. Every rule first asserts that its scope holds at least one class.
 */
class BytecodeRulesTest {
    @Test
    @DisplayName("B-01: classes of :fileformat-* open nothing, read no clock, reflect on nothing and keep no state")
    fun b01() {
        val scope = requireScope("B-01", Classes.all.filter { it.module.startsWith("fileformat-") })
        val forbidden = listOf(
            "java/net/", "java/nio/file/", "java/nio/channels/", "java/io/File", "java/io/RandomAccessFile",
            "java/lang/Thread", "java/lang/reflect/", "kotlin/reflect/", "okhttp3/", "kotlinx/coroutines/",
        )
        val violations = scope.flatMap { cls ->
            cls.references.filter { ref -> forbidden.any { ref.startsWith(it) } }.map { "${cls.name} references $it" } +
                cls.invocations.filter { (owner, name) ->
                    (owner == "java/lang/System" && (name == "currentTimeMillis" || name == "nanoTime")) ||
                        (owner == "java/lang/Class" && name == "forName") ||
                        (owner.startsWith("java/time/") && name == "now")
                }.map { (owner, name) -> "${cls.name} calls $owner.$name" } +
                cls.nonFinalFields.map { "${cls.name} has the non-final field $it" }
        }
        failOn("B-01", violations)
    }

    @Test
    @DisplayName("B-02: android.util.Log only in slussen.app.platform.log; no standard output and no printStackTrace")
    fun b02() {
        val scope = requireScope("B-02", Classes.all)
        val violations = scope.flatMap { cls ->
            val log = if (!cls.name.startsWith("slussen/app/platform/log/") && "android/util/Log" in cls.references) {
                listOf("${cls.name} references android/util/Log")
            } else {
                emptyList()
            }
            val standardStreams = cls.fieldReads
                .filter { (owner, name) -> owner == "java/lang/System" && (name == "out" || name == "err") }
                .map { (owner, name) -> "${cls.name} reads $owner.$name" }
            val printing = cls.invocations.filter { (owner, name) ->
                (owner == "java/io/PrintStream" && name.startsWith("print")) ||
                    (owner == "kotlin/io/ConsoleKt" && name.startsWith("print")) ||
                    name == "printStackTrace"
            }.map { (owner, name) -> "${cls.name} calls $owner.$name" }
            log + standardStreams + printing
        }
        failOn("B-02", violations)
    }

    @Test
    @DisplayName("B-03: classes in slussen.app.ui reference nothing of :persistence or a plug-in")
    fun b03() {
        val scope = requireScope("B-03", Classes.all.filter { it.name.startsWith("slussen/app/ui/") })
        val forbidden = listOf("slussen/persistence/", "slussen/provider/", "slussen/budgetapp/", "slussen/fileformat/")
        failOn(
            "B-03",
            scope.flatMap { cls ->
                cls.references.filter { ref -> forbidden.any { ref.startsWith(it) } }.map { "${cls.name} references $it" }
            },
        )
    }

    @Test
    @DisplayName("B-04: only AdmittedBudgetAppWriter writes; BudgetAppWriter only in its allowed places")
    fun b04() {
        val scope = requireScope("B-04", Classes.all)
        val writer = "slussen/pluginapi/budgetapp/BudgetAppWriter"
        val admitted = "slussen/core/upload/AdmittedBudgetAppWriter"
        val violations = scope.flatMap { cls ->
            val writes = cls.invocations
                .filter { (owner, name) -> owner == writer && (name == "create" || name == "markCleared") }
                .filterNot { cls.name == admitted || cls.name.startsWith("$admitted$") }
                .map { (_, name) -> "${cls.name} calls BudgetAppWriter.$name" }
            val allowedPlace = cls.module == "plugin-api" || cls.module.startsWith("budgetapp-") ||
                cls.name.startsWith("slussen/core/upload/") || cls.name.startsWith("slussen/app/di/")
            val type = if (!allowedPlace && writer in cls.references) {
                listOf("${cls.name} references BudgetAppWriter")
            } else {
                emptyList()
            }
            writes + type
        }
        failOn("B-04", violations)
    }

    @Test
    @DisplayName("B-05: classes in slussen.app.schedule reference nothing in slussen.core.upload")
    fun b05() {
        val scope = requireScope("B-05", Classes.all.filter { it.name.startsWith("slussen/app/schedule/") })
        failOn(
            "B-05",
            scope.flatMap { cls ->
                cls.references.filter { it.startsWith("slussen/core/upload/") }.map { "${cls.name} references $it" }
            },
        )
    }

    @Test
    @DisplayName("B-06: Secret.reveal and ConsentSession.reveal are called only from plug-in modules and :persistence")
    fun b06() {
        val scope = requireScope("B-06", Classes.all)
        val revealing = setOf("slussen/pluginapi/value/Secret", "slussen/pluginapi/provider/ConsentSession")
        val allowedModule = { module: String ->
            module == "persistence" || module.startsWith("provider-") || module.startsWith("budgetapp-") ||
                module.startsWith("fileformat-")
        }
        failOn(
            "B-06",
            scope.filterNot { allowedModule(it.module) }.flatMap { cls ->
                cls.invocations.filter { (owner, name) -> owner in revealing && name == "reveal" }
                    .map { (owner, _) -> "${cls.name} calls $owner.reveal" }
            },
        )
    }

    @Test
    @DisplayName("B-07: within :core, MessageDigest only in slussen.core.identity")
    fun b07() {
        val scope = requireScope("B-07", Classes.all.filter { it.module == "core" })
        failOn(
            "B-07",
            scope.filterNot { it.name.startsWith("slussen/core/identity/") }
                .filter { "java/security/MessageDigest" in it.references }
                .map { "${it.name} references java/security/MessageDigest" },
        )
    }
}

/** What the bytecode rules need to know of one compiled class. */
class ClassFacts(val module: String, val name: String) {
    val references = mutableSetOf<String>()
    val invocations = mutableSetOf<Pair<String, String>>()
    val fieldReads = mutableSetOf<Pair<String, String>>()
    val nonFinalFields = mutableListOf<String>()
}

/** The compiled classes of the production modules, as build-logic hands them to the test task (ADR-001 §6.4). */
object Classes {
    val all: List<ClassFacts> by lazy {
        val modules = System.getProperty("slussen.productionModules")?.split(',')?.filter { it.isNotBlank() }
            ?: error("slussen.productionModules is set by build-logic for :architecture-tests:test (ADR-001 §6.4).")
        check(modules.toSet() == Sources.appModules.toSet() - setOf("testing", "architecture-tests")) {
            "The bytecode rules must read every production module of ADR-001 §1; build-logic handed $modules."
        }
        modules.flatMap { module ->
            val directories = System.getProperty("slussen.classes.$module").orEmpty()
                .split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
            check(directories.isNotEmpty()) { "No compiled classes were handed over for :$module." }
            directories.flatMap { directory ->
                directory.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                    .map { read(module, it.readBytes()) }.toList()
            }
        }
    }

    fun read(module: String, bytes: ByteArray): ClassFacts {
        val reader = ClassReader(bytes)
        val facts = ClassFacts(module, reader.className)
        reader.accept(Collector(facts), ClassReader.SKIP_FRAMES)
        return facts
    }

    private class Collector(private val facts: ClassFacts) : ClassVisitor(Opcodes.ASM9) {
        fun type(type: Type) {
            when (type.sort) {
                Type.OBJECT -> facts.references += type.internalName
                Type.ARRAY -> type(type.elementType)
                Type.METHOD -> {
                    type.argumentTypes.forEach(::type)
                    type(type.returnType)
                }
            }
        }

        fun internal(name: String?) {
            if (name == null) return
            if (name.startsWith("[")) type(Type.getType(name)) else facts.references += name
        }

        fun typesOf(desc: String?) {
            if (desc != null) type(Type.getType(desc))
        }

        fun constant(value: Any?) {
            when (value) {
                is Type -> type(value)
                is Handle -> {
                    internal(value.owner)
                    typesOf(value.desc)
                    facts.invocations += value.owner to value.name
                }
            }
        }

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            internal(superName)
            interfaces?.forEach(::internal)
        }

        override fun visitField(access: Int, name: String, desc: String, signature: String?, value: Any?): FieldVisitor? {
            typesOf(desc)
            if (access and Opcodes.ACC_FINAL == 0) facts.nonFinalFields += name
            return null
        }

        override fun visitMethod(
            access: Int,
            name: String,
            desc: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            typesOf(desc)
            exceptions?.forEach(::internal)
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitTypeInsn(opcode: Int, type: String) = internal(type)

                override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                    internal(owner)
                    typesOf(desc)
                    if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD) facts.fieldReads += owner to name
                }

                override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) {
                    internal(owner)
                    typesOf(desc)
                    facts.invocations += owner to name
                }

                override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg args: Any?) {
                    typesOf(desc)
                    constant(bsm)
                    args.forEach(::constant)
                }

                override fun visitLdcInsn(value: Any?) = constant(value)

                override fun visitMultiANewArrayInsn(desc: String, numDimensions: Int) = typesOf(desc)

                override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) = internal(type)

                override fun visitLocalVariable(
                    name: String,
                    desc: String,
                    signature: String?,
                    start: Label,
                    end: Label,
                    index: Int,
                ) = typesOf(desc)
            }
        }
    }
}

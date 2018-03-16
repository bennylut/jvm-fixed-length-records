package com.github.bennylut.flr

import com.github.bennylut.util.*
import net.openhft.chronicle.core.Memory
import net.openhft.chronicle.core.OS
import net.openhft.chronicle.core.UnsafeMemory
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.util.CheckClassAdapter
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl
import java.io.PrintWriter
import java.lang.Integer.max
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod

interface FixedLengthRecord {
    fun size(): Int

    //TODO: move those into an hidden interface
    fun _ref(address: Long)

    fun _ptr(): Long
    fun _dup(): FixedLengthRecord

    object Factory {
        private val INAME_OBJECT = Any::class.iname()
        private val INAME_FLR = FixedLengthRecord::class.iname()
        private val IDESC_FLR = FixedLengthRecord::class.idesc()
        private val INAME_BASE_VECTOR = BaseVector::class.iname()
        private val INAME_FACTORY = Factory::class.iname()
        private val IDESC_FACTORY = Factory::class.idesc()
        private val IDESC_MEMORY = Memory::class.idesc()
        private val INAME_MEMORY = Memory::class.iname()
        private val IDESC_CREATE_FUN = "(${Class::class.java.idesc()})${FixedLengthRecord::class.java.idesc()}"


        @JvmStatic
        private val MEMORY = OS.memory()

        private val precompiled = ConcurrentHashMap<Class<*>, CompiledRecordFactory>()
        private val compilationStack = HashSet<Class<*>>()


        fun <T : FixedLengthRecord> create(type: KClass<T>) = create(type.java)

        fun <T : FixedLengthRecord> create(type: Class<T>): T {
            return (precompiled[type] ?: compile(type)).newInstance().cast()
        }

        @Synchronized
        fun <T : FixedLengthRecord> compile(type: Class<T>): CompiledRecordFactory {
            if (!compilationStack.add(type)) throw CompilationException("recursion detected in compilation of $type")

            try {
                return precompiled.computeIfAbsent(type) {
                    val annot = type.getAnnotation(Layout::class.java)
                            ?: throw CompilationException("missing layout annotation")
                    val layout = ParsedLayout(annot.layoutString)
                    doCompile(RecordAST.parse(type, layout))
                }

            } finally {
                compilationStack.remove(type)
            }
        }

        @Synchronized
        fun <T : FixedLengthRecord> compileDynamicLayout(type: Class<T>, layoutString: String): CompiledRecordFactory {
            val layout = ParsedLayout(layoutString)
            if (!compilationStack.add(type)) throw CompilationException("recursion detected in compilation of $type")

            try {
                return doCompile(RecordAST.parse(type, layout))
            } finally {
                compilationStack.remove(type)
            }
        }


        private fun doCompile(ast: RecordAST): CompiledRecordFactory {
            val iname = ast.type.simpleName
            val bytecode = ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS).apply {
                visit(V1_5, ACC_PUBLIC + ACC_SUPER, iname, null, INAME_OBJECT, arrayOf(ast.type.iname()))

                writeFields(ast)
                writeConstructor(iname, ast)

                for (field in ast) {
                    writeFieldAccessor(iname, field)
                }

                writeRef(iname, ast)
                writeDup(iname, ast)
                writeSize(iname, ast)
                writePtr(iname, ast)

                visitEnd()
            }.toByteArray()

            //TODO: this is only for testing...
            CheckClassAdapter.verify(ClassReader(bytecode), false, PrintWriter(System.out))

            val clazz = UnsafeMemory.UNSAFE.defineAnonymousClass(Factory::class.java, bytecode, null)
            return CompiledRecordFactory(clazz, ast.size)
        }

        private fun ClassWriter.writeRef(iname: String, ast: RecordAST) {
            visitMethod(ACC_PUBLIC, "_ref", "(J)V", null, null).apply {
                visitCode()

                //first update my own pointer
                visitVarInsn(ALOAD, 0)
                visitVarInsn(LLOAD, 1)
                visitFieldInsn(PUTFIELD, iname, "pointer", "J")

                //now update the rest of the pointers
                for (field in ast) {
                    if (field.isVector || field.isRecord()) {
                        visitVarInsn(ALOAD, 0)
                        visitFieldInsn(GETFIELD, iname, field.name, field.type.idesc())

                        visitVarInsn(LLOAD, 1)
                        visitLdcInsn(field.offset)
                        visitInsn(LADD)

                        if (field.isVector) {
                            visitMethodInsn(INVOKEVIRTUAL, INAME_BASE_VECTOR, "_ref", "(J)V", false)
                        } else {
                            visitMethodInsn(INVOKEINTERFACE, INAME_FLR, "_ref", "(J)V", true)
                        }

                    }
                }

                visitInsn(RETURN)
                visitMaxs(-1, -1)
                visitEnd()
            }
        }

        private fun ClassWriter.writeDup(iname: String, ast: RecordAST) {
            visitMethod(ACC_PUBLIC, "_dup", "()${IDESC_FLR}", null, null).apply {
                visitCode()

                //crate new
                visitTypeInsn(NEW, iname)
                visitInsn(DUP)
                visitMethodInsn(INVOKESPECIAL, iname, "<init>", "()V", false)

                visitInsn(ARETURN)
                visitMaxs(-1, -1)
                visitEnd()
            }
        }

        private fun ClassWriter.writePtr(iname: String, ast: RecordAST) {
            visitMethod(ACC_PUBLIC, "_ptr", "()J", null, null).apply {
                visitCode()

                visitVarInsn(ALOAD, 0)
                visitFieldInsn(GETFIELD, iname, "pointer", "J")

                visitInsn(LRETURN)
                visitMaxs(-1, -1)
                visitEnd()
            }
        }

        private fun ClassWriter.writeSize(iname: String, ast: RecordAST) {
            visitMethod(ACC_PUBLIC, "size", "()I", null, null).apply {
                visitCode()

                visitLdcInsn(ast.size)

                visitInsn(IRETURN)
                visitMaxs(-1, -1)
                visitEnd()
            }
        }

        private fun ClassWriter.writeFieldAccessor(iname: String, field: RecordField) {
            val getter = field.getter
            val setter = field.setter
            val type = field.type
            val typeName = type.simpleName.capitalize()

            if (field.isRecord() || field.isVector) {
                //complex getter
                visitMethod(ACC_PUBLIC, getter.name, getter.idesc(), null, null).apply {
                    visitCode()

                    visitVarInsn(ALOAD, 0)
                    visitFieldInsn(GETFIELD, iname, field.name, field.type.idesc())

                    visitInsn(type.returnInsn())
                    visitMaxs(-1, -1)
                    visitEnd()
                }
            } else {
                //primitive getter
                visitMethod(ACC_PUBLIC, getter.name, getter.idesc(), null, null).apply {
                    visitCode()
                    visitFieldInsn(GETSTATIC, INAME_FACTORY, "MEMORY", IDESC_MEMORY)

                    pushFieldOffset(iname, field)

                    visitMethodInsn(INVOKEINTERFACE, INAME_MEMORY, "read$typeName", "(J)${type.idesc()}", true)

                    visitInsn(type.returnInsn())
                    visitMaxs(-1, -1)
                    visitEnd()
                }
            }

            if (setter != null) {
                //primitive setter only
                visitMethod(ACC_PUBLIC, setter.name, setter.idesc(), null, null).apply {
                    visitCode()
                    visitFieldInsn(GETSTATIC, INAME_FACTORY, "MEMORY", IDESC_MEMORY)

                    pushFieldOffset(iname, field)

                    //push value to update
                    visitVarInsn(type.loadInsn(), 1)

                    //update
                    visitMethodInsn(INVOKEINTERFACE, INAME_MEMORY, "write$typeName", "(J${type.idesc()})V", true)

                    visitInsn(RETURN)
                    visitMaxs(-1, -1)
                    visitEnd()
                }
            }
        }

        private fun MethodVisitor.pushFieldOffset(iname: String, field: RecordField) {
            //compute location of the field: offset + pointer
            visitLdcInsn(field.offset)
            visitVarInsn(ALOAD, 0)
            visitFieldInsn(GETFIELD, iname, "pointer", "J")
            visitInsn(LADD)
        }

        private fun ClassWriter.writeFields(ast: RecordAST) {
            visitField(ACC_PRIVATE, "pointer", "J", null, -1L)

            for (field in ast) {
                if (field.isVector || field.isRecord()) {
                    visitField(ACC_PRIVATE or ACC_FINAL, field.name, field.type.idesc(), null, null)
                }
            }
        }

        private fun ClassWriter.writeConstructor(iname: String, ast: RecordAST) {
            visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(ALOAD, 0)
                visitMethodInsn(INVOKESPECIAL, INAME_OBJECT, "<init>", "()V", false)

                for (field in ast) {
                    if (field.isVector || field.isRecord()) {
                        //put this on stack
                        visitVarInsn(ALOAD, 0)

                        //put new value on the stack
                        if (field.isVector) {
                            val fieldTypeIname = field.type.iname()
                            visitTypeInsn(NEW, fieldTypeIname)
                            visitInsn(DUP)

                            visitLdcInsn(field.multiplicity)

                            if (!field.componentType.isPrimitive) {
                                visitFieldInsn(GETSTATIC, INAME_FACTORY, "INSTANCE", IDESC_FACTORY)
                                visitLdcInsn(Type.getType(field.componentType))
                                visitMethodInsn(INVOKEVIRTUAL, INAME_FACTORY, "create", IDESC_CREATE_FUN, false)
                                visitMethodInsn(INVOKESPECIAL, fieldTypeIname, "<init>", "(I${IDESC_FLR})V", false)
                            } else {
                                visitMethodInsn(INVOKESPECIAL, fieldTypeIname, "<init>", "(I)V", false)
                            }
                        } else {
                            visitFieldInsn(GETSTATIC, INAME_FACTORY, "INSTANCE", IDESC_FACTORY)
                            visitLdcInsn(Type.getType(field.type))
                            visitMethodInsn(INVOKEVIRTUAL, INAME_FACTORY, "create", IDESC_CREATE_FUN, false)
                        }

                        //put it into the field
                        visitFieldInsn(PUTFIELD, iname, field.name, field.type.idesc())
                    }

                }

                visitInsn(RETURN)
                visitMaxs(-1, -1)
                visitEnd()
            }
        }

    }


    private class ParsedLayout(layout: String) {
        private val fields: Array<String>
        private val multiplicity: IntArray

        init {
            val parsed = layout.split("\\s*,\\s*".toRegex())
                    .map {
                        if (it.endsWith("]")) {
                            val bpos = it.indexOf('[')
                            if (bpos < 0) throw CompilationException("invalid layout syntax")

                            val multiplicity = try {
                                it.substring(bpos + 1, it.length - 1).toInt()
                            } catch (nfe: NumberFormatException) {
                                throw CompilationException("invalid layout syntax: ... $it ...", nfe)
                            }

                            it.substring(0, bpos) to multiplicity
                        } else {
                            it to 0
                        }
                    }

            fields = Array<String>(parsed.size) { parsed[it].first }
            multiplicity = IntArray(parsed.size) { parsed[it].second }
        }

        val numFields = fields.size
        fun field(i: Int) = fields[i]
        fun multiplicity(i: Int) = multiplicity[i]

    }

    private class RecordAST(
            private var fields: Map<String, RecordField>,
            val size: Int,
            val type: Class<*>) : Iterable<RecordField> {

        override fun iterator(): Iterator<RecordField> = fields.values.iterator()

        operator fun get(field: String) = fields[field]

        companion object {

            private val FLR_METHODS = FixedLengthRecord::class.java.declaredMethods.toSet()

            private fun isSupported(type: Class<*>): Boolean =
                    type.isPrimitive
                            || BaseVector::class.java.isAssignableFrom(type)
                            || FixedLengthRecord::class.java.isAssignableFrom(type)


            fun parse(type: Class<*>, layout: ParsedLayout): RecordAST {

                if (!FixedLengthRecord::class.java.isAssignableFrom(type))
                    throw CompilationException("illegal interface given: $type does not implements ${FixedLengthRecord::class.java}")

                val fields = HashMap<String, RecordField>()
                val ktype = type.kotlin

                //check that this type has no special methods
                for (func in ktype.memberFunctions) {
                    if (!func.isAbstract || func.javaMethod in FLR_METHODS) continue
                    throw CompilationException("could not implement function $func")
                }

                for (property in ktype.memberProperties) {
                    val field = fields.getOrPut(property.name) {
                        RecordField()
                    }

                    field.getter = property.javaGetter!!
                    field.name = field.getter.name.substring(3).decapitalize()
                    field.type = field.getter.returnType
                    field.isVector = BaseVector::class.java.isAssignableFrom(field.type)
                    if (field.isVector) {
                        field.componentType = when (field.type) {
                            IntVector::class.java -> Int::class.javaPrimitiveType
                            LongVector::class.java -> Long::class.javaPrimitiveType
                            DoubleVector::class.java -> Double::class.javaPrimitiveType
                            FloatVector::class.java -> Float::class.javaPrimitiveType
                            ByteVector::class.java -> Byte::class.javaPrimitiveType
                            Vector::class.java -> ((field.getter.genericReturnType as ParameterizedTypeImpl).actualTypeArguments[0]).cast()
                            else -> throw java.lang.UnsupportedOperationException("unknown vector type: ${field.type}")
                        }!!
                    } else {
                        field.componentType = field.type
                    }

                    if (property is KMutableProperty<*>) {
                        field.setter = property.setter.javaMethod
                    }
                }

                for (field in fields.values) {
                    if (!isSupported(field.type)) throw CompilationException("field:${field.name} has unsupported type: ${field.type}")
                }

                val fieldNames = fields.keys.toHashSet()

                //fill multiplicity and offset (also check linkability)
                var offset = 0L
                for (i in 0 until layout.numFields) {
                    val field = fields[layout.field(i)]
                            ?: throw CompilationException("layout cannot be linked, field:${layout.field(i)} defined in the interface but not in the layout string")

                    val m = layout.multiplicity(i)
                    if (m == 0 && field.isVector) {
                        throw UnsupportedOperationException("layout cannot be linked, $field is defined in the layout to be scalar but defined to be vector in the interface")
                    }

                    if (m > 0 && !field.isVector) {
                        throw UnsupportedOperationException("layout cannot be linked, $field is defined in the layout to be vector but defined to be scalar in the interface")
                    }

                    field.multiplicity = m
                    field.offset = offset
                    fieldNames.remove(field.name)

                    offset += field.size()
                }

                if (fieldNames.isNotEmpty()) {
                    throw CompilationException("layout cannot be linked, fields:$fieldNames defined in the interface but not in the layout string")
                }

                //check that complex fields only has getters
                for (field in fields.values) {
                    if ((field.isRecord() || field.isVector) && field.setter != null) throw CompilationException("complex fields cannot have setters: ${field.name}")
                }

                return RecordAST(fields, offset.toInt(), type)
            }
        }
    }

    private class RecordField {

        lateinit var getter: Method
        var setter: Method? = null
        var isVector: Boolean = false
        var multiplicity: Int = -1
        var offset: Long = -1
        var type: Class<*> = Void::class.java
        var componentType: Class<*> = type

        fun isRecord() = !type.isPrimitive

        var name: String = "UNNAMED"

        private fun scalarSize(): Int {
            if (!componentType.isPrimitive) {
                if (FixedLengthRecord::class.java.isAssignableFrom(componentType)) return Factory.compile<FixedLengthRecord>(componentType.cast()).size
                else throw UnsupportedOperationException("will not calculate size for non primitive type: $componentType")
            }

            return when (componentType) {
                Int::class.javaPrimitiveType -> 4
                Long::class.javaPrimitiveType -> 8
                Double::class.javaPrimitiveType -> 8
                Float::class.javaPrimitiveType -> 4
                Short::class.javaPrimitiveType -> 2
                Byte::class.javaPrimitiveType -> 1
                else -> throw java.lang.UnsupportedOperationException("unknown type for sizeof: $componentType")
            }
        }

        fun size(): Int = scalarSize() * max(1, multiplicity)

    }

}


class CompiledRecordFactory(val type: Class<*>, val size: Int) {
    fun newInstance() = type.newInstance()
}


class CompilationException : RuntimeException {
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
}

@Target(AnnotationTarget.CLASS)
annotation class Layout(val layoutString: String)

fun <T : FixedLengthRecord> KClass<T>.create() = FixedLengthRecord.Factory.create(this)
inline fun <T : FixedLengthRecord> T.cloneRef(): T {
    val dup = _dup()
    dup._ref(_ptr())
    return dup.cast()
}

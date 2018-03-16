package com.github.bennylut.util

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.lang.UnsupportedOperationException
import java.lang.reflect.Method
import kotlin.reflect.KClass

internal fun KClass<*>.iname() = java.iname()
internal fun KClass<*>.idesc() = java.idesc()
internal fun Class<*>.iname() = Type.getInternalName(this)

/**
 * For primitives:
 * Int -> "I"
 * Long -> "J"
 * Double -> "D"
 * Float -> "F"
 * Byte -> "B"
 * Char -> "C"
 * Boolean -> "Z"
 * Void -> "V"
 */
internal fun Class<*>.idesc() = Type.getDescriptor(this)

internal fun Method.idesc() = Type.getMethodDescriptor(this)

internal fun Class<*>.loadInsn(): Int {
    if (!this.isPrimitive) return ALOAD

    return when (this) {
        Int::class.javaPrimitiveType -> ILOAD
        Long::class.javaPrimitiveType -> LLOAD
        Double::class.javaPrimitiveType -> DLOAD
        Float::class.javaPrimitiveType -> FLOAD
        Short::class.javaPrimitiveType -> ILOAD
        Byte::class.javaPrimitiveType -> ILOAD
        Boolean::class.javaPrimitiveType -> ILOAD
        else -> throw UnsupportedOperationException("unknown type for load: $this")
    }
}

internal fun Class<*>.returnInsn(): Int {
    if (!this.isPrimitive) return ARETURN

    return when (this) {
        Void.TYPE -> RETURN
        Int::class.javaPrimitiveType -> IRETURN
        Long::class.javaPrimitiveType -> LRETURN
        Double::class.javaPrimitiveType -> DRETURN
        Float::class.javaPrimitiveType -> FRETURN
        Short::class.javaPrimitiveType -> IRETURN
        Byte::class.javaPrimitiveType -> IRETURN
        Boolean::class.javaPrimitiveType -> IRETURN
        else -> throw UnsupportedOperationException("unknown type for return: $this")
    }
}
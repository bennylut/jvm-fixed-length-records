package com.github.bennylut.flr

import net.openhft.chronicle.core.OS
import kotlin.reflect.KClass

class Heap private constructor(
        private var ptr: Long,
        private var size: Long) {

    companion object {
        private val MEMORY = OS.memory()!!

        fun allocateBytes(size: Long): Heap {
            val ptr = MEMORY.allocate(size)
            return Heap(ptr, size)
        }

        fun allocateRecords(type: Class<out FixedLengthRecord>, amount: Long): Heap {
            val size = FixedLengthRecord.Factory.compile(type).size
            println("allocating ${size * amount}")
            return allocateBytes(size * amount)
        }

        fun allocateRecords(type: KClass<out FixedLengthRecord>, amount: Long) = allocateRecords(type.java, amount)
    }

    @JvmOverloads
    fun <T : FixedLengthRecord> refRecord(flr: T, offset: Long = 0): T {
        if (ptr == 0L) throw IllegalStateException("free")
        if (offset < 0 || offset >= size) throw IndexOutOfBoundsException("offset !in [0,$size)")
        if (offset + flr.size() > size) throw  IndexOutOfBoundsException("will not fit")
        flr.pointTo(ptr + offset)
        return flr
    }

    inline fun <reified T : FixedLengthRecord> refRecord(offset: Long = 0): T =
            refRecord(T::class.create(), offset)


    @JvmOverloads
    fun <T : FixedLengthRecord> refRecords(flr: T, length: Int, offset: Long = 0): Vector<T> {
        if (ptr == 0L) throw IllegalStateException("free")
        if (offset < 0 || offset >= size) throw IndexOutOfBoundsException("offset !in [0,$length)")
        if (offset + flr.size() * length > size) throw  IndexOutOfBoundsException("will not fit")
        val result = Vector(length, flr)
        result.pointTo(offset + ptr)

        return result
    }

    inline fun <reified T : FixedLengthRecord> refRecords(length: Int, offset: Long = 0): Vector<T> =
            refRecords(T::class.create(), length, offset)


    fun free() {
        if (ptr != 0L) {
            MEMORY.freeMemory(ptr, size)
            ptr = 0
            size = 0
        }
    }

    fun finalize() {
        free()
    }
}
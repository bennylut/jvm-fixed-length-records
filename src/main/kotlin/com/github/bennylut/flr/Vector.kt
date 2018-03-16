package com.github.bennylut.flr

import net.openhft.chronicle.core.OS

abstract class BaseVector(val length: Int) {

    protected var basePointer = 0L

    fun pointTo(ptr: Long) {
        basePointer = ptr
    }

}

class Vector<T : FixedLengthRecord>(length: Int, private val flr: T) : BaseVector(length) {

    operator fun get(i: Int): T {
        flr.pointTo(basePointer + i * flr.size())
        return flr
    }

    fun forEach(op: (Int, T) -> Unit) {
        val clone = flr.cloneRef()
        val size = flr.size()

        for (i in 0 until length) {
            clone.pointTo(basePointer + i * size)
            op(i, clone)
        }
    }

}

private val MEMORY = OS.memory()!!

class IntVector(length: Int) : BaseVector(length) {

    operator fun get(i: Int): Int {
        return MEMORY.readInt(basePointer + i * 4)
    }

    operator fun set(i: Int, v: Int) {
        println("basepointer: $basePointer")
        MEMORY.writeInt(basePointer + i * 4, v)
    }

    inline fun forEach(op: (Int) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }

    inline fun <R> map(opmap: (Int) -> R): ArrayList<R> {
        val result = ArrayList<R>(length)
        forEach {
            result.add(opmap(it))
        }
        return result
    }
}

class LongVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Long {
        return MEMORY.readLong(basePointer + i * 8)
    }

    operator fun set(i: Int, v: Long) {
        MEMORY.writeLong(basePointer + i * 8, v)
    }

    inline fun forEach(op: (Long) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

class FloatVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Float {
        return MEMORY.readFloat(basePointer + i * 4)
    }

    operator fun set(i: Int, v: Float) {
        MEMORY.writeFloat(basePointer + i * 4, v)
    }

    inline fun forEach(op: (Float) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

class DoubleVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Double {
        return MEMORY.readDouble(basePointer + i * 8)
    }

    operator fun set(i: Int, v: Double) {
        MEMORY.writeDouble(basePointer + i * 8, v)
    }

    inline fun forEach(op: (Double) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

class ByteVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Byte {
        return MEMORY.readByte(basePointer + i)
    }

    operator fun set(i: Int, v: Byte) {
        MEMORY.writeByte(basePointer + i, v)
    }

    inline fun forEach(op: (Byte) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

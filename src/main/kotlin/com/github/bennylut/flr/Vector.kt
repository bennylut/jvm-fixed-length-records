package com.github.bennylut.flr

import com.github.bennylut.util.cast
import net.openhft.chronicle.core.OS

abstract class BaseVector(val length: Int) {

    protected var basePointer = 0L

    fun _ref(ptr: Long) {
        basePointer = ptr
    }

}

class Vector<T : FixedLengthRecord>(length: Int, private val flr: T) : BaseVector(length) {

    operator fun get(i: Int): T = get(i, flr._dup().cast())

    operator fun get(i: Int, into: T): T {
        into._ref(basePointer + i * flr.size())
        return into
    }

    fun forEach(op: (Int, T) -> Unit) {
        val clone = flr.cloneRef()
        val size = flr.size()

        for (i in 0 until length) {
            clone._ref(basePointer + i * size)
            op(i, clone)
        }
    }

}

private val MEM = OS.memory()!!

class IntVector(length: Int) : BaseVector(length) {

    operator fun get(i: Int): Int {
        return MEM.readInt(basePointer + i * 4)
    }

    operator fun set(i: Int, v: Int) {
        MEM.writeInt(basePointer + i * 4, v)
    }

    inline fun forEach(op: (Int,Int) -> Unit) {
        for (i in 0 until length) {
            op(i,get(i))
        }
    }

    inline fun <R> map(opmap: (Int) -> R): ArrayList<R> {
        val result = ArrayList<R>(length)
        forEach { i, v ->
            result.add(opmap(v))
        }
        return result
    }
}

class LongVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Long {
        return MEM.readLong(basePointer + i * 8)
    }

    operator fun set(i: Int, v: Long) {
        MEM.writeLong(basePointer + i * 8, v)
    }

    inline fun forEach(op: (Long) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

class FloatVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Float {
        return MEM.readFloat(basePointer + i * 4)
    }

    operator fun set(i: Int, v: Float) {
        MEM.writeFloat(basePointer + i * 4, v)
    }

    inline fun forEach(op: (Float) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

class DoubleVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Double {
        return MEM.readDouble(basePointer + i * 8)
    }

    operator fun set(i: Int, v: Double) {
        MEM.writeDouble(basePointer + i * 8, v)
    }

    inline fun forEach(op: (Double) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

class ByteVector(size: Int) : BaseVector(size) {

    operator fun get(i: Int): Byte {
        return MEM.readByte(basePointer + i)
    }

    operator fun set(i: Int, v: Byte) {
        MEM.writeByte(basePointer + i, v)
    }

    inline fun forEach(op: (Byte) -> Unit) {
        for (i in 0 until length) {
            op(get(i))
        }
    }
}

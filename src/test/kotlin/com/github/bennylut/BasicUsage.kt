package com.github.bennylut

import com.github.bennylut.flr.*
import com.github.bennylut.util.cast


@Layout("x,y,xs[10]")
interface Example : FixedLengthRecord {
    var x: Int
    var y: Double
    val xs: IntVector
}

@Layout("z[2]")
interface Example2 : FixedLengthRecord {
    val z: Vector<Example>
}


fun main(args: Array<String>) {
    val heap = Heap.allocateRecords(Example::class, 2)

    val example: Vector<Example> = heap.refRecords(2)
    val example2: Example2 = heap.refRecord()

    println("A")

    example.forEach { i, e ->
        println("X")
        e.x = i


        println("Y")
        e.y = i + 0.1
        println("XS")
        for (j in 0 until e.xs.length) {
            e.xs[j] = j + i
        }
    }
    println("B")

    val e0: Example = example[0].cloneRef()
    val e1 = example[1].cloneRef()

    println("C")
    val v1 = example2.z[0].cloneRef()
    val v2 = example2.z[1].cloneRef()

    println("""
        example:
             [0] x=${e0.x}, y=${e0.y}, xs=${e0.xs.map { "$it" }}
             [1] x=${e1.x}, y=${e1.y}, xs=${e1.xs.map { "$it" }}

         example2:
             [0] x=${v1.x}, y=${v1.y}, xs=${v1.xs.map { "$it" }}
             [1] x=${v2.x}, y=${v2.y}, xs=${v2.xs.map { "$it" }}
    """)

}
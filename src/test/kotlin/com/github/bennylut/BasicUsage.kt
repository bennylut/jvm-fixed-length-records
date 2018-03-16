package com.github.bennylut

import com.github.bennylut.flr.*
import com.github.bennylut.util.cast
import kotlin.system.measureTimeMillis


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
    Heap.allocateRecords(Example::class, 2).use { heap ->

        val example: Vector<Example> = heap.refRecords(2)
        val example2: Example2 = heap.refRecord()

        while (true) {
            val array = IntArray(12)
            val timenat = measureTimeMillis {
                for (x in 1..100000000) {
                    for (i in 0 until 12) {
                        array[(x + i) % 12] = x
                    }
                }
            }

            println("timenat: $timenat")

            println("before time")
            val time = measureTimeMillis {
                for (x in 1..100000000) {
                    example.forEach { i, e ->
                        e.x = i


                        e.y = i + 0.1
                        for (j in 0 until e.xs.length) {
                            val len = e.xs.length
                            e.xs[j] = j + i
                        }
                    }
                }
            }

            println("took: $time")

        }
        val e0: Example = example[0]
        val e1 = example[1]

        val v1 = example2.z[0]
        val v2 = example2.z[1]

        println("""
        example:
             [0] x=${e0.x}, y=${e0.y}, xs=${e0.xs.map { "$it" }}
             [1] x=${e1.x}, y=${e1.y}, xs=${e1.xs.map { "$it" }}

         example2:
             [0] x=${v1.x}, y=${v1.y}, xs=${v1.xs.map { "$it" }}
             [1] x=${v2.x}, y=${v2.y}, xs=${v2.xs.map { "$it" }}
        """)

    }

}
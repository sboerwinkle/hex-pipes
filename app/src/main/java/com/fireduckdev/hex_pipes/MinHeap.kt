package com.fireduckdev.hex_pipes

// TODO: This should be nice and generic
class MinHeap (private val capacity: Int){
    val items: Array<TestViewGroup.PipeElement?> = Array(capacity) {null}
    var size = 0

    fun add(e: TestViewGroup.PipeElement) {
        if (size == capacity) throw Exception("Exceeded capacity of $capacity")
        val idx = size++;
        items[idx] = e
        bubbleUp(idx)
    }

    fun peek(): TestViewGroup.PipeElement? {
        return items[0]
    }

    fun bubbleUp(idx: Int) {
        var ret = idx
        val item = items[ret]!!
        while (true) {
            // TODO: Can reduce checks by having an "emperor" object at the top
            if (ret == 0) break
            val parent = (ret - 1) / 2
            if (item.interruptTime >= items[parent]!!.interruptTime) break;
            mv(items[parent]!!, ret)
            ret = parent
        }
        mv(item, ret)
    }

    fun bubbleDown(idx: Int) {
        var ret = idx
        val item = items[ret]!!
        while (true) {
            var child = ret * 2 + 1
            if (child >= size) break
            if (child + 1 < size
                && items[child + 1]!!.interruptTime < items[child]!!.interruptTime
            ) {
                child = child + 1
            }
            if (items[child]!!.interruptTime >= item.interruptTime) break
            mv(items[child]!!, ret)
            ret = child
        }
        mv(item, ret)
    }

    private fun mv(item: TestViewGroup.PipeElement, idx: Int) {
        items[idx] = item
        item.heapIdx = idx
    }
}
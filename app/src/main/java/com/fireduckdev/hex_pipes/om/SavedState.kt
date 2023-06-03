package com.fireduckdev.hex_pipes.om

import org.json.JSONArray
import org.json.JSONObject

private val finished_key = "finished"
private val background_key = "background"
private val pipes_key = "pipes"

class SavedState {
    val finished: Boolean
    val background: Int
    val pipes: List<SavedPipe>

    constructor(finished: Boolean, background: Int, pipes: List<SavedPipe>) {
        this.finished = finished
        this.background = background
        this.pipes = pipes
    }

    constructor(obj: JSONObject) {
        finished = obj.getBoolean(finished_key)
        background = obj.getInt(background_key)
        val jsonPipes = obj.getJSONArray(pipes_key)
        pipes = List(jsonPipes.length()) { SavedPipe(jsonPipes.getJSONObject(it)) }
    }

    fun export(): JSONObject {
        val ret = JSONObject()
        ret.put(finished_key, finished)
        ret.put(background_key, background)
        ret.put(pipes_key, JSONArray(pipes.map(SavedPipe::export)))
        return ret
    }
}
package com.fireduckdev.hex_pipes.om

import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception

private val color_key = "color"
private val m_key = "m"
private val n_key = "n"
private val couplers_key = "couplers"

class SavedPipe {
    val color: Int?
    val m: Int
    val n: Int
    val couplers: Array<Boolean>

    constructor(color: Int?, m: Int, n: Int, couplers: Array<Boolean>) {
        this.color = color
        this.m = m
        this.n = n
        this.couplers = couplers
    }

    constructor(obj: JSONObject) {
        color = if (obj.has(color_key)) obj.getInt(color_key) else null;
        m = obj.getInt(m_key)
        n = obj.getInt(n_key)
        val jsonCouplers = obj.getJSONArray(couplers_key)
        if (jsonCouplers.length() != 6) throw Exception("Invalid JSONObject, couplers array should be of length 6")
        couplers = Array(6) { jsonCouplers.getBoolean(it) }
    }

    fun export(): JSONObject {
        val ret = JSONObject()
        if (color != null) ret.put(color_key, color)
        ret.put(m_key, m);
        ret.put(n_key, n);
        val jsonCouplers = JSONArray()
        for (coupler in couplers) {
            jsonCouplers.put(coupler)
        }
        ret.put(couplers_key, jsonCouplers)
        return ret
    }
}
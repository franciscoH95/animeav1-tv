package com.animeav1.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes SvelteKit __data.json flat-array format.
 *
 * Each "data" node holds a deduplicated array where objects/arrays store
 * integer indices pointing to other positions in that same array.
 * data[0] is always the root; resolving it recursively yields the full object.
 */
object SvelteKitDecoder {

    /** Find and decode the node whose root object contains [rootKey]. */
    fun decode(jsonString: String, rootKey: String): JSONObject? {
        val root  = JSONObject(jsonString)
        val nodes = root.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optString("type") != "data") continue
            val data = node.optJSONArray("data") ?: continue
            if (data.length() == 0) continue
            val first = data.optJSONObject(0) ?: continue
            if (first.has(rootKey)) return resolve(data, 0) as? JSONObject
        }
        return null
    }

    private fun resolve(arr: JSONArray, idx: Any?): Any? {
        val intIdx: Int = when (idx) {
            is Int  -> idx
            is Long -> if (idx in 0..Int.MAX_VALUE) idx.toInt() else return idx
            else    -> return idx
        }
        if (intIdx < 0 || intIdx >= arr.length()) return intIdx
        return when (val v = arr.get(intIdx)) {
            is JSONObject -> {
                val out = JSONObject()
                v.keys().forEach { key -> out.put(key, resolve(arr, v.get(key)) ?: JSONObject.NULL) }
                out
            }
            is JSONArray -> {
                val out = JSONArray()
                for (j in 0 until v.length()) out.put(resolve(arr, v.get(j)) ?: JSONObject.NULL)
                out
            }
            JSONObject.NULL -> null
            else -> v
        }
    }
}

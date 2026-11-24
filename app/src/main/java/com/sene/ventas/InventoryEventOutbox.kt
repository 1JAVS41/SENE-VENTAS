package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Cola durable de cambios de existencias; el servidor solo los reenvía al SeneAdmin vinculado. */
object InventoryEventOutbox {
    private const val PREFS="sene_inventory_wire_outbox"
    private const val KEY="events"

    @Synchronized
    fun enqueue(context:Context,scope:String,changed:JSONArray,ts:Long,fullSnapshot:Boolean=false):Boolean {
        if(scope !in setOf("M","S")||(changed.length()==0&&!fullSnapshot))return false
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val events=try{JSONArray(prefs.getString(KEY,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        events.put(JSONObject().put("v",SeneWireProtocol.VERSION).put("t",SeneWireProtocol.INVENTORY_SYNC).put("e",UUID.randomUUID().toString())
            .put("g",scope).put("i",ts).put("p",changed).put("f",fullSnapshot))
        return prefs.edit().putString(KEY,events.toString()).commit()
    }

    @Synchronized fun pending(context:Context):JSONArray {
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return try{JSONArray(prefs.getString(KEY,"[]")?:"[]")}catch(_:Exception){JSONArray()}
    }

    @Synchronized fun acknowledge(context:Context,ids:JSONArray) {
        if(ids.length()==0)return
        val accepted=buildSet{for(i in 0 until ids.length())ids.optString(i).takeIf{it.isNotBlank()}?.let(::add)}
        if(accepted.isEmpty())return
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val old=try{JSONArray(prefs.getString(KEY,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        val keep=JSONArray()
        val edit=prefs.edit()
        for(i in 0 until old.length()){
            val event=old.optJSONObject(i)?:continue
            val eventId=event.optString("e")
            if(eventId in accepted) {
                if(event.optBoolean("f",false)) {
                    val scope=event.optString("g")
                    if(scope in setOf("M","S")) edit.putBoolean("full_snapshot_acked_"+scope,true)
                }
            } else keep.put(event)
        }
        edit.putString(KEY,keep.toString()).commit()
    }

    /** Encola la fotografía inicial al sincronizar, aunque el inventario ya existiera antes de activar Wire. */
    @Synchronized fun ensureFullSnapshot(context:Context,scope:String,changed:JSONArray,ts:Long):Boolean {
        if(scope !in setOf("M","S"))return false
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(prefs.getBoolean("full_snapshot_acked_"+scope,false))return true
        val events=try{JSONArray(prefs.getString(KEY,"[]")?: "[]")}catch(_:Exception){JSONArray()}
        for(i in 0 until events.length()) {
            val event=events.optJSONObject(i)?:continue
            if(event.optBoolean("f",false)&&event.optString("g")==scope)return true
        }
        return enqueue(context,scope,changed,ts,true)
    }

    @Synchronized fun hasPendingFullSnapshot(context:Context,scope:String):Boolean {
        if(scope !in setOf("M","S"))return false
        val prefs=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val events=try{JSONArray(prefs.getString(KEY,"[]")?:"[]")}catch(_:Exception){JSONArray()}
        return (0 until events.length()).any { i ->
            val event=events.optJSONObject(i)
            event!=null && event.optBoolean("f",false) && event.optString("g")==scope
        }
    }
}

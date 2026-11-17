package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Perfiles locales de trabajadores. El PIN nunca se conserva en texto claro. */
object WorkerProfiles {
    private const val PREFS = "sene_worker_profiles_v1"
    private const val KEY = "profiles"

    data class Profile(val id:String,val name:String,val phone:String,val salt:String,val pinHash:String)

    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    @Synchronized fun all(c:Context):List<Profile>{
        val raw=prefs(c).getString(KEY,"[]")?: "[]"
        return try {
            val a=JSONArray(raw)
            buildList { for(i in 0 until a.length()){
                val x=a.optJSONObject(i)?:continue
                val id=x.optString("id");val name=x.optString("name")
                if(id.isNotBlank()&&name.isNotBlank())add(Profile(id,name,x.optString("phone"),x.optString("salt"),x.optString("pinHash")))
            }}
        } catch(_:Exception){emptyList()}
    }
    private fun save(c:Context,items:List<Profile>):Boolean{
        val a=JSONArray()
        items.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("phone",it.phone).put("salt",it.salt).put("pinHash",it.pinHash))}
        return prefs(c).edit().putString(KEY,a.toString()).commit()
    }
    private fun digest(salt:String,pin:String):String{
        val bytes=MessageDigest.getInstance("SHA-256").digest((salt+":"+pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString(""){"%02x".format(it)}
    }
    @Synchronized fun create(c:Context,name:String,phone:String,pin:String):Profile?{
        val clean=name.trim()
        if(clean.isBlank()||pin.length!=4||pin.any{!it.isDigit()})return null
        val existing=all(c)
        if(existing.any{it.name.equals(clean,true)})return null
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}
        val profile=Profile(UUID.randomUUID().toString(),clean,phone.filter{it.isDigit()},salt,digest(salt,pin))
        return if(save(c,existing+profile))profile else null
    }
    fun verify(profile:Profile,pin:String):Boolean=pin.length==4&&pin.all{it.isDigit()}&&digest(profile.salt,pin)==profile.pinHash
}

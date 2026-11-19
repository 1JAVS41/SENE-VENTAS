package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.UUID

/** Perfiles locales de trabajadores. El PIN nunca se conserva en texto claro. */
object WorkerProfiles {
    private const val PREFS = "sene_worker_profiles_v1"
    private const val KEY = "profiles"

    data class Profile(val id:String,val name:String,val phone:String,val salt:String,val pinHash:String,val createdAt:Long=0L,val firstShiftTs:Long=0L)

    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    @Synchronized fun all(c:Context):List<Profile>{
        val raw=prefs(c).getString(KEY,"[]")?: "[]"
        return try {
            val a=JSONArray(raw)
            buildList { for(i in 0 until a.length()){
                val x=a.optJSONObject(i)?:continue
                val id=x.optString("id");val name=x.optString("name")
                if(id.isNotBlank()&&name.isNotBlank())add(Profile(id,name,x.optString("phone"),x.optString("salt"),x.optString("pinHash"),x.optLong("createdAt",0L),x.optLong("firstShiftTs",0L)))
            }}
        } catch(_:Exception){emptyList()}
    }
    private fun save(c:Context,items:List<Profile>):Boolean{
        val a=JSONArray()
        items.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("phone",it.phone).put("salt",it.salt).put("pinHash",it.pinHash).put("createdAt",it.createdAt).put("firstShiftTs",it.firstShiftTs))}
        return prefs(c).edit().putString(KEY,a.toString()).commit()
    }
    private fun digest(salt:String,pin:String):String{
        val saltBytes=ByteArray(salt.length/2){i->salt.substring(i*2,i*2+2).toInt(16).toByte()}
        val spec=PBEKeySpec(pin.toCharArray(),saltBytes,120000,256)
        return try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString(""){"%02x".format(it)}}
        finally{spec.clearPassword()}
    }
    @Synchronized fun create(c:Context,name:String,phone:String,pin:String):Profile?{
        val clean=name.trim()
        if(clean.isBlank()||pin.length!=4||pin.any{!it.isDigit()})return null
        val existing=all(c)
        if(existing.any{it.name.equals(clean,true)})return null
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}
        val profile=Profile(UUID.randomUUID().toString(),clean,phone.filter{it.isDigit()},salt,digest(salt,pin),System.currentTimeMillis(),0L)
        return if(save(c,existing+profile)){try{WorkerArchive.ensureProfileFolders(c,profile.id,profile.name)}catch(_:Exception){};profile}else null
    }
    @Synchronized fun markFirstShift(c:Context,profileId:String,ts:Long):Profile?{\n        val items=all(c)\n        val current=items.firstOrNull{it.id==profileId}?:return null\n        if(current.firstShiftTs>0L)return current\n        val updated=current.copy(firstShiftTs=ts)\n        if(!save(c,items.map{if(it.id==profileId)updated else it}))return null\n        return updated\n    }\n    fun verify(profile:Profile,pin:String):Boolean=pin.length==4&&pin.all{it.isDigit()}&&digest(profile.salt,pin)==profile.pinHash
}

package com.sene.ventas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Nómina multiempleado y asistencia persistente, completamente local. */
object EmployeePayroll {
    private const val PREFS="sene_employee_payroll_v2"
    private const val CONFIGS="employee_configs"
    private const val ENTRIES="payroll_entries"
    private const val ATTENDANCE="attendance_days"

    data class Config(
        val employee:String,val dailyHours:Int,val weeklySalaryCents:Long,
        val workDays:Int,val restDay:Int,val payDay:Int,
        val workDaysOfWeek:Set<Int> = emptySet(),
        val employeeId:String = "",val createdAt:Long = System.currentTimeMillis()
    )
    data class Capacity(val adjustedSalaryCents:Long,val limitCents:Long,val committedCents:Long,val availableCents:Long)
    data class Entry(
        val id:String,val employee:String,val type:String,val amountCents:Long,
        val reason:String,val description:String,val timestamp:Long,val periodKey:String,
        val settled:Boolean,val employeeId:String = ""
    )
    data class Attendance(val employeeId:String,val date:String,val startedAt:Long,val closedAt:Long)

    private fun prefs(c:Context)=c.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun activeId(c:Context):String=Store.shift(c)?.optString("employeeId").orEmpty()
    private fun dateKey(ts:Long):String=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(ts))
    private fun parseDate(s:String):Long=try{SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(s)?.time?:0L}catch(_:Exception){0L}

    fun configs(c:Context):List<Config>{
        val raw=prefs(c).getString(CONFIGS,null)
        if(raw!=null)return try{val a=JSONArray(raw);buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;decodeConfig(x)?.let{add(it)}}}}catch(_:Exception){emptyList()}
        // Migra una configuración antigua a un perfil existente, si lo encuentra.
        val legacy=c.getSharedPreferences("sene_employee_payroll_v1",Context.MODE_PRIVATE).getString("employee_config",null)?:return emptyList()
        return try{
            val x=JSONObject(legacy);val profile=WorkerProfiles.all(c).firstOrNull{it.name.equals(x.optString("employee"),true)}
            val rest=x.optInt("restDay",Calendar.SUNDAY);val count=x.optInt("workDays",6)
            val days=listOf(Calendar.MONDAY,Calendar.TUESDAY,Calendar.WEDNESDAY,Calendar.THURSDAY,Calendar.FRIDAY,Calendar.SATURDAY,Calendar.SUNDAY).filter{it!=rest}.take(count).toSet()
            val cfg=Config(x.optString("employee"),x.optInt("dailyHours",8),x.optLong("weeklySalaryCents"),days.size,rest,x.optInt("payDay",Calendar.SATURDAY),days,profile?.id.orEmpty(),System.currentTimeMillis())
            listOf(cfg)
        }catch(_:Exception){emptyList()}
    }
    private fun decodeConfig(x:JSONObject):Config?{
        val id=x.optString("employeeId");val name=x.optString("employee")
        val a=x.optJSONArray("workDaysOfWeek")?:JSONArray()
        val days=buildSet{for(i in 0 until a.length())a.optInt(i).takeIf{it in Calendar.SUNDAY..Calendar.SATURDAY}?.let{add(it)}}
        if(name.isBlank()||id.isBlank()||days.isEmpty())return null
        return Config(name,x.optInt("dailyHours",8).coerceIn(1,24),x.optLong("weeklySalaryCents").coerceAtLeast(0),days.size,
            (Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull{it !in days}?:Calendar.SUNDAY,
            x.optInt("payDay",Calendar.SATURDAY),days,id,x.optLong("createdAt",System.currentTimeMillis()))
    }
    fun config(c:Context,employeeId:String?=null):Config?{
        val id=employeeId?.takeIf{it.isNotBlank()}?:activeId(c)
        val all=configs(c)
        return if(id.isNotBlank())all.firstOrNull{it.employeeId==id} else all.firstOrNull()
    }
    fun saveConfig(c:Context,cfg:Config):Boolean{
        val id=cfg.employeeId.ifBlank{WorkerProfiles.all(c).firstOrNull{it.name.equals(cfg.employee,true)}?.id.orEmpty()}
        val days=cfg.workDaysOfWeek
        if(id.isBlank()||cfg.employee.isBlank()||cfg.dailyHours !in 1..24||cfg.weeklySalaryCents<=0||days.isEmpty()||
            days.any{it !in Calendar.SUNDAY..Calendar.SATURDAY}||cfg.payDay !in Calendar.SUNDAY..Calendar.SATURDAY)return false
        val saved=cfg.copy(employeeId=id,workDays=days.size,restDay=(Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull{it !in days}?:Calendar.SUNDAY)
        val raw=prefs(c).getString(CONFIGS,null)
        val existing=if(raw!=null) try{val a0=JSONArray(raw);buildList{for(i in 0 until a0.length())decodeConfig(a0.optJSONObject(i)?:continue)?.let{add(it)}}}catch(_:Exception){emptyList()} else {
            val legacy=c.getSharedPreferences("sene_employee_payroll_v1",Context.MODE_PRIVATE).getString("employee_config",null)
            try{
                if(legacy==null) emptyList() else {
                    val x=JSONObject(legacy);val oldProfile=WorkerProfiles.all(c).firstOrNull{it.name.equals(x.optString("employee"),true)}
                    if(oldProfile==null||oldProfile.id==id) emptyList() else {
                        val rest=x.optInt("restDay",Calendar.SUNDAY);val count=x.optInt("workDays",6)
                        val oldDays=listOf(Calendar.MONDAY,Calendar.TUESDAY,Calendar.WEDNESDAY,Calendar.THURSDAY,Calendar.FRIDAY,Calendar.SATURDAY,Calendar.SUNDAY).filter{it!=rest}.take(count).toSet()
                        listOf(Config(oldProfile.name,x.optInt("dailyHours",8),x.optLong("weeklySalaryCents"),oldDays.size,rest,x.optInt("payDay",Calendar.SATURDAY),oldDays,oldProfile.id,System.currentTimeMillis()))
                    }
                }
            }catch(_:Exception){emptyList()}
        }
        val all=existing.filterNot{it.employeeId==id}+saved
        val a=JSONArray()
        all.forEach{x->a.put(JSONObject().put("employeeId",x.employeeId).put("employee",x.employee).put("dailyHours",x.dailyHours)
            .put("weeklySalaryCents",x.weeklySalaryCents).put("workDays",x.workDays).put("restDay",x.restDay)
            .put("payDay",x.payDay).put("createdAt",x.createdAt)
            .put("workDaysOfWeek",JSONArray().apply{x.workDaysOfWeek.sorted().forEach{put(it)}}))}
        return prefs(c).edit().putString(CONFIGS,a.toString()).commit()
    }
    fun periodKey(timestamp:Long,payDay:Int):String{
        val cal=Calendar.getInstance().apply{timeInMillis=timestamp}
        val delta=(cal.get(Calendar.DAY_OF_WEEK)-payDay+7)%7
        cal.add(Calendar.DAY_OF_YEAR,-delta)
        return dateKey(cal.timeInMillis)
    }
    private fun periodStart(ts:Long,payDay:Int):Calendar=Calendar.getInstance().apply{timeInMillis=parseDate(periodKey(ts,payDay))}
    fun attendance(c:Context):List<Attendance>{
        val raw=prefs(c).getString(ATTENDANCE,"[]")?: "[]"
        return try{val a=JSONArray(raw);buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(Attendance(x.optString("employeeId"),x.optString("date"),x.optLong("startedAt"),x.optLong("closedAt")))}}}catch(_:Exception){emptyList()}
    }
    private fun saveAttendance(c:Context,items:List<Attendance>):Boolean{
        val a=JSONArray();items.forEach{x->a.put(JSONObject().put("employeeId",x.employeeId).put("date",x.date).put("startedAt",x.startedAt).put("closedAt",x.closedAt))}
        return prefs(c).edit().putString(ATTENDANCE,a.toString()).commit()
    }
    @Synchronized fun recordShiftStart(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Boolean{
        if(employeeId.isBlank())return false
        val key=dateKey(ts);val all=attendance(c).toMutableList();val i=all.indexOfFirst{it.employeeId==employeeId&&it.date==key}
        if(i>=0)all[i]=all[i].copy(startedAt=ts) else all.add(Attendance(employeeId,key,ts,0L))
        return saveAttendance(c,all)
    }
    @Synchronized fun recordShiftEnd(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Boolean{
        if(employeeId.isBlank())return false
        val key=dateKey(ts);val all=attendance(c).toMutableList();val i=all.indexOfFirst{it.employeeId==employeeId&&it.date==key}
        if(i>=0)all[i]=all[i].copy(closedAt=ts) else all.add(Attendance(employeeId,key,0L,ts))
        return saveAttendance(c,all)
    }
    fun absences(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Int{
        val cfg=config(c,employeeId)?:return 0
        val start=periodStart(ts,cfg.payDay);val begin=maxOf(start.timeInMillis,cfg.createdAt)
        val cal=Calendar.getInstance().apply{timeInMillis=begin};cal.set(Calendar.HOUR_OF_DAY,0);cal.set(Calendar.MINUTE,0);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0)
        val today=dateKey(ts);val attended=attendance(c).filter{it.employeeId==employeeId&&it.startedAt>0}.map{it.date}.toSet()
        var count=0;var guard=0
        while(dateKey(cal.timeInMillis)<today&&guard++<8){
            if(cal.get(Calendar.DAY_OF_WEEK) in cfg.workDaysOfWeek && dateKey(cal.timeInMillis) !in attended)count++
            cal.add(Calendar.DAY_OF_YEAR,1)
        }
        return count
    }
    fun entries(c:Context,employeeId:String?=null):List<Entry>{
        val raw=prefs(c).getString(ENTRIES,"[]")?: "[]"
        return try{val a=JSONArray(raw);buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue
            val e=Entry(x.optString("id"),x.optString("employee"),x.optString("type"),x.optLong("amountCents").coerceAtLeast(0),
                x.optString("reason"),x.optString("description"),x.optLong("timestamp"),x.optString("periodKey"),x.optBoolean("settled"),x.optString("employeeId"))
            if(employeeId.isNullOrBlank()||e.employeeId==employeeId)add(e)
        }}}catch(_:Exception){emptyList()}
    }
    private fun saveEntries(c:Context,items:List<Entry>):Boolean{
        val a=JSONArray();items.forEach{e->a.put(JSONObject().put("id",e.id).put("employeeId",e.employeeId).put("employee",e.employee).put("type",e.type)
            .put("amountCents",e.amountCents).put("reason",e.reason).put("description",e.description).put("timestamp",e.timestamp)
            .put("periodKey",e.periodKey).put("settled",e.settled))}
        return prefs(c).edit().putString(ENTRIES,a.toString()).commit()
    }
    fun capacity(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Capacity{
        val cfg=config(c,employeeId)?:return Capacity(0,0,0,0)
        val absent=absences(c,employeeId,ts);val rate=cfg.weeklySalaryCents/cfg.workDays
        val deduction=(rate*absent).coerceAtMost(cfg.weeklySalaryCents);val adjusted=(cfg.weeklySalaryCents-deduction).coerceAtLeast(0)
        val limit=adjusted/2;val key=periodKey(ts,cfg.payDay)
        val committed=entries(c,employeeId).filter{it.periodKey==key&&!it.settled&&(it.type==TYPE_LOAN||it.type==TYPE_CONSUMPTION)}.sumOf{it.amountCents}
        return Capacity(adjusted,limit,committed,(limit-committed).coerceAtLeast(0))
    }
    fun capacity(c:Context,absences:Int, timestamp:Long=System.currentTimeMillis()):Capacity{
        val id=activeId(c);if(id.isNotBlank())return capacity(c,id,timestamp)
        val cfg=config(c)?:return Capacity(0,0,0,0)
        return capacity(c,cfg.employeeId,timestamp)
    }
    @Synchronized fun addRequest(c:Context,type:String,amountCents:Long,reason:String="",description:String="",absences:Int=0,
        timestamp:Long=System.currentTimeMillis(),employeeId:String=activeId(c)):Entry?{
        if(type!=TYPE_LOAN&&type!=TYPE_CONSUMPTION||amountCents<=0||employeeId.isBlank())return null
        val cfg=config(c,employeeId)?:return null;val cap=capacity(c,employeeId,timestamp);if(amountCents>cap.availableCents)return null
        val entry=Entry(UUID.randomUUID().toString(),cfg.employee,type,amountCents,reason.trim(),description.trim(),timestamp,periodKey(timestamp,cfg.payDay),false,employeeId)
        return if(saveEntries(c,entries(c)+entry))entry else null
    }
    fun markSettled(c:Context,period:String,employeeId:String):Boolean{
        val updated=entries(c).map{if(it.employeeId==employeeId&&it.periodKey==period&&!it.settled)it.copy(settled=true)else it}
        return saveEntries(c,updated)
    }
    fun entriesForCurrentPeriod(c:Context,employeeId:String=activeId(c),ts:Long=System.currentTimeMillis()):List<Entry>{
        val cfg=config(c,employeeId)?:return emptyList();val key=periodKey(ts,cfg.payDay)
        return entries(c,employeeId).filter{it.periodKey==key&&!it.settled}
    }
    fun money(cents:Long)="$"+String.format(Locale.US,"%.2f",cents.coerceAtLeast(0)/100.0)
    const val TYPE_LOAN="LOAN"
    const val TYPE_CONSUMPTION="CONSUMPTION"
}

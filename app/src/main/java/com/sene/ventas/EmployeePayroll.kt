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
    data class Attendance(val employeeId:String,val date:String,val startedAt:Long,val closedAt:Long,val closureReason:String="",val closureSource:String="",val completedWorkday:Boolean=false)

    data class WeeklyDay(val day:String,val status:String,val started:String="",val closed:String="")
    data class PayrollCalculation(
        val grossCents:Long,val absences:Int,val absenceDeductionCents:Long,
        val earlyExitMinutes:Long,val earlyExitDeductionCents:Long,
        val consumptionCents:Long,val loanCents:Long,val netCents:Long
    )

    private fun displayDate(ts:Long)=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date(ts))
    private fun effectiveStartDate(c:Context,employeeId:String):String {
        val profile=WorkerProfiles.all(c).firstOrNull{it.id==employeeId} ?: return ""
        val start=maxOf(profile.createdAt,profile.firstShiftTs)
        return if(start>0L)displayDate(start) else ""
    }
    private fun scheduledDaysBetween(start:String,end:String,scheduled:Set<Int>):Int {
        if(start.isBlank()||end.isBlank()||start>end||scheduled.isEmpty())return 0
        val cal=Calendar.getInstance().apply{timeInMillis=parseDate(start)}
        val last=parseDate(end);var count=0;var guard=0
        while(cal.timeInMillis<=last&&guard++<370){
            if(cal.get(Calendar.DAY_OF_WEEK) in scheduled)count++
            cal.add(Calendar.DAY_OF_YEAR,1)
        }
        return count
    }

    /** Cálculo equivalente al de SENE ADMIN: prorratea el primer período,
     * excluye días anteriores al registro/primer turno y descuenta cierres anticipados justificados. */
    fun calculateCurrentPeriod(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):PayrollCalculation {
        val cfg=config(c,employeeId) ?: return PayrollCalculation(0,0,0,0,0,0,0,0)
        val periodEnd=periodKey(ts,cfg.payDay)
        val periodStart=displayDate(Calendar.getInstance().apply{timeInMillis=parseDate(periodEnd);add(Calendar.DAY_OF_YEAR,-6)}.timeInMillis)
        val effective=effectiveStartDate(c,employeeId)
        val payableStart=if(effective.isNotBlank()&&effective>periodStart)effective else periodStart
        val payableDays=scheduledDaysBetween(payableStart,periodEnd,cfg.workDaysOfWeek)
        val gross=if(cfg.workDays>0)(cfg.weeklySalaryCents*payableDays/cfg.workDays).coerceAtMost(cfg.weeklySalaryCents) else 0L
        val today=displayDate(ts)
        val records=attendance(c).filter{it.employeeId==employeeId}
        val attended=records.filter{it.startedAt>0L}.map{it.date}.toSet()
        val startCal=Calendar.getInstance().apply{timeInMillis=parseDate(payableStart)}
        var absences=0;var guard=0
        while(displayDate(startCal.timeInMillis)<today&&displayDate(startCal.timeInMillis)<=periodEnd&&guard++<370){
            if(startCal.get(Calendar.DAY_OF_WEEK) in cfg.workDaysOfWeek && displayDate(startCal.timeInMillis) !in attended)absences++
            startCal.add(Calendar.DAY_OF_YEAR,1)
        }
        val absenceDeduction=(cfg.weeklySalaryCents/cfg.workDays.coerceAtLeast(1)*absences).coerceAtMost(gross)
        val earlyRecords=records.filter{record->
            record.date>=periodStart&&record.date<=today&&record.startedAt>0L&&record.closedAt>0L&&record.closureSource.equals("TRABAJADOR",true)&&record.closureReason.isNotBlank()&&!record.completedWorkday
        }
        val earlyMissing=earlyRecords.map{record->(record.startedAt+cfg.dailyHours*3600000L-record.closedAt).coerceAtLeast(0L)}
            .filter{it>=60000L}
        val earlyMinutes=earlyMissing.sumOf{(it+59999L)/60000L}
        val earlyAmount=if(cfg.workDays>0&&cfg.dailyHours>0){
            earlyMissing.sumOf{missing->(cfg.weeklySalaryCents.toDouble()/cfg.workDays.toDouble()/cfg.dailyHours.toDouble()*(missing/3600000.0)).toLong().coerceAtLeast(0L)}
        }else 0L
        val earlyDeduction=earlyAmount.coerceAtMost((gross-absenceDeduction).coerceAtLeast(0L))
        val pending=entriesForCurrentPeriod(c,employeeId,ts)
        val consumption=pending.filter{it.type==TYPE_CONSUMPTION}.sumOf{it.amountCents}
        val loans=pending.filter{it.type==TYPE_LOAN}.sumOf{it.amountCents}
        val net=(gross-absenceDeduction-earlyDeduction-consumption-loans).coerceAtLeast(0L)
        return PayrollCalculation(gross,absences,absenceDeduction,earlyMinutes,earlyDeduction,consumption,loans,net)
    }

    /** Semana calendario de lunes a domingo, con los mismos estados que muestra SENE ADMIN. */
    fun weekAttendance(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):List<WeeklyDay> {
        val cfg=config(c,employeeId) ?: return emptyList()
        val effective=effectiveStartDate(c,employeeId)
        val periodEnd=periodKey(ts,cfg.payDay)
        val periodStart=displayDate(Calendar.getInstance().apply{timeInMillis=parseDate(periodEnd);add(Calendar.DAY_OF_YEAR,-6)}.timeInMillis)
        val today=displayDate(ts)
        val all=attendance(c).filter{it.employeeId==employeeId}.associateBy{it.date}
        return (0..6).map{index->
            val date=Calendar.getInstance().apply{timeInMillis=parseDate(periodStart);add(Calendar.DAY_OF_YEAR,index)}
            val key=displayDate(date.timeInMillis)
            val record=all[key]
            val dayName=when(date.get(Calendar.DAY_OF_WEEK)){
                Calendar.SUNDAY->"DOMINGO";Calendar.MONDAY->"LUNES";Calendar.TUESDAY->"MARTES"
                Calendar.WEDNESDAY->"MIÉRCOLES";Calendar.THURSDAY->"JUEVES";Calendar.FRIDAY->"VIERNES";else->"SÁBADO"
            }
            val status=when{
                record!=null&&record.closedAt>0L&&record.closureSource.equals("TRABAJADOR",true)&&record.closureReason.isNotBlank()&&!record.completedWorkday -> "EARLY_EXIT"
                record!=null&&record.startedAt>0L&&record.closedAt==0L -> "WORKING"
                record!=null&&record.startedAt>0L&&record.closedAt>0L -> "WORKED"
                key>=today -> "FUTURE"
                date.get(Calendar.DAY_OF_WEEK) !in cfg.workDaysOfWeek -> "FUTURE"
                effective.isNotBlank()&&key<effective -> "NO_RECORD"
                else -> "NOT_WORKED"
            }
            val timeFormat=SimpleDateFormat("HH:mm",Locale("es","MX"))
            WeeklyDay(dayName,status,
                record?.startedAt?.takeIf{it>0L}?.let{timeFormat.format(Date(it))}.orEmpty(),
                record?.closedAt?.takeIf{it>0L}?.let{timeFormat.format(Date(it))}.orEmpty())
        }
    }

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
            val cfg=Config(x.optString("employee"),x.optInt("dailyHours",0),x.optLong("weeklySalaryCents"),days.size,rest,x.optInt("payDay",0),days,profile?.id.orEmpty(),System.currentTimeMillis())
            listOf(cfg)
        }catch(_:Exception){emptyList()}
    }
    private fun decodeConfig(x:JSONObject):Config?{
        val id=x.optString("employeeId");val name=x.optString("employee")
        val a=x.optJSONArray("workDaysOfWeek")?:JSONArray()
        val days=buildSet{for(i in 0 until a.length())a.optInt(i).takeIf{it in Calendar.SUNDAY..Calendar.SATURDAY}?.let{add(it)}}
        if(name.isBlank()||id.isBlank()||days.isEmpty())return null
        val hours=x.optInt("dailyHours",0);val salary=x.optLong("weeklySalaryCents",0L);val payDay=x.optInt("payDay",0)
        if(hours !in 1..24||salary<=0||payDay !in Calendar.SUNDAY..Calendar.SATURDAY)return null
        return Config(name,hours,salary,days.size,
            (Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull{it !in days}?:Calendar.SUNDAY,
            payDay,days,id,x.optLong("createdAt",System.currentTimeMillis()))
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
        // El período activo termina en el próximo día de pago, igual que SENE ADMIN.
        val delta=(payDay-cal.get(Calendar.DAY_OF_WEEK)+7)%7
        cal.add(Calendar.DAY_OF_YEAR,delta)
        return dateKey(cal.timeInMillis)
    }
    private fun periodStart(ts:Long,payDay:Int):Calendar=Calendar.getInstance().apply{timeInMillis=parseDate(periodKey(ts,payDay))}
    fun attendance(c:Context):List<Attendance>{
        val raw=prefs(c).getString(ATTENDANCE,"[]")?: "[]"
        return try{
            val a=JSONArray(raw)
            val parsed=buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(Attendance(x.optString("employeeId"),x.optString("date"),x.optLong("startedAt"),x.optLong("closedAt"),x.optString("closureReason"),x.optString("closureSource"),x.optBoolean("completedWorkday",false)))}}
            // Migra registros antiguos que guardaron el inicio un día y el cierre al día siguiente.
            // La asistencia pertenece al día en que comenzó el turno, no al día en que terminó.
            val merged=parsed.toMutableList()
            val endOnly=parsed.filter{it.startedAt==0L&&it.closedAt>0L}
            endOnly.forEach{closing->
                val previous=merged.indices.filter{idx->
                    val candidate=merged[idx]
                    candidate.employeeId==closing.employeeId&&candidate.startedAt>0L&&candidate.closedAt==0L&&
                        candidate.date<closing.date&&candidate.startedAt<closing.closedAt&&
                        runCatching{val cal=Calendar.getInstance().apply{timeInMillis=parseDate(candidate.date)};cal.add(Calendar.DAY_OF_YEAR,1);dateKey(cal.timeInMillis)==closing.date}.getOrDefault(false)
                }.maxByOrNull{merged[it].startedAt}
                if(previous!=null){
                    val started=merged[previous]
                    merged[previous]=started.copy(closedAt=closing.closedAt,closureReason=closing.closureReason,closureSource=closing.closureSource,completedWorkday=closing.completedWorkday)
                    merged.removeAll{it.employeeId==closing.employeeId&&it.date==closing.date&&it.startedAt==0L&&it.closedAt==closing.closedAt}
                }
            }
            merged
        }catch(_:Exception){emptyList()}
    }
    private fun saveAttendance(c:Context,items:List<Attendance>):Boolean{
        val a=JSONArray();items.forEach{x->a.put(JSONObject().put("employeeId",x.employeeId).put("date",x.date).put("startedAt",x.startedAt).put("closedAt",x.closedAt).put("closureReason",x.closureReason).put("closureSource",x.closureSource).put("completedWorkday",x.completedWorkday))}
        return prefs(c).edit().putString(ATTENDANCE,a.toString()).commit()
    }
    @Synchronized fun recordShiftStart(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Boolean{
        if(employeeId.isBlank())return false
        val key=dateKey(ts);val all=attendance(c).toMutableList();val i=all.indexOfFirst{it.employeeId==employeeId&&it.date==key}
        if(i>=0)all[i]=all[i].copy(startedAt=ts,closedAt=0L,closureReason="",closureSource="") else all.add(Attendance(employeeId,key,ts,0L))
        return saveAttendance(c,all)
    }
    @Synchronized fun recordShiftEnd(c:Context,employeeId:String,ts:Long=System.currentTimeMillis(),reason:String="",source:String="",completedWorkday:Boolean=false,startedAt:Long=0L):Boolean{
        if(employeeId.isBlank())return false
        val all=attendance(c).toMutableList()
        val start=if(startedAt>0L)startedAt else all.filter{it.employeeId==employeeId&&it.startedAt>0L&&it.closedAt==0L&&it.startedAt<ts}.maxByOrNull{it.startedAt}?.startedAt?:0L
        val key=dateKey(if(start>0L)start else ts)
        val i=all.indexOfFirst{it.employeeId==employeeId&&it.date==key}
        if(i>=0)all[i]=all[i].copy(closedAt=ts,closureReason=reason,closureSource=source,completedWorkday=completedWorkday)
        else all.add(Attendance(employeeId,key,start,ts,reason,source,completedWorkday))
        return saveAttendance(c,all)
    }
    // Todos los resúmenes y límites usan el mismo cálculo efectivo de nómina.
    // Así no se descuentan días anteriores al primer turno ni cierres administrativos.
    fun absences(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Int =
        calculateCurrentPeriod(c,employeeId,ts).absences

    fun earlyExitMinutes(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Long =
        calculateCurrentPeriod(c,employeeId,ts).earlyExitMinutes

    fun earlyExitDeductionCents(c:Context,employeeId:String,ts:Long=System.currentTimeMillis()):Long =
        calculateCurrentPeriod(c,employeeId,ts).earlyExitDeductionCents

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
        val calculation=calculateCurrentPeriod(c,employeeId,ts)
        val adjusted=(calculation.grossCents-calculation.absenceDeductionCents-calculation.earlyExitDeductionCents).coerceAtLeast(0L)
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

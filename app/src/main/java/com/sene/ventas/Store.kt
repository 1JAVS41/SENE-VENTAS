package com.sene.ventas
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject
private class HistoryDb(context:Context):SQLiteOpenHelper(context,"sene_history.db",null,1){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE sales (id INTEGER PRIMARY KEY AUTOINCREMENT, data TEXT NOT NULL)");db.execSQL("CREATE TABLE moves (id INTEGER PRIMARY KEY AUTOINCREMENT, data TEXT NOT NULL)")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){}
 fun migrate(context:Context){
  val prefs=context.getSharedPreferences("sene_ventas_local",0)
  if(prefs.getBoolean("history_migrated_v1",false))return
  val raw=prefs.getString("datos",null)
  val legacy=try{if(raw.isNullOrBlank())JSONObject() else JSONObject(raw)}catch(_:Exception){JSONObject()}
  val db=writableDatabase
  db.beginTransaction()
  try{
   val sales=legacy.optJSONArray("sales")?:JSONArray()
   for(i in 0 until sales.length()){val x=sales.optJSONObject(i)?:continue;db.insert("sales",null,ContentValues().apply{put("data",x.toString())})}
   val moves=legacy.optJSONArray("moves")?:JSONArray()
   for(i in 0 until moves.length()){val x=moves.optJSONObject(i)?:continue;db.insert("moves",null,ContentValues().apply{put("data",x.toString())})}
   db.setTransactionSuccessful()
  }finally{db.endTransaction()}
  legacy.remove("sales");legacy.remove("moves")
  prefs.edit().putString("datos",legacy.toString()).putBoolean("history_migrated_v1",true).apply()
 }
 fun all(table:String):JSONArray{val result=JSONArray();readableDatabase.rawQuery("SELECT data FROM $table ORDER BY id ASC",null).use{c->while(c.moveToNext())try{result.put(JSONObject(c.getString(0)))}catch(_:Exception){}};return result}
 fun replace(table:String,array:JSONArray){val db=writableDatabase;db.beginTransaction();try{db.delete(table,null,null);for(i in 0 until array.length()){val x=array.optJSONObject(i)?:continue;db.insert(table,null,ContentValues().apply{put("data",x.toString())})};db.setTransactionSuccessful()}finally{db.endTransaction()}}
 fun append(table:String,item:JSONObject){writableDatabase.insert(table,null,ContentValues().apply{put("data",item.toString())})}
 fun appendMany(table:String,items:List<JSONObject>){val db=writableDatabase;db.beginTransaction();try{for(item in items)db.insert(table,null,ContentValues().apply{put("data",item.toString())});db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
object Store {
 private const val PREF="sene_ventas_local"; private const val KEY="datos"
 @Volatile private var history:HistoryDb?=null
 @Synchronized private fun db(c:Context):HistoryDb {val h=history?:HistoryDb(c.applicationContext).also{history=it};h.migrate(c.applicationContext);return h}
 private fun root(c:Context):JSONObject { val raw=c.getSharedPreferences(PREF,0).getString(KEY,null); return try { if(raw==null) JSONObject() else JSONObject(raw) } catch(e:Exception){ JSONObject() } }
 private fun put(c:Context,r:JSONObject){ c.getSharedPreferences(PREF,0).edit().putString(KEY,r.toString()).apply(); try{ReportFile.update(c,r)}catch(_:Exception){} }
 fun products(c:Context)=root(c).optJSONArray("products")?:JSONArray()
 fun sales(c:Context)=db(c).all("sales")
 fun moves(c:Context)=db(c).all("moves")
 fun shift(c:Context)=root(c).optJSONObject("shift")
 fun phone(c:Context)=root(c).optString("phone","523311337592")
 fun setPhone(c:Context,p:String){ val r=root(c);r.put("phone",p);put(c,r) }
 fun saveProducts(c:Context,a:JSONArray){val r=root(c);r.put("products",a).put("stockUpdatedAt",System.currentTimeMillis());put(c,r)}
 fun stockUpdatedAtText(c:Context):String{val ts=root(c).optLong("stockUpdatedAt",0L);return if(ts>0L)java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",java.util.Locale("es","MX")).format(java.util.Date(ts)) else "Sin registro previo de actualización"}
 fun saveSales(c:Context,a:JSONArray){db(c).replace("sales",a);try{ReportFile.update(c)}catch(_:Exception){}}
 fun saveShift(c:Context,s:JSONObject?){val r=root(c);if(s==null)r.remove("shift")else r.put("shift",s);put(c,r)}
 fun addSale(c:Context,x:JSONObject){addSales(c,listOf(x))}
 fun addSales(c:Context,items:List<JSONObject>){if(items.isEmpty())return;db(c).appendMany("sales",items);try{ReportFile.update(c)}catch(_:Exception){}}
 fun addMove(c:Context,x:JSONObject){db(c).append("moves",x);try{ReportFile.update(c)}catch(_:Exception){}}
 fun providerExpenses(c:Context,since:Long=0L):Double{val all=moves(c);var total=0.0;for(i in 0 until all.length()){val x=all.optJSONObject(i)?:continue;if(x.optString("type")=="PROVIDER_PAYMENT"&&x.optLong("ts",0L)>=since)total+=x.optDouble("amount",0.0)};return total}
 fun cashBalance(c:Context):Double{val s=shift(c)?:return 0.0;val start=s.optLong("startTs",0L);val all=sales(c);var sold=0.0;for(i in 0 until all.length()){val x=all.optJSONObject(i)?:continue;if(x.optLong("ts",0L)>=start)sold+=x.optDouble("subtotal",0.0)};return s.optDouble("cash",0.0)+sold-providerExpenses(c,start)}
 fun money(v:Double)="$"+String.format(java.util.Locale.US,"%.2f",v)
}
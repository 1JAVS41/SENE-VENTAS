package com.sene.ventas
// Build validation marker: QR SENEPAIR2, same-phone restore, shift sync and payment-day flow.
import android.app.*
import android.content.*
import android.graphics.Typeface
import android.graphics.Rect
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.provider.DocumentsContract
import android.os.*
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private const val GREEN=0xFF168B45.toInt()
private const val PIN="2910"
private fun stamp()=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date())
private fun parseAmount(raw:String):Double? {
 val value=raw.trim().replace("$","").replace(" ","").replace("\u00A0","")
 if(value.isEmpty())return null
 val comma=value.lastIndexOf(',');val dot=value.lastIndexOf('.')
 val normalized=when{
  comma>=0&&dot>=0&&comma>dot->value.replace(".","").replace(',','.')
  comma>=0&&dot>=0->value.replace(",","")
  comma>=0->{val decimals=value.length-comma-1;if(decimals in 1..2)value.replace(',','.') else value.replace(",","")}
  else->value
 }
 return normalized.toDoubleOrNull()
}
private fun internetTimeMillis():Long?{
 val endpoints=listOf(
  "https://worldtimeapi.org/api/timezone/America/Mexico_City",
  "https://www.google.com/generate_204",
  "https://www.cloudflare.com/"
 )
 for(endpoint in endpoints){
  var connection:HttpURLConnection?=null
  try{
   connection=(URL(endpoint).openConnection() as HttpURLConnection).apply{connectTimeout=5000;readTimeout=5000;requestMethod="GET";useCaches=false}
   connection.connect()
   if(endpoint.contains("worldtimeapi.org")){
    val body=connection.inputStream.bufferedReader().use{it.readText()}
    val unix=JSONObject(body).optLong("unixtime",0L)
    if(unix>0L)return unix*1000L
   }
   val serverDate=connection.getHeaderFieldDate("Date",0L)
   if(serverDate>0L)return serverDate
  }catch(_:Exception){}finally{connection?.disconnect()}
 }
 return null
}
private fun day()=SimpleDateFormat("dd/MM/yyyy",Locale("es","MX")).format(Date())
private fun Activity.dp(n:Int)=(n*resources.displayMetrics.density).toInt()
private fun Activity.rounded(color:Int,radius:Int=18,stroke:Int=0)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=0)setStroke(dp(1),stroke)}
private fun Activity.page(title:String,logoAction:(()->Unit)?=null):LinearLayout {
 val v=LinearLayout(this).apply {
  orientation=LinearLayout.VERTICAL
  setPadding(dp(18),dp(18),dp(18),dp(30))
  setBackgroundColor(0xFFF4F5FA.toInt())
 }
 val top=LinearLayout(this).apply{
  orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
  setPadding(dp(16),dp(16),dp(16),dp(16))
  background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF111A35.toInt(),0xFF263B70.toInt())).apply{cornerRadius=dp(22).toFloat()}
  elevation=dp(4).toFloat()
 }
 val logoFrame=FrameLayout(this).apply{background=rounded(0xFF344A82.toInt(),17);setPadding(dp(6),dp(6),dp(6),dp(6))}
 val logo=ImageView(this).apply{setImageResource(R.drawable.logo_sene_pv);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER}
 logoFrame.addView(logo,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
 if(logoAction!=null){logoFrame.isClickable=true;logoFrame.isFocusable=true;logoFrame.setOnClickListener{logoAction()};logoFrame.contentDescription="Abrir menú SENE"}
 top.addView(logoFrame,LinearLayout.LayoutParams(dp(58),dp(58)))
 val brandColumn=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(13),0,0,0)}
 brandColumn.addView(TextView(this).apply{text="SENE / VENTAS";textSize=20f;letterSpacing=.07f;setTextColor(-1);typeface=Typeface.create("sans-serif",Typeface.BOLD)})
 brandColumn.addView(TextView(this).apply{text="GESTIÓN INTELIGENTE";textSize=10f;letterSpacing=.16f;setTextColor(0xFFB9C9F8.toInt());setPadding(0,dp(5),0,0)})
 top.addView(brandColumn,LinearLayout.LayoutParams(0,-2,1f))
 v.addView(top,LinearLayout.LayoutParams(-1,-2))
 val accent=View(this).apply{background=rounded(0xFFFFB547.toInt(),3)}
 val accentParams=LinearLayout.LayoutParams(dp(42),dp(5));accentParams.topMargin=dp(23);accentParams.bottomMargin=dp(10);v.addView(accent,accentParams)
 v.addView(TextView(this).apply{text=title;textSize=27f;setTextColor(0xFF17213D.toInt());typeface=Typeface.create("sans-serif",Typeface.BOLD);setPadding(0,0,0,dp(3))})
 v.addView(TextView(this).apply{text="Todo bajo control.";textSize=13f;setTextColor(0xFF7A8298.toInt());setPadding(0,0,0,dp(17))})
 return v
}
private fun Activity.txt(s:String,size:Float=17f)=TextView(this).apply{
 text=s;textSize=size;setTextColor(if(size>=17f)0xFF202B49.toInt() else 0xFF66708A.toInt())
 typeface=Typeface.create(if(size>=17f)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
 setPadding(dp(15),dp(13),dp(15),dp(13));background=rounded(0xFFFFFFFF.toInt(),14,0xFFE2E6F0.toInt())
 setLineSpacing(dp(2).toFloat(),1f)
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(5);p.bottomMargin=dp(5);layoutParams=p
}
private fun Activity.input(h:String,num:Boolean=false)=EditText(this).apply{
 hint=h;textSize=16f;setSingleLine(true);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
 setTextColor(0xFF17213D.toInt());setHintTextColor(0xFF929BB0.toInt())
 setPadding(dp(17),dp(15),dp(17),dp(15));background=rounded(0xFFFFFFFF.toInt(),14,0xFFDCE2EF.toInt())
 if(num)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);p.bottomMargin=dp(9);layoutParams=p
 setOnFocusChangeListener{view,hasFocus->if(hasFocus)view.postDelayed({var parent:android.view.ViewParent?=view.parent;while(parent!=null && parent !is ScrollView)parent=parent.parent;if(parent is ScrollView){val rect=Rect(0,0,view.width,view.height);parent.offsetDescendantRectToMyCoords(view,rect);val bottom=parent.height-parent.paddingBottom-dp(20);val delta=when{rect.bottom>bottom->rect.bottom-bottom+dp(28);rect.top<parent.scrollY+dp(12)->rect.top-parent.scrollY-dp(12);else->0};if(delta!=0)parent.smoothScrollBy(0,delta)}},250)}
}
private fun Activity.button(s:String,green:Boolean=true,f:()->Unit)=Button(this).apply{
 text=s;textSize=15f;typeface=Typeface.create("sans-serif",Typeface.BOLD);isAllCaps=false;letterSpacing=.025f
 minHeight=dp(54);setPadding(dp(18),dp(12),dp(18),dp(12));setTextColor(if(green)-1 else 0xFF293A68.toInt())
 background=if(green)GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF435FAD.toInt(),0xFF283D78.toInt())).apply{cornerRadius=dp(14).toFloat()} else rounded(0xFFFFFFFF.toInt(),14,0xFFD9E0F0.toInt())
 elevation=dp(if(green)3 else 0).toFloat()
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);p.bottomMargin=dp(7);layoutParams=p
 setOnClickListener{f()}
}
private fun Activity.powerButton(s:String,f:()->Unit)=Button(this).apply{
 text=s;textSize=16f;typeface=Typeface.create("sans-serif-black",Typeface.BOLD);isAllCaps=false;letterSpacing=.035f
 minHeight=dp(68);setPadding(dp(18),dp(14),dp(18),dp(14));setTextColor(0xFFFFFFFF.toInt())
 background=android.graphics.drawable.LayerDrawable(arrayOf(
  GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFF07152F.toInt(),0xFF07152F.toInt())).apply{cornerRadius=dp(18).toFloat()},
  GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0xFFFFD66B.toInt(),0xFFFF9D24.toInt(),0xFFB94D13.toInt())).apply{cornerRadius=dp(16).toFloat()}
 )).apply{setLayerInset(1,dp(2),dp(1),dp(2),dp(6))}
 elevation=dp(10).toFloat()
 setShadowLayer(dp(2).toFloat(),0f,dp(3).toFloat(),0xFF152443.toInt())
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);p.bottomMargin=dp(10);layoutParams=p
 setOnClickListener{f()}
}
private fun Activity.display(v:LinearLayout){
 // El teclado se gestiona con WindowInsets (Android 15/16 edge-to-edge) y cada campo enfocado se desplaza por encima del IME.
 WindowCompat.setDecorFitsSystemWindows(window,false)
 window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
 val sc=ScrollView(this)
 sc.setBackgroundColor(0xFFF4F5FA.toInt())
 sc.isFillViewport=true
 sc.isSmoothScrollingEnabled=true
 sc.isVerticalScrollBarEnabled=true
 sc.clipToPadding=false
 sc.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
 sc.addView(v)
 setContentView(sc)
 fun revealFocused(){
  val focused=currentFocus ?: return
  if(focused===sc || !focused.isShown)return
  val rect=Rect(0,0,focused.width,focused.height)
  sc.offsetDescendantRectToMyCoords(focused,rect)
  val visibleTop=sc.scrollY+sc.paddingTop+dp(10)
  val visibleBottom=sc.scrollY+sc.height-sc.paddingBottom-dp(24)
  val delta=when{
   rect.bottom>visibleBottom->rect.bottom-visibleBottom+dp(28)
   rect.top<visibleTop->rect.top-visibleTop-dp(12)
   else->0
  }
  if(delta!=0)sc.scrollBy(0,delta)
 }
 ViewCompat.setOnApplyWindowInsetsListener(sc){view,insets->
  val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
  val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
  val keyboard=insets.isVisible(WindowInsetsCompat.Type.ime())
  sc.setPadding(dp(18),bars.top+dp(12),dp(18),(if(keyboard)ime.bottom else bars.bottom)+dp(28))
  sc.post{revealFocused()}
  insets
 }
 ViewCompat.requestApplyInsets(sc)
 sc.viewTreeObserver.addOnGlobalLayoutListener{sc.post{revealFocused()}}
}
private fun Activity.msg(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
private fun Activity.pin(ok:()->Unit){val e=input("PIN del patrón",true);e.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;AlertDialog.Builder(this).setTitle("Soy Patrón").setView(e).setPositiveButton("Entrar"){_,_->if(e.text.toString()==PIN)ok()else msg("PIN incorrecto")}.setNegativeButton("Cancelar",null).show()}
private object InventoryCrypto {
 private const val ALIAS="SENE_INVENTORY_AES_KEY"
 private val MAGIC="SENEENC1".toByteArray(Charsets.US_ASCII)
 private const val IV_SIZE=12
 private fun key():SecretKey {
  val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  val existing=ks.getKey(ALIAS,null) as? SecretKey
  if(existing!=null)return existing
  val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
  generator.init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
   .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
   .setRandomizedEncryptionRequired(true).build())
  return generator.generateKey()
 }
 fun encrypt(plain:ByteArray):ByteArray {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.ENCRYPT_MODE,key())
  val iv=cipher.iv
  return MAGIC+iv+cipher.doFinal(plain)
 }
 fun isEncrypted(data:ByteArray)=data.size>=MAGIC.size+IV_SIZE+16 && data.copyOfRange(0,MAGIC.size).contentEquals(MAGIC)
 fun decrypt(data:ByteArray):ByteArray {
  if(!isEncrypted(data))throw java.io.IOException("El archivo no tiene el formato cifrado de SENE-VENTAS")
  val iv=data.copyOfRange(MAGIC.size,MAGIC.size+IV_SIZE)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
  return cipher.doFinal(data.copyOfRange(MAGIC.size+IV_SIZE,data.size))
 }
}
private object InventoryArchive {
 fun save(activity:Activity,products:JSONArray):Boolean {
  val prefs=activity.getSharedPreferences("sene_ventas_local",0)
  val saved=prefs.getString("report_tree_uri",null)?:return false
  val tree=Uri.parse(saved);val resolver=activity.contentResolver
  val rootId=DocumentsContract.getTreeDocumentId(tree)
  val root=DocumentsContract.buildDocumentUriUsingTree(tree,rootId)
  val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
  fun child(parent:Uri,name:String):Uri? {
   val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
   resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
    val id=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val col=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    while(cur.moveToNext())if(id>=0&&col>=0&&cur.getString(col)==name)return DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(id))
   }
   return null
  }
  val folder=child(root,date)?:DocumentsContract.createDocument(resolver,root,DocumentsContract.Document.MIME_TYPE_DIR,date)?:throw java.io.IOException("No se pudo crear la carpeta de fecha")
  val time=SimpleDateFormat("HH-mm-ss-SSS",Locale.US).format(Date())
  val name="SENE-INVENTARIO-$date-$time.txt"
  val target=DocumentsContract.createDocument(resolver,folder,"text/plain",name)?:throw java.io.IOException("No se pudo crear el archivo TXT")
  val updatedAt=stamp()
  val out=StringBuilder("INVENTARIO_SENE_V1\nActualizado: ").append(updatedAt).append("\nCódigo | Producto | Precio | Existencia")
  for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;out.append("\n").append(p.optString("code")).append(" | ").append(p.optString("name")).append(" | ").append(p.optDouble("price")).append(" | ").append(p.optInt("stock"))}
  resolver.openOutputStream(target,"w")?.use{it.write(out.toString().toByteArray(Charsets.UTF_8))}?:throw java.io.IOException("No se pudo escribir el inventario TXT")
  prefs.edit().putBoolean("first_inventory_created",true).putString("inventory_source_file",name).putString("inventory_source_updated",updatedAt).apply()
  return true
 }
}

private data class InventorySnapshot(val folder:String,val file:String,val updated:String,val products:JSONArray)
private object InventoryReader {
 fun readSnapshot(activity:Activity,uri:Uri,fileName:String?=null,folderName:String=""):InventorySnapshot {
  val resolver=activity.contentResolver
  val name=fileName?:run{
   var value=uri.lastPathSegment?: "inventario.txt"
   resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cur->if(cur.moveToFirst()){val col=cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(col>=0)value=cur.getString(col)?:value}}
   value
  }
  val raw=resolver.openInputStream(uri)?.use{it.readBytes()}?:throw java.io.IOException("No se pudo abrir el archivo seleccionado")
  val decoded=if(InventoryCrypto.isEncrypted(raw))InventoryCrypto.decrypt(raw) else raw
  // Compatibilidad con TXT de inventarios creados por versiones anteriores.
  // No se exige una frase fija en el encabezado; se valida la tabla real.
  val content=when {
   decoded.size>=2 && decoded[0]==0xFF.toByte() && decoded[1]==0xFE.toByte() -> String(decoded,2,decoded.size-2,Charsets.UTF_16LE)
   decoded.size>=2 && decoded[0]==0xFE.toByte() && decoded[1]==0xFF.toByte() -> String(decoded,2,decoded.size-2,Charsets.UTF_16BE)
   else -> String(decoded,Charsets.UTF_8).removePrefix("\uFEFF")
  }.replace("\u0000","").replace("\r\n","\n").replace("\r","\n")
  val lines=content.lineSequence().map{it.trim().trim('\uFEFF')}.filter{it.isNotBlank()}.toList()
  val markerIndex=lines.indexOfFirst{it.equals("INVENTARIO_SENE_V1",true)}
  val products=JSONArray()
  val dataLines=if(markerIndex>=0)lines.drop(markerIndex+1) else lines
  for(line in dataLines){
   val header=java.text.Normalizer.normalize(line,java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"),"").lowercase(Locale.ROOT)
   if(line.startsWith("Actualizado:",true) || (header.contains("codigo") && header.contains("producto")) || header.startsWith("sene ventas") || header.startsWith("inventario"))continue
      val parts=when{
    line.contains("|")->line.split("|").map{it.trim()}
    line.contains("\t")->line.split("\t").map{it.trim()}
    line.contains(";")->line.split(";").map{it.trim()}
    else->emptyList()
   }
   if(parts.size<4)continue
   val priceText=parts[2].replace("$","").replace(" ","")
   val normalizedPrice=if(priceText.contains(".")&&priceText.contains(","))priceText.replace(",","") else priceText.replace(",",".")
   val price=parseAmount(parts[2])
   val stock=Regex("-?\\d+").find(parts[3])?.value?.toIntOrNull()
   if(parts[0].isNotBlank()&&parts[1].isNotBlank()&&price!=null&&stock!=null){
    products.put(JSONObject().put("code",parts[0]).put("name",parts[1]).put("price",price).put("stock",stock))
   }
  }
  if(products.length()==0)throw java.io.IOException("El TXT se reconoció como inventario SENE, pero no se encontraron filas válidas. Se esperaba: Código | Producto | Precio | Existencia")
  val updated=content.lineSequence().firstOrNull{it.startsWith("Actualizado: ")}?.removePrefix("Actualizado: ")?:folderName.ifBlank{"Fecha no indicada"}
  return InventorySnapshot(folderName,name,updated,products)
 }
 fun listSnapshots(activity:Activity,tree:Uri):List<InventorySnapshot> {
  val resolver=activity.contentResolver
  val rootId=DocumentsContract.getTreeDocumentId(tree)
  val root=DocumentsContract.buildDocumentUriUsingTree(tree,rootId)
  val candidates=mutableListOf<Triple<String,String,Uri>>()
  val visited=mutableSetOf<String>()
  fun collect(parent:Uri,label:String,depth:Int){
   if(depth>8)return
   val parentId=DocumentsContract.getDocumentId(parent)
   if(!visited.add(parentId))return
   val child=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId)
   resolver.query(child,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use{cur->
    val id=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val name=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);val mime=cur.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
    while(cur.moveToNext()){
     if(id<0||name<0||mime<0)continue
     val childName=cur.getString(name)?:continue
     val childId=cur.getString(id)
     val childUri=DocumentsContract.buildDocumentUriUsingTree(tree,childId)
     if(cur.getString(mime)==DocumentsContract.Document.MIME_TYPE_DIR){
      collect(childUri,if(label.isBlank())childName else "$label / $childName",depth+1)
     }else if(childName.endsWith(".txt",true)||childName.endsWith(".sene",true)){
      candidates.add(Triple(label.ifBlank{"INVENTARIO SENE"},childName,childUri))
     }
    }
   }
  }
  collect(root,"",0)
  val snapshots=mutableListOf<InventorySnapshot>()
  for(item in candidates.sortedByDescending{it.second})try{snapshots.add(readSnapshot(activity,item.third,item.second,item.first))}catch(_:Exception){}
  return snapshots
 }
 fun latest(activity:Activity,tree:Uri):InventorySnapshot {
  val snapshots=listSnapshots(activity,tree)
  val formatter=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX"))
  return snapshots.maxByOrNull{snapshot->try{formatter.parse(snapshot.updated)?.time?:0L}catch(_:Exception){0L}}
   ?:throw java.io.IOException("No se encontró un inventario válido en INVENTARIO SENE")
 }
}
private fun whatsapp(a:Activity,body:String,after:()->Unit={}){val u="https://wa.me/"+Store.phone(a)+"?text="+URLEncoder.encode(body,"UTF-8");try{a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(u)));after()}catch(e:Exception){a.msg("No se pudo abrir WhatsApp")}}

class InicioTurnoActivity:Activity(){
 private val pairScanRequest=7419
 private var pendingPairBaseUrl=""
 private var pairingStatusView:TextView?=null

 private var internet="Consultando internet…"
 private lateinit var internetLabel:TextView
 private var internetBaseMillis=0L
 private var internetBaseElapsed=0L
 private val internetClockHandler=Handler(Looper.getMainLooper())
 private val internetClockTick=object:Runnable{override fun run(){
  if(internetBaseMillis>0L){
   val current=internetBaseMillis+(SystemClock.elapsedRealtime()-internetBaseElapsed)
   val formatted=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(current))
   val difference=Math.abs(current-System.currentTimeMillis())
   val status=if(difference>60000L)"\n⚠ La hora del celular difiere por más de 1 minuto." else "\n✓ Hora del celular sincronizada."
   internet="Internet: $formatted$status"
   if(::internetLabel.isInitialized)internetLabel.text=internet
  }
  internetClockHandler.postDelayed(this,1000L)
 }}
 private var pendingStartAfterWhatsApp=false
 private var refreshAfterPatron=false
 override fun onResume(){super.onResume();if(pendingStartAfterWhatsApp){pendingStartAfterWhatsApp=false;startActivity(Intent(this,MainActivity::class.java));finish()}else if(refreshAfterPatron){refreshAfterPatron=false;draw()}}
 override fun onCreate(b:Bundle?){super.onCreate(b);Protection.requireActivation(this){continueAfterAuthorization()}}
 private fun continueAfterAuthorization(){if(Store.shift(this)!=null){startActivity(Intent(this,MainActivity::class.java));finish();return};draw();consultInternetTime()}
 private fun pairingPrefs()=getSharedPreferences("sene_ventas_server",MODE_PRIVATE)
 private fun showPairingSetup(){
  val saved=pairingPrefs().getString("server_base_url","")?: ""
  val url=input("Dirección HTTPS del servidor SENE")
  url.setText(saved)
  val state=pairingPrefs().getString("device_slot",null)
  val device=pairingPrefs().getString("device_id",null)
  val status=if(!device.isNullOrBlank())"Este equipo está vinculado como ${state?: "VENTAS"}." else "Este equipo todavía no está vinculado."
  AlertDialog.Builder(this).setTitle("Conectar con SENE PATRÓN")
   .setMessage(status+"\n\nCaptura la dirección HTTPS oficial del servidor. Después escanea el QR de vinculación generado desde SENE PATRÓN.")
   .setView(url)
   .setNeutralButton("VERIFICAR") { _, _ ->
    val base=url.text.toString().trim().trimEnd('/')
    if(!base.startsWith("https://")||Uri.parse(base).host.isNullOrBlank()){msg("Escribe una dirección HTTPS válida");return@setNeutralButton}
    pairingPrefs().edit().putString("server_base_url",base).apply()
    Thread{try{
     val client=SeneVentasApiClient(this,base)
     val result=if(client.storedDeviceId()!=null)client.session() else throw IllegalStateException("Este equipo aún no está vinculado")
     runOnUiThread{msg("Conexión correcta · "+result.optString("slot")+" · "+result.optString("licenseStatus"))}
    }catch(e:Exception){runOnUiThread{msg("No se pudo verificar: "+(e.message?:"Error de conexión"))}}}.start()
   }
   .setPositiveButton("GUARDAR Y ESCANEAR QR") { _, _ ->
    val base=url.text.toString().trim().trimEnd('/')
    if(!base.startsWith("https://")||Uri.parse(base).host.isNullOrBlank()){msg("Escribe una dirección HTTPS válida");return@setPositiveButton}
    pairingPrefs().edit().putString("server_base_url",base).apply()
    pendingPairBaseUrl=base
    startActivityForResult(Intent(this,ScanActivity::class.java),pairScanRequest)
   }.setNegativeButton("CANCELAR",null).show()
 }
 private fun completePairingFromQr(values:ArrayList<String>){
  val token=values.lastOrNull{it.matches(Regex("^[a-fA-F0-9]{64}$"))||it.startsWith("SENEPAIR2:",true)||it.startsWith("SENEPAIR1:",true)}
  if(token.isNullOrBlank()){msg("No se encontró un QR de vinculación SENE válido");return}
  val base=pendingPairBaseUrl.ifBlank{pairingPrefs().getString("server_base_url","")?:""}
  if(base.isBlank()){msg("Primero configura el servidor SENE");return}
  msg("Validando QR con SENE PATRÓN…")
  Thread{try{
   val result=SeneVentasApiClient(this,base).pairFromQr(token)
   runOnUiThread{
    val slot=result.optString("slot")
    msg("Equipo vinculado correctamente: "+slot+" · "+result.optString("businessName"))
    draw()
   }
  }catch(e:Exception){
   runOnUiThread{msg("No se pudo vincular: "+(e.message?:"Error de conexión"))}
  }}.start()
 }
 private fun consultInternetTime(){
  internet="Consultando hora de Internet…"
  if(::internetLabel.isInitialized)internetLabel.text="Internet: "+internet
  Thread{
   val onlineMillis=internetTimeMillis()
   runOnUiThread{
    if(onlineMillis!=null){
     internetBaseMillis=onlineMillis
     internetBaseElapsed=SystemClock.elapsedRealtime()
     internetClockHandler.removeCallbacks(internetClockTick)
     internetClockTick.run()
    }else{
     internet="Sin respuesta de servidores de hora; se usa la hora del celular."
     if(::internetLabel.isInitialized)internetLabel.text="Internet: "+internet
    }
   }
  }.start()
 }

 override fun onDestroy(){internetClockHandler.removeCallbacks(internetClockTick);super.onDestroy()}
 private var pendingWorker=""
 private var pendingWorkerPhone=""
 private var pendingCash=""
 private fun startShiftWithLatestInventory(worker:String,phone:String,cash:String){
  val localProducts=Store.products(this)
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  // Si el inventario ya fue importado desde TXT, se usa el inventario cargado
  // y se genera el respaldo interno; nunca se vuelve a pedir elegir carpeta.
  if(localProducts.length()>0){
   createShiftReport(worker,phone,cash,saved?.takeIf{it.isNotBlank()}?.let{Uri.parse(it)})
   return
  }
  if(!saved.isNullOrBlank()){
   createShiftReport(worker,phone,cash,Uri.parse(saved))
   return
  }
  AlertDialog.Builder(this).setTitle("PRIMER USO DE SENE-VENTAS")
   .setMessage("Es la primera vez usando SENE-VENTAS. Dirígete a la sección Soy Patrón para generar o cargar tu primer inventario.")
   .setPositiveButton("IR A SOY PATRÓN"){_,_->pin{startActivity(Intent(this,PatronActivity::class.java))}}
   .setNegativeButton("Cerrar",null).show()
 }
 private fun createShiftReport(worker:String,phone:String,cash:String,tree:Uri?){
  try{
   val localProducts=Store.products(this)
   val savedTree=tree?:getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)?.takeIf{it.isNotBlank()}?.let{Uri.parse(it)}
   // Un TXT cargado ya deja el inventario disponible en Store. Si no existe
   // permiso de carpeta, el respaldo se guarda en el espacio privado de SENE.
   val snapshot=if(localProducts.length()==0 && savedTree!=null)InventoryReader.latest(this,savedTree)else null
   val latest=snapshot?.folder?: "INVENTARIO SENE"
   val prefs=getSharedPreferences("sene_ventas_local",0)
   val newest=snapshot?.file?:prefs.getString("inventory_source_file","Inventario local")!!
   val updated=snapshot?.updated?:prefs.getString("inventory_source_updated","Cargado desde TXT")!!
   val products=snapshot?.products?:localProducts
   if(products.length()==0)throw java.io.IOException("El inventario cargado no contiene productos")
   Store.saveProducts(this,products)
   val now=System.currentTimeMillis()
   val shift=JSONObject().put("worker",worker).put("phone",phone).put("cash",parseAmount(cash)?:0.0).put("started",stamp()).put("startTs",now).put("lastReport",now)
   val report=StringBuilder("SENE-VENTAS - INICIO DE TURNO\nFecha y hora: ").append(stamp()).append("\nTrabajador: ").append(worker).append("\nNúmero del trabajador: ").append(phone).append("\nCaja inicial: ").append(Store.money(shift.optDouble("cash"))).append("\nInventario SENE: carpeta ").append(latest).append("\nArchivo de inventario: ").append(newest).append("\nÚltima actualización del inventario: ").append(updated).append("\n\nINVENTARIO_SENE_V1\nCódigo | Producto | Precio | Existencia")
   for(i in 0 until products.length()){val item=products.optJSONObject(i)?:continue;report.append("\n").append(item.optString("code")).append(" | ").append(item.optString("name")).append(" | ").append(Store.money(item.optDouble("price"))).append(" | ").append(item.optInt("stock"))}
   val body=report.toString()+REPORT_BACKUP_NOTE
   if(savedTree!=null) saveTextBackup(this,savedTree,"SENE-VENTAS-INICIO",body)
   else saveTextBackupLocal(this,"SENE-VENTAS-INICIO",body)
   Store.saveShift(this,shift)
   // Abrir el chat directo del patrón con el reporte completo ya preparado para enviar.
   pendingStartAfterWhatsApp=openWhatsAppText(this,body)
   if(!pendingStartAfterWhatsApp)msg("El turno quedó registrado y el respaldo TXT está guardado en el teléfono, en SENE VENTAS / INVENTARIO SENE. Revisa el número de WhatsApp del patrón.")
  }catch(e:Exception){AlertDialog.Builder(this).setTitle("No se pudo iniciar el turno").setMessage("No se pudo guardar el respaldo en INVENTARIO SENE. Verifica que la carpeta esté disponible y tenga permisos de escritura.\n\nDetalle: "+(e.message?:"Error desconocido.")).setPositiveButton("IR A SOY PATRÓN"){_,_->pin{startActivity(Intent(this,PatronActivity::class.java))}}.setNegativeButton("Cerrar",null).show()}
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==pairScanRequest){if(c==RESULT_OK){val values=d?.getStringArrayListExtra(SCAN_CODES)?:arrayListOf();completePairingFromQr(values)};return};if(r==START_INVENTORY_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();createShiftReport(pendingWorker,pendingWorkerPhone,pendingCash,tree)}catch(e:Exception){msg("No se pudo guardar el acceso a Inventario SENE: "+(e.message?:"error"))}}}
 private fun draw(){val v=page("Inicio de Turno");v.addView(txt("Celular: "+stamp()));internetLabel=txt("Internet: "+internet,15f);v.addView(internetLabel);v.addView(button("VERIFICAR HORA DE INTERNET",false){consultInternetTime()});val savedSlot=pairingPrefs().getString("device_slot",null);val savedDevice=pairingPrefs().getString("device_id",null);val linkLabel=if(!savedDevice.isNullOrBlank())"SENE PATRÓN: VINCULADO · ${savedSlot?: "VENTAS"}" else "SENE PATRÓN: AÚN NO VINCULADO";pairingStatusView=txt(linkLabel,14f);v.addView(pairingStatusView!!);v.addView(button("VINCULAR / VERIFICAR SENE PATRÓN",false){showPairingSetup()});v.addView(button("RESTAURAR GRATIS · MISMO TELÉFONO",false){showVentasSamePhoneRestoreDialog()})
 val n=input("Nombre del trabajador");val p=input("Número de celular del trabajador",true);val cash=input("¿Con cuánto inicia caja chica? $",true);v.addView(n);v.addView(p);v.addView(cash)
 v.addView(powerButton("⏻  ENCENDER Y COMENZAR TURNO"){if(n.text.isBlank()||p.text.isBlank()||cash.text.isBlank()){msg("Completa los datos");return@powerButton};startShiftWithLatestInventory(n.text.toString().trim(),p.text.toString().filter{it.isDigit()},cash.text.toString())})
 v.addView(button("Soy Patrón (PIN)",false){pin{refreshAfterPatron=true;startActivity(Intent(this,PatronActivity::class.java))}})
 val inventoryReady=Store.products(this).length()>0 || try{val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null);if(saved.isNullOrBlank())false else {InventoryReader.latest(this,Uri.parse(saved));true}}catch(_:Exception){false};if(!inventoryReady){val notice=txt("Aún no hay un inventario válido en INVENTARIO SENE. Entra a Soy Patrón para crear o cargar el primero.",14f);notice.setTextColor(0xFFB3261E.toInt());v.addView(notice)}
 Protection.addBlindButton(this,v)
 v.addView(txt("Historial de Turnos"));val m=Store.moves(this);for(i in m.length()-1 downTo maxOf(0,m.length()-15))v.addView(txt(m.optJSONObject(i)?.optString("text")?:"",14f));display(v)}
}

class MainActivity:Activity(){
 private lateinit var salesDeviceClock:TextView
 private lateinit var salesInternetClock:TextView
 private val salesClockHandler=Handler(Looper.getMainLooper())
 private var salesInternetText="Consultando…"
 private var salesInternetBaseMillis=0L
 private var salesInternetBaseElapsed=0L
 private val salesClockTick=object:Runnable{override fun run(){if(::salesDeviceClock.isInitialized)salesDeviceClock.text="Hora del celular: "+stamp();salesClockHandler.postDelayed(this,1000)}}
 private val salesInternetClockTick=object:Runnable{override fun run(){
  if(salesInternetBaseMillis>0L){
   val current=salesInternetBaseMillis+(SystemClock.elapsedRealtime()-salesInternetBaseElapsed)
   salesInternetText=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(current))
   if(::salesInternetClock.isInitialized)salesInternetClock.text="Hora de Internet: "+salesInternetText
  }
  salesClockHandler.postDelayed(this,1000L)
 }}
 private val ticket=linkedMapOf<String,Int>();private lateinit var code:EditText;private lateinit var paid:EditText;private lateinit var ticketView:TextView;private lateinit var totalView:TextView;private lateinit var sell:Button;private val h=Handler(Looper.getMainLooper())
 private var reportDialog:AlertDialog?=null
 private lateinit var countdownView:TextView
 private var pendingFinalReport=false
 private var pendingShareUri:Uri?=null
 private var pendingFinalShare=false
 private var confirmingReport=false
 private var refreshAfterPatron=false
 private val lock=object:Runnable{override fun run(){if(::sell.isInitialized){updateReportCountdown();if(due())showReportAlert() else {sell.isEnabled=true;sell.alpha=1f}};h.postDelayed(this,1000)}}
 private fun updateReportCountdown(){if(!::countdownView.isInitialized)return;val s=Store.shift(this)?:return;val remaining=(7200000L-(System.currentTimeMillis()-s.optLong("lastReport",System.currentTimeMillis()))).coerceAtLeast(0L);if(remaining==0L){countdownView.text="\u26A0 REPORTE PENDIENTE: ENV\u00CDA PARA CONTINUAR";countdownView.setTextColor(0xFFB3261E.toInt());sell.isEnabled=false;sell.alpha=0.45f}else{val total=remaining/1000;val hh=total/3600;val mm=(total%3600)/60;val ss=total%60;countdownView.text=String.format(Locale.US,"SIGUIENTE REPORTE EN: %02d:%02d:%02d",hh,mm,ss);countdownView.setTextColor(0xFF168B45.toInt())}}
 private fun due():Boolean{val s=Store.shift(this)?:return false;return System.currentTimeMillis()-s.optLong("lastReport",System.currentTimeMillis())>=7200000}
 override fun onCreate(b:Bundle?){super.onCreate(b);if(Store.shift(this)==null){startActivity(Intent(this,InicioTurnoActivity::class.java));finish();return};draw();h.post(lock);salesClockHandler.post(salesClockTick);refreshSalesInternetClock()}
 override fun onResume(){super.onResume();if(::salesDeviceClock.isInitialized){salesClockHandler.removeCallbacks(salesClockTick);salesClockHandler.post(salesClockTick)};if(::salesInternetClock.isInitialized)refreshSalesInternetClock();if(pendingShareUri!=null)h.post{confirmReportSent()}else if(refreshAfterPatron){refreshAfterPatron=false;draw()}else if(::sell.isInitialized&&due())h.post{showReportAlert()}}
 override fun onDestroy(){h.removeCallbacks(lock);salesClockHandler.removeCallbacks(salesClockTick);salesClockHandler.removeCallbacks(salesInternetClockTick);super.onDestroy()}
 private fun refreshSalesInternetClock(){
  salesInternetText="Consultando…"
  if(::salesInternetClock.isInitialized)salesInternetClock.text="Hora de Internet: "+salesInternetText
  Thread{
   val value=internetTimeMillis()
   runOnUiThread{
    if(value!=null){
     salesInternetBaseMillis=value
     salesInternetBaseElapsed=SystemClock.elapsedRealtime()
     salesClockHandler.removeCallbacks(salesInternetClockTick)
     salesInternetClockTick.run()
    }else{
     salesInternetText="No disponible"
     if(::salesInternetClock.isInitialized)salesInternetClock.text="Hora de Internet: "+salesInternetText
    }
   }
  }.start()
 }
 private fun showReportAlert(){if(reportDialog?.isShowing==true)return;reportDialog=AlertDialog.Builder(this).setTitle("REPORTE CADA 2 HORAS").setMessage("Envía el documento de movimientos por WhatsApp para continuar trabajando.").setCancelable(false).setPositiveButton("ENVIAR"){_,_->sendReport(false)}.create().also{it.setCanceledOnTouchOutside(false);it.show();it.window?.setLayout(-1,-2)}}
 private fun showToolsMenu(){
  val items=arrayOf("Ventas hasta el momento","Llegó proveedor / Agregar inventario","Gastos a proveedores","Ver stock","Soy Patrón / Administrador","Cerrar turno","Guardar reporte TXT","Conexión con SENE PATRÓN","Día de pago · historial y reporte","Restaurar SENE VENTAS · mismo teléfono","Enviar solicitud al patrón")
  val adapter=object:android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,items){
   override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{
    val row=super.getView(position,convertView,parent) as TextView
    row.setTextColor(0xFF245DB8.toInt());row.textSize=16f
    row.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
    row.setPadding(dp(20),dp(16),dp(16),dp(16))
    return row
   }
  }
  AlertDialog.Builder(this).setTitle("SENE · HERRAMIENTAS").setAdapter(adapter){_,which->
   when(which){
    0->showCurrentSalesSummary()
    1->startActivity(Intent(this,AgregarInventarioActivity::class.java))
    2->showProviderExpenses()
    3->stock()
    4->pin{refreshAfterPatron=true;startActivity(Intent(this,PatronActivity::class.java))}
    5->AlertDialog.Builder(this).setTitle("Cerrar turno").setMessage("¿Enviar reporte final y cerrar turno?").setPositiveButton("Enviar y cerrar"){_,_->sendReport(true)}.setNegativeButton("Cancelar",null).show()
    6->saveReportToPhone()
    7->showSeneCloudConnection()
    8->showPayrollTools()
    9->showVentasSamePhoneRestoreDialog()
    10->showWorkRequestDialog()
   }
  }.setNegativeButton("Volver",null).show()
 }
 private fun showWorkRequestDialog(){
  val labels=arrayOf("Préstamo","Consumo de producto","Permiso de ausencia","Aviso de llegada tarde","Cambio de horario","Vacaciones","Incidente","Faltante","Cambio de descanso","Adelanto de sueldo","Equipo dañado","Sugerencia")
  val types=arrayOf("LOAN","CONSUMPTION","ABSENCE_PERMISSION","LATE_ARRIVAL","SCHEDULE_CHANGE","VACATION","INCIDENT","SHORTAGE","REST_DAY_CHANGE","SALARY_ADVANCE","DAMAGED_EQUIPMENT","SUGGESTION")
  AlertDialog.Builder(this).setTitle("¿QUÉ NECESITAS SOLICITAR?")
   .setItems(labels){_,index->showWorkRequestDetails(types[index],labels[index])}
   .setNegativeButton("CANCELAR",null).show()
 }
 private fun showWorkRequestDetails(type:String,label:String){
  val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
  val description=input("Explica qué necesitas y por qué").apply{minLines=3;maxLines=5;setSingleLine(false)}
  val amount=input("Cantidad en pesos (si aplica)",true)
  form.addView(description);form.addView(amount)
  AlertDialog.Builder(this).setTitle(label.uppercase(Locale.ROOT)).setMessage("La solicitud se enviará a SENE PATRÓN para revisión. Podrás consultar la respuesta en Día de pago · historial y reporte.")
   .setView(form).setPositiveButton("ENVIAR SOLICITUD"){_,_->
    val detail=description.text.toString().trim()
    val money=if(amount.text.isNullOrBlank())null else parseAmount(amount.text.toString())
    if(detail.length<4||detail.length>1500||(amount.text.isNotBlank()&&(money==null||money<0.0))){
     msg("Escribe una explicación y revisa la cantidad")
     return@setPositiveButton
    }
    val payload=JSONObject().put("description",detail).put("requestedAt",stamp())
    if(money!=null)payload.put("amountCents",Math.round(money*100.0))
    Thread{try{
     val result=payrollApi().createWorkRequest(type,payload)
     runOnUiThread{msg("Solicitud enviada al patrón. Estado: "+result.optString("status","pendiente"))}
    }catch(e:Exception){runOnUiThread{msg("No se pudo enviar la solicitud: "+(e.message?:"Error de conexión"))}}}.start()
   }.setNegativeButton("CANCELAR",null).show()
 }
 private fun showPayrollTools(){
  val options=arrayOf("CONSULTAR HISTORIAL DE SOLICITUDES Y RESPUESTAS","GENERAR / CONSULTAR REPORTE DE NÓMINA")
  AlertDialog.Builder(this).setTitle("DÍA DE PAGO").setItems(options){_,which->
   if(which==0)loadWorkHistory() else generatePayrollReport()
  }.setNegativeButton("CERRAR",null).show()
 }
 private fun payrollApi():SeneVentasApiClient{
  val prefs=getSharedPreferences("sene_ventas_server",MODE_PRIVATE)
  val base=prefs.getString("server_base_url",null)
   ?:throw IllegalStateException("Primero conecta SENE VENTAS con SENE PATRÓN")
  if(prefs.getString("device_id",null).isNullOrBlank())throw IllegalStateException("Este equipo aún no está vinculado")
  return SeneVentasApiClient(this,base)
 }
 private fun loadWorkHistory(){
  Thread{try{
   val result=payrollApi().requestHistory()
   val items=result.optJSONArray("items")?:JSONArray()
   val corrections=result.optJSONArray("corrections")?:JSONArray()
   val lines=StringBuilder()
   for(i in 0 until items.length()){
    val item=items.optJSONObject(i)?:continue
    lines.append("• ").append(item.optString("type")).append(" · ").append(item.optString("status").uppercase(Locale.ROOT))
     .append("\n  ").append(item.optJSONObject("details")?.optString("description").orEmpty())
    val decision=item.optJSONObject("decisionDetails")?:JSONObject()
    if(decision.length()>0)lines.append("\n  Respuesta del patrón: ").append(decision.toString())
    lines.append("\n\n")
   }
   for(i in 0 until corrections.length()){
    val item=corrections.optJSONObject(i)?:continue
    lines.append("• CORRECCIÓN DE VENTA · ").append(item.optString("status").uppercase(Locale.ROOT))
     .append("\n  Venta: ").append(item.optString("saleId"))
     .append("\n  Motivo: ").append(item.optJSONArray("reasons")?.let{arr->(0 until arr.length()).joinToString(", "){arr.optString(it)}}?:item.optString("reason"))
     .append("\n\n")
   }
   val message=if(lines.isEmpty())"Todavía no hay solicitudes ni respuestas registradas." else lines.toString()
   runOnUiThread{AlertDialog.Builder(this).setTitle("HISTORIAL · SOLICITUDES Y RESPUESTAS")
    .setMessage(message).setPositiveButton("CERRAR",null).show()}
  }catch(e:Exception){runOnUiThread{msg("No se pudo consultar el historial: "+(e.message?:"Error"))}}}.start()
 }
 private fun generatePayrollReport(){
  msg("Consultando configuración de nómina y movimientos…")
  Thread{try{
   val api=payrollApi()
   val session=api.session()
   val payrollState=session.optJSONObject("payroll")?:JSONObject()
   val settingsResult=api.payrollSettings()
   if(!settingsResult.optBoolean("configured",false)){
    runOnUiThread{msg("El patrón todavía no configuró sueldo y fecha de pago para este equipo.")}
    return@Thread
   }
   val settings=settingsResult.optJSONObject("settings")?:JSONObject()
   val history=api.requestHistory()
   val items=history.optJSONArray("items")?:JSONArray()
   val corrections=history.optJSONArray("corrections")?:JSONArray()
   val prefs=getSharedPreferences("sene_ventas_payroll",MODE_PRIVATE)
   // El periodo se calcula con la última nómina guardada por el servidor,
   // no con la apertura de WhatsApp (que no confirma que el usuario envió el mensaje).
   val periodStart=settingsResult.optLong("lastReportCreatedAt",0L)*1000L
   val sales=Store.sales(this)
   var total=0.0;var units=0;val tickets=mutableSetOf<Long>()
   for(i in 0 until sales.length()){
    val sale=sales.optJSONObject(i)?:continue
    val ts=sale.optLong("ts",0L)
    if(periodStart>0L&&ts<periodStart)continue
    total+=sale.optDouble("subtotal",0.0);units+=sale.optInt("qty",1)
    tickets.add(if(ts>0L)ts else sale.optString("time").hashCode().toLong())
   }
   var deductions=0L;var approved=0
   val deductionLines=StringBuilder()
   for(i in 0 until items.length()){
    val item=items.optJSONObject(i)?:continue
    if(item.optString("status")!="approved")continue
    val decidedAt=item.optLong("decidedAt",0L)
    if(periodStart>0L && decidedAt>0L && decidedAt*1000L<periodStart)continue
    val deduction=item.optJSONObject("decisionDetails")?.optJSONObject("deduction")?:continue
    if(!deduction.optBoolean("authorized",false))continue
    val cents=deduction.optLong("amountCents",0L)
    if(cents<=0L)continue
    deductions+=cents;approved++
    deductionLines.append("\n• ").append(item.optString("type")).append(": $")
     .append(String.format(Locale.US,"%.2f",cents/100.0))
   }
   val salary=settings.optLong("salaryCents",0L)
   val net=(salary-deductions).coerceAtLeast(0L)
   val date=payrollState.optString("mexicoDate",SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date()))
   val report=JSONObject()
    .put("worker",Store.shift(this)?.optString("worker")?:"")
    .put("slot",session.optString("slot"))
    .put("periodStartTs",periodStart)
    .put("periodEndDate",date)
    .put("salaryCents",salary)
    .put("salesTotalCents",Math.round(total*100.0))
    .put("salesCount",tickets.size)
    .put("unitsSold",units)
    .put("approvedDeductionsCents",deductions)
    .put("approvedDeductionsCount",approved)
    .put("netPayCents",net)
   val due=payrollState.optBoolean("dueToday",false)
   val dueLabel=if(due)"SÍ · hoy está habilitado el envío" else "NO · el envío por WhatsApp está bloqueado hasta el día configurado"
   val body=StringBuilder("SENE VENTAS · REPORTE DE DÍA DE PAGO")
    .append("\nEmpresa: ").append(session.optString("businessName"))
    .append("\nEquipo: ").append(session.optString("slot"))
    .append("\nFecha de corte: ").append(date)
    .append("\nSueldo configurado: $").append(String.format(Locale.US,"%.2f",salary/100.0))
    .append("\nVentas del periodo: $").append(String.format(Locale.US,"%.2f",total))
    .append("\nTickets: ").append(tickets.size).append(" · Unidades: ").append(units)
    .append("\nDescuentos autorizados: $").append(String.format(Locale.US,"%.2f",deductions))
    .append(deductionLines)
    .append("\nNETO PRELIMINAR: $").append(String.format(Locale.US,"%.2f",net/100.0))
    .append("\n\nFecha de pago: ").append(dueLabel)
    .append("\n\nEl cálculo es preliminar; el patrón valida los movimientos y autorizaciones.")
   val savedDate=prefs.getString("server_saved_date",null)
   val reuseSaved=savedDate==date
   val reportToSend=if(reuseSaved)JSONObject(prefs.getString("server_saved_report","{}")?:"{}")else report
   val bodyText=if(reuseSaved)prefs.getString("server_saved_body",null)?:body.toString()else body.toString()
   runOnUiThread{
    val builder=AlertDialog.Builder(this).setTitle("REPORTE DE NÓMINA")
     .setMessage(bodyText).setNegativeButton("CERRAR",null)
    if(due)builder.setPositiveButton("ABRIR REPORTE EN WHATSAPP"){_,_->
     Thread{try{
      api.savePayrollReport(date,reportToSend)
      prefs.edit().putString("server_saved_date",date)
       .putString("server_saved_report",reportToSend.toString())
       .putString("server_saved_body",bodyText).apply()
      runOnUiThread{
       if(!openWhatsAppText(this,bodyText))msg("El reporte quedó guardado en SENE; no se pudo abrir WhatsApp.")
      }
     }catch(e:Exception){runOnUiThread{msg("No se pudo registrar el reporte de nómina: "+(e.message?:"Error"))}}}.start()
    }
    builder.show()
   }
  }catch(e:Exception){runOnUiThread{msg("No se pudo generar el reporte: "+(e.message?:"Error"))}}}.start()
 }
 private fun showSeneCloudConnection(){
  val prefs=getSharedPreferences("sene_ventas_server",MODE_PRIVATE)
  val baseInput=input("Dirección HTTPS oficial del servidor SENE")
  baseInput.setText(prefs.getString("server_base_url","")?: "")
  val device=prefs.getString("device_id",null)
  val slot=prefs.getString("device_slot",null)
  val state=if(!device.isNullOrBlank())"Este equipo está vinculado como ${slot?: "VENTAS"}." else "Este equipo todavía no está vinculado."
  AlertDialog.Builder(this).setTitle("Conexión SENE PATRÓN")
   .setMessage(state+"\n\nGuarda la dirección HTTPS del servidor. Para vincular, escanea el QR de un solo uso que genera SENE PATRÓN.")
   .setView(baseInput)
   .setNeutralButton("VERIFICAR") { _, _ ->
    val base=baseInput.text.toString().trim().trimEnd('/')
    if(!base.startsWith("https://")||Uri.parse(base).host.isNullOrBlank()){msg("Dirección HTTPS no válida");return@setNeutralButton}
    prefs.edit().putString("server_base_url",base).apply()
    Thread{try{
     val api=SeneVentasApiClient(this,base)
     if(api.storedDeviceId()==null)throw IllegalStateException("El equipo aún no está vinculado")
     val result=api.session()
     runOnUiThread{msg("Conexión correcta · "+result.optString("slot")+" · "+result.optString("licenseStatus"))}
    }catch(e:Exception){runOnUiThread{msg("No se pudo verificar: "+(e.message?:"Error"))}}}.start()
   }
   .setPositiveButton("GUARDAR Y ESCANEAR QR"){_,_->
    val base=baseInput.text.toString().trim().trimEnd('/')
    if(!base.startsWith("https://")||Uri.parse(base).host.isNullOrBlank()){msg("Dirección HTTPS no válida");return@setPositiveButton}
    prefs.edit().putString("server_base_url",base).apply()
    startActivityForResult(Intent(this,ScanActivity::class.java),MAIN_PAIR_SCAN_REQUEST)
   }.setNegativeButton("CANCELAR",null).show()
 }
 private fun finishQrPairing(values:ArrayList<String>){
  val token=values.lastOrNull{it.matches(Regex("^[a-fA-F0-9]{64}$"))||it.startsWith("SENEPAIR2:",true)||it.startsWith("SENEPAIR1:",true)}
  if(token.isNullOrBlank()){msg("No se encontró un QR SENE válido");return}
  val base=getSharedPreferences("sene_ventas_server",MODE_PRIVATE).getString("server_base_url",null)
  if(base.isNullOrBlank()){msg("Configura primero el servidor SENE");return}
  Thread{try{
   val result=SeneVentasApiClient(this,base).pairFromQr(token)
   runOnUiThread{msg("Equipo vinculado: "+result.optString("slot")+" · "+result.optString("businessName"))}
  }catch(e:Exception){runOnUiThread{msg("No se pudo vincular: "+(e.message?:"Error de conexión"))}}}.start()
 }
 private fun showCurrentSalesSummary(){
  val shift=Store.shift(this)?:JSONObject()
  val all=Store.sales(this)
  val today=day()
  val startTs=shift.optLong("startTs",0L)
  var dayTotal=0.0;var shiftTotal=0.0
  var dayUnits=0;var shiftUnits=0
  val dayTickets=mutableSetOf<Long>();val shiftTickets=mutableSetOf<Long>()
  for(i in 0 until all.length()){
   val sale=all.optJSONObject(i)?:continue
   val time=sale.optString("time")
   val ts=sale.optLong("ts",0L)
   val amount=sale.optDouble("subtotal",0.0)
   val qty=sale.optInt("qty",1)
   if(time.startsWith(today)){
    dayTotal+=amount;dayUnits+=qty
    dayTickets.add(if(ts>0L)ts else time.hashCode().toLong())
   }
   if(startTs>0L && ts>=startTs){
    shiftTotal+=amount;shiftUnits+=qty
    shiftTickets.add(if(ts>0L)ts else time.hashCode().toLong())
   }
  }
  val opening=shift.optDouble("cash",0.0)
  val providerExpenses=Store.providerExpenses(this,startTs)
  val estimated=opening+shiftTotal-providerExpenses
  val report=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
  fun summaryLine(label:String,value:String,emphasis:Boolean=false){
   val line=TextView(this).apply{
    text=label+"\n"+value;textSize=if(emphasis)21f else 16f
    setTextColor(if(emphasis)0xFF173B72.toInt() else 0xFF263653.toInt())
    typeface=Typeface.create("sans-serif",if(emphasis)Typeface.BOLD else Typeface.NORMAL)
    setPadding(dp(15),dp(12),dp(15),dp(12))
    background=rounded(if(emphasis)0xFFE8F0FF.toInt() else 0xFFF5F7FC.toInt(),14,0xFFDCE4F1.toInt())
    setLineSpacing(dp(3).toFloat(),1f)
   }
   val lp=LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(5);lp.bottomMargin=dp(5);report.addView(line,lp)
  }
  summaryLine("CAJA CON LA QUE EMPEZASTE",Store.money(opening))
  summaryLine("VENTAS DESDE QUE INICIASTE TURNO",Store.money(shiftTotal)+"\n"+shiftTickets.size+" ventas · "+shiftUnits+" piezas")
  summaryLine("PAGOS A PROVEEDORES",Store.money(providerExpenses))
  summaryLine("CAJA ACTUAL ESTIMADA",Store.money(estimated),true)
  summaryLine("VENTAS ACUMULADAS DEL DÍA",Store.money(dayTotal)+"\n"+dayTickets.size+" ventas · "+dayUnits+" piezas")
  report.addView(TextView(this).apply{
   text="La caja actual suma la caja inicial y las ventas del turno, y resta los pagos registrados a proveedores."
   textSize=12f;setTextColor(0xFF7A8298.toInt());setPadding(dp(5),dp(9),dp(5),dp(4))
  })
  AlertDialog.Builder(this).setTitle("VENTAS HASTA EL MOMENTO").setView(report)
   .setPositiveButton("CERRAR",null).show()
 }
 private fun showProviderExpenses(){
  val shift=Store.shift(this)?:JSONObject()
  val start=shift.optLong("startTs",0L)
  val moves=Store.moves(this)
  val rows=mutableListOf<String>()
  var total=0.0
  for(i in 0 until moves.length()){
   val move=moves.optJSONObject(i)?:continue
   if(move.optString("type")!="PROVIDER_PAYMENT"||move.optLong("ts",0L)<start)continue
   val amount=move.optDouble("amount",0.0)
   total+=amount
   rows.add(move.optString("time")+"\n"+move.optString("supplier","Proveedor")+" · "+move.optString("text")+"\nPago: "+Store.money(amount))
  }
  val view=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
  view.addView(txt("TOTAL PAGADO EN ESTE TURNO\n"+Store.money(total),19f))
  view.addView(txt("CAJA ACTUAL ESTIMADA\n"+Store.money(Store.cashBalance(this)),21f))
  view.addView(txt(if(rows.isEmpty())"Aún no hay pagos a proveedores registrados en este turno." else rows.asReversed().joinToString("\n\n"),14f))
  AlertDialog.Builder(this).setTitle("GASTOS A PROVEEDORES").setView(view).setPositiveButton("CERRAR",null).show()
 }
 private fun draw(){
  val v=page("VENTAS"){showToolsMenu()}
  val sales=Store.sales(this);var sum=0.0;var count=0
  for(i in 0 until sales.length()){val x=sales.optJSONObject(i)?:continue;if(x.optString("time").startsWith(day())){sum+=x.optDouble("subtotal");count++}}
  v.addView(txt("HOY  ·  $count ventas     "+Store.money(sum),14f).apply{gravity=Gravity.CENTER})
  v.addView(button("ESCANEAR PRODUCTO"){openScanner()})
  code=input("Código de barras");v.addView(code)
  v.addView(button("AGREGAR AL TICKET",false){addCode(code.text.toString());code.setText("")})
  ticketView=txt("Ticket vacío",18f);v.addView(ticketView)
  paid=input("¿Cuánto te pagan?",true);v.addView(paid)
  totalView=txt("TOTAL: $0.00\nCambio: $0.00 | Falta: $0.00",22f);v.addView(totalView)
  paid.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,d:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){refresh()};override fun afterTextChanged(s:android.text.Editable?) {}})
  sell=button("COBRAR Y REGISTRAR VENTA"){sellTicket()};v.addView(sell)
  v.addView(button("Limpiar ticket",false){ticket.clear();refresh()})
  Protection.addBlindButton(this,v)
  countdownView=txt("",13f).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD);setPadding(dp(8),dp(9),dp(8),dp(9))};v.addView(countdownView)
  display(v);refresh();updateReportCountdown();sell.isEnabled=!due();installSeneAdvisor()
 }
private fun reviewScannedCodes(codes:ArrayList<String>){
  if(codes.isEmpty()){msg("No se escanearon productos");return}
  val products=Store.products(this)
  val list=LinearLayout(this).apply{orientation=1;setPadding(dp(16),dp(8),dp(16),dp(8))}
  val intro=TextView(this).apply{text="Compara cada renglón con los productos que el cliente dejó en el mostrador. Marca únicamente los que coincidan.";textSize=14f;setTextColor(0xFF43564A.toInt());setPadding(0,0,0,dp(12))}
  list.addView(intro)
  val boxes=ArrayList<CheckBox>()
  for((index,codeValue) in codes.withIndex()){
   var productName:String?=null
   for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;if(p.optString("code")==codeValue){productName=p.optString("name");break}}
   val label=if(productName!=null)productName+"  •  "+codeValue else "NO REGISTRADO  •  "+codeValue
   val cb=CheckBox(this).apply{text="${index+1}. $label";textSize=14f;setTextColor(if(productName!=null)0xFF203A2B.toInt() else 0xFFB3261E.toInt());isChecked=false;setPadding(dp(4),dp(8),dp(4),dp(8))}
   boxes.add(cb);list.addView(cb)
   val divider=View(this).apply{setBackgroundColor(0xFFE2EAE4.toInt())}
   list.addView(divider,LinearLayout.LayoutParams(-1,dp(1)))
  }
  val scroll=ScrollView(this).apply{isFillViewport=true;isVerticalScrollBarEnabled=true;addView(list)}
  AlertDialog.Builder(this)
   .setTitle("Verificar productos (${codes.size})")
   .setView(scroll)
   .setPositiveButton("Agregar seleccionados"){_,_->
    var selected=0
    for(i in codes.indices){if(boxes[i].isChecked){selected++;addCode(codes[i])}}
    if(selected==0)msg("No seleccionaste productos verificados")
   }
   .setNegativeButton("Cancelar",null)
   .show()
 }
 private fun offerSupplierInventory(codeValue:String, productName:String?){
  val title=if(productName==null)"Producto no registrado" else "Producto sin existencia"
  val message=if(productName==null)
   "El código "+codeValue+" no está registrado. Si es un producto nuevo que llegó del proveedor, puedes darlo de alta y agregar la cantidad recibida."
   else
   productName+" no tiene existencia disponible. Si llegó mercancía del proveedor, puedes registrar únicamente la cantidad recibida."
  AlertDialog.Builder(this).setTitle(title).setMessage(message)
   .setPositiveButton("AGREGAR INVENTARIO"){_,_->
    startActivity(Intent(this,AgregarInventarioActivity::class.java).putExtra("prefill_code",codeValue))
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun addCode(c:String){
  if(c.isBlank()){msg("Escribe o escanea un código");return}
  val p=Store.products(this)
  for(i in 0 until p.length()){
   val x=p.optJSONObject(i)?:continue
   if(x.optString("code")==c){
    if(x.optInt("stock")<=0){offerSupplierInventory(c,x.optString("name"));return}
    ticket[c]=(ticket[c]?:0)+1;refresh();return
   }
  }
  offerSupplierInventory(c,null)
 }
 private fun refresh(){if(!::ticketView.isInitialized)return;val p=Store.products(this);val b=StringBuilder();var sum=0.0;for((c,q) in ticket){for(i in 0 until p.length()){val x=p.optJSONObject(i)?:continue;if(x.optString("code")==c){val sub=x.optDouble("price")*q;sum+=sub;b.append(x.optString("name")).append(" x").append(q).append(" = ").append(Store.money(sub)).append("\n");break}}};ticketView.text=if(b.isEmpty())"Ticket vacío" else b.toString();val diff=(parseAmount(paid.text.toString())?:0.0)-sum;totalView.text="TOTAL: "+Store.money(sum)+"\n"+if(diff>=0)"Cambio a dar: "+Store.money(diff) else "Falta: "+Store.money(-diff)}
 private fun sellTicket(){if(due()){showReportAlert();return};if(ticket.isEmpty()){msg("Ticket vacio. Escanea un producto.");return}
 val p=Store.products(this);val list=mutableListOf<Pair<JSONObject,Int>>();var sum=0.0
 for((c,q) in ticket){var found:JSONObject?=null;for(i in 0 until p.length()){val x=p.optJSONObject(i);if(x?.optString("code")==c){found=x;break}};if(found==null){msg("Producto no encontrado: "+c);return};if(found.optInt("stock")<q){msg("Stock insuficiente: "+found.optString("name")+". Quedan "+found.optInt("stock"));return};list.add(found to q);sum+=found.optDouble("price")*q}
 val pay=paid.text.toString().toDoubleOrNull()?:0.0;if(pay<sum){msg("Falta dinero");return};val s=Store.shift(this)?:JSONObject()
 val saleTime=stamp();val saleTs=System.currentTimeMillis();val saleItems=ArrayList<JSONObject>()
 for((x,q) in list){x.put("stock",x.optInt("stock")-q);saleItems.add(JSONObject().put("ts",saleTs).put("time",saleTime).put("code",x.optString("code")).put("name",x.optString("name")).put("qty",q).put("price",x.optDouble("price")).put("subtotal",x.optDouble("price")*q).put("worker",s.optString("worker")))}
 Store.saveProducts(this,p);Store.addSales(this,saleItems);val change=pay-sum;val n=list.size;ticket.clear();paid.setText("");msg("Venta guardada: "+n+" productos | Total "+Store.money(sum)+" | Cambio "+Store.money(change));draw()}
 private fun reportText(final:Boolean):String{val s=Store.shift(this)?:JSONObject();val all=Store.sales(this);val b=StringBuilder("SENE.VENTAS - "+if(final)"CIERRE DE TURNO" else "REPORTE");var sum=0.0;b.append("\nTrabajador: ").append(s.optString("worker")).append("\nFecha/hora celular: ").append(stamp()).append("\nCaja inicial: ").append(Store.money(s.optDouble("cash"))).append("\nVentas del turno")
 for(i in 0 until all.length()){val x=all.optJSONObject(i)?:continue;if(x.optLong("ts")>=s.optLong("startTs",0)){sum+=x.optDouble("subtotal");b.append("\n").append(x.optString("time")).append(" ").append(x.optString("name")).append(" x").append(x.optInt("qty")).append(" = ").append(Store.money(x.optDouble("subtotal")))}}
 val expenses=Store.providerExpenses(this,s.optLong("startTs",0L))
 b.append("\nTotal: ").append(Store.money(sum)).append("\nPagos a proveedores: ").append(Store.money(expenses)).append("\nCaja ahora: ").append(Store.money(s.optDouble("cash")+sum-expenses)).append("\nINVENTARIO_SENE_V1\ncodigo | nombre | precio | stock");val p=Store.products(this);for(i in 0 until p.length()){val x=p.optJSONObject(i)?:continue;b.append("\n").append(x.optString("code")).append(" | ").append(x.optString("name")).append(" | ").append(x.optDouble("price")).append(" | ").append(x.optInt("stock"))};return b.toString()}
 private fun sendReport(final:Boolean){
  if(Store.shift(this)==null)return
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(saved.isNullOrBlank()){
   pendingFinalReport=final
   AlertDialog.Builder(this).setTitle("Elegir carpeta para reportes")
    .setMessage("La primera vez, elige dónde guardar los reportes. Recomendamos crear una carpeta llamada “Inventario SENE” y seleccionarla. SENE-VENTAS organizará automáticamente los documentos en carpetas por fecha.")
    .setPositiveButton("ELEGIR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,TREE_REPORT_REQUEST)}
    .setNegativeButton("Cancelar",null).show()
   return
  }
  completeReport(final,Uri.parse(saved))
 }
 private var pendingReportText:String?=null
 private fun uploadShiftReportToServer(shift:JSONObject,file:java.io.File,final:Boolean){
  val prefs=getSharedPreferences("sene_ventas_server",MODE_PRIVATE)
  val base=prefs.getString("server_base_url",null)
  val device=prefs.getString("device_id",null)
  if(base.isNullOrBlank()||device.isNullOrBlank())return
  val sales=Store.sales(this)
  val start=shift.optLong("startTs",0L)
  var total=0.0
  var units=0
  var tickets=0
  val seen=mutableSetOf<Long>()
  for(i in 0 until sales.length()){
   val item=sales.optJSONObject(i)?:continue
   val ts=item.optLong("ts",0L)
   if(ts<start)continue
   total+=item.optDouble("subtotal",0.0)
   units+=item.optInt("qty",1)
   if(seen.add(if(ts>0L)ts else item.optString("time").hashCode().toLong()))tickets++
  }
  val expenses=Store.providerExpenses(this,start)
  val opening=shift.optDouble("cash",0.0)
  val report=JSONObject()
   .put("worker",shift.optString("worker"))
   .put("workerPhone",shift.optString("phone"))
   .put("shiftStarted",shift.optString("started"))
   .put("shiftStartTs",start)
   .put("shiftClosed",final)
   .put("shiftEndTs",if(final)System.currentTimeMillis() else 0L)
   .put("salesTotalCents",Math.round(total*100.0))
   .put("salesCount",tickets)
   .put("unitsSold",units)
   .put("providerExpensesCents",Math.round(expenses*100.0))
   .put("openingCashCents",Math.round(opening*100.0))
   .put("estimatedCashCents",Math.round((opening+total-expenses)*100.0))
   .put("localReportFile",file.name)
   .put("localReportUpdatedAt",file.lastModified())
  val shiftKey=start.toString()+":"+file.lastModified().toString()
  Thread{
   try{
    SeneVentasApiClient(this,base).saveShiftReport(shiftKey,report)
   }catch(e:Exception){
    runOnUiThread{Toast.makeText(this,"El TXT se guardó, pero no se pudo sincronizar el reporte con SENE PATRÓN: "+(e.message?:"sin conexión"),Toast.LENGTH_LONG).show()}
   }
  }.start()
 }
 private fun completeReport(final:Boolean,tree:Uri){
  val s=Store.shift(this)?:return
  try{
   if(final && s.optLong("closingTs",0L)==0L){s.put("closingTs",System.currentTimeMillis()).put("closed",stamp());Store.saveShift(this,s)}
   val file=ReportFile.update(this)
   val body=file.readText(Charsets.UTF_8)+REPORT_BACKUP_NOTE
   file.writeText(body,Charsets.UTF_8)
   val dailyUri=saveDailyReport(tree,file)
   // Solo se sincroniza el cierre final del turno; los cortes obligatorios de 2 horas
   // siguen siendo locales/WhatsApp y no consumen cuota remota semanal.
   if(final)uploadShiftReportToServer(s,file,true)
   pendingShareUri=dailyUri
   pendingReportText=body
   pendingFinalShare=final
   launchPendingReportShare()
  }catch(e:Exception){msg("No se pudo guardar el reporte. No se cerró el turno ni se reactivaron las ventas: "+(e.message?:"error"))}
 }
 private fun launchPendingReportShare(){
  val body=pendingReportText?:return
  openWhatsAppText(this,body)
 }
 private fun confirmReportSent(){
  if(confirmingReport||pendingShareUri==null)return
  confirmingReport=true
  AlertDialog.Builder(this).setTitle("Confirmar envío del reporte")
   .setMessage("¿Ya enviaste el reporte escrito por WhatsApp? El respaldo TXT completo ya está guardado en INVENTARIO SENE. Las ventas seguirán bloqueadas hasta que confirmes.")
   .setCancelable(false)
   .setPositiveButton("SÍ, YA LO ENVIÉ"){_,_->
    confirmingReport=false
    val final=pendingFinalShare;pendingShareUri=null;pendingReportText=null
    val shift=Store.shift(this)
    if(final){Store.saveShift(this,null);ticket.clear();startActivity(Intent(this,InicioTurnoActivity::class.java));finish()}
    else if(shift!=null){shift.put("lastReport",System.currentTimeMillis());Store.saveShift(this,shift);reportDialog?.dismiss();sell.isEnabled=true;sell.alpha=1f;updateReportCountdown();msg("Envío confirmado. Puedes continuar trabajando.")}
   }
   .setNegativeButton("VOLVER A ENVIAR"){_,_->confirmingReport=false;launchPendingReportShare()}
   .show()
 }
 private fun saveDailyReport(tree:Uri,source:java.io.File):Uri{
  val resolver=contentResolver
  val rootId=DocumentsContract.getTreeDocumentId(tree)
  val root=DocumentsContract.buildDocumentUriUsingTree(tree,rootId)
  val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
  fun findChild(parent:Uri,name:String):Uri?{
   val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
   resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
    val idCol=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val nameCol=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    while(cur.moveToNext()){if(nameCol>=0&&idCol>=0&&cur.getString(nameCol)==name)return DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(idCol))}
   }
   return null
  }
  val folder=findChild(root,date)?:DocumentsContract.createDocument(resolver,root,DocumentsContract.Document.MIME_TYPE_DIR,date)?:throw java.io.IOException("No se pudo crear la carpeta del día")
  val time=SimpleDateFormat("HH-mm-ss",Locale.US).format(Date())
  val name="SENE-VENTAS-$date-$time.txt"
  val target=DocumentsContract.createDocument(resolver,folder,"text/plain",name)?:throw java.io.IOException("No se pudo crear el TXT")
  resolver.openOutputStream(target,"w")?.use{out->source.inputStream().use{it.copyTo(out)}}?:throw java.io.IOException("No se pudo escribir el TXT")
  return target
 }
 private fun stock(){val p=Store.products(this);val b=StringBuilder();for(i in 0 until p.length()){val x=p.optJSONObject(i)?:continue;b.append(x.optString("code")).append(" | ").append(x.optString("name")).append(" | ").append(Store.money(x.optDouble("price"))).append(" | Stock ").append(x.optInt("stock")).append("\n")};AlertDialog.Builder(this).setTitle("Stock actual").setMessage(if(b.isEmpty())"Sin productos" else b.toString()).setPositiveButton("Cerrar",null).show()}
 private fun saveReportToPhone(){val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/plain";putExtra(Intent.EXTRA_TITLE,"SENE-VENTAS-REPORTE.txt")};try{startActivityForResult(intent,SAVE_REPORT_REQUEST)}catch(_:Exception){msg("No se pudo abrir el selector para guardar")}}
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==MAIN_PAIR_SCAN_REQUEST){if(c==RESULT_OK){finishQrPairing(d?.getStringArrayListExtra(SCAN_CODES)?:arrayListOf())};return};if(r==TREE_REPORT_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();completeReport(pendingFinalReport,tree)}catch(e:Exception){msg("No se pudo guardar el acceso a la carpeta: "+(e.message?:"error"))};return};if(r==SAVE_REPORT_REQUEST&&c==RESULT_OK){val uri=d?.data?:return;try{val file=ReportFile.update(this);contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}};msg("Reporte TXT guardado en la ubicación que elegiste")}catch(e:Exception){msg("No se pudo guardar el TXT: "+(e.message?:"error"))};return};if(r==SCAN_REQUEST&&c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty())reviewScannedCodes(codes);return};val x=IntentIntegrator.parseActivityResult(r,c,d);if(x?.contents!=null)addCode(x.contents)}
}

class AgregarInventarioActivity:Activity(){
 private lateinit var code:EditText;private lateinit var name:EditText;private lateinit var price:EditText;private lateinit var qty:EditText;private lateinit var providerPayment:EditText;private var existing:JSONObject?=null
 override fun onCreate(b:Bundle?){super.onCreate(b);draw();intent.getStringExtra("prefill_code")?.takeIf{it.isNotBlank()}?.let{code.setText(it);find()}}
 private lateinit var deviceClock:TextView;private lateinit var internetClock:TextView;private val clockHandler=Handler(Looper.getMainLooper());private val clockTick=object:Runnable{override fun run(){if(::deviceClock.isInitialized)deviceClock.text="Hora del celular: "+stamp();clockHandler.postDelayed(this,1000)}}
 override fun onResume(){super.onResume();if(::deviceClock.isInitialized)clockHandler.post(clockTick)}
 override fun onPause(){clockHandler.removeCallbacks(clockTick);super.onPause()}
 private fun consultProviderInternetTime(){internetClock.text="Hora de Internet: consultando…";Thread{val value=internetTimeMillis();val text=value?.let{SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(it))}?: "No disponible";runOnUiThread{if(::internetClock.isInitialized)internetClock.text="Hora de Internet: "+text}}.start()}
 private fun draw(){val v=page("Agregar Inventario - Llegó proveedor");deviceClock=txt("Hora del celular: "+stamp(),14f);internetClock=txt("Hora de Internet: consultando…",14f);v.addView(deviceClock);v.addView(internetClock);v.addView(txt("CAJA DISPONIBLE: "+Store.money(Store.cashBalance(this)),17f));v.addView(button("ACTUALIZAR HORA DE INTERNET",false){consultProviderInternetTime()});code=input("Escanea o escribe código");name=input("Descripción del producto");price=input("Precio de venta $",true);qty=input("¿Cuántos llegaron del proveedor?",true);providerPayment=input("¿Cuánto pagarás al proveedor? $",true);v.addView(code);v.addView(button("ESCANEAR CÓDIGO"){openScanner()});v.addView(button("Buscar producto",false){find()});v.addView(name);v.addView(price);v.addView(qty);v.addView(providerPayment);v.addView(txt("El pago se descontará de la caja al guardar. Si no pagarás ahora, escribe 0.",13f));v.addView(button("Continuar y confirmar"){confirm()});v.addView(button("Volver",false){finish()});display(v);consultProviderInternetTime()}
 private fun find(){existing=null;val p=Store.products(this);for(i in 0 until p.length()){val x=p.optJSONObject(i)?:continue;if(x.optString("code")==code.text.toString())existing=x};if(existing!=null){name.setText(existing!!.optString("name"));price.setText(existing!!.optDouble("price").toString());price.isEnabled=false;msg("Producto existente. El precio no se puede cambiar.")}else{price.isEnabled=true;name.setText("");price.setText("");msg("Producto nuevo. Captura descripción y precio.")}}
 private fun confirm(){val q=qty.text.toString().toIntOrNull();val payment=if(providerPayment.text.isBlank())0.0 else parseAmount(providerPayment.text.toString());val cash=Store.cashBalance(this);if(code.text.isBlank()||q==null||q<1){msg("Revisa código y cantidad");return};if(payment==null||payment<0){msg("Captura un pago válido");return};if(payment>cash){msg("El pago supera la caja disponible ("+Store.money(cash)+"). No se puede registrar.");return};if(existing==null&&(name.text.isBlank()||parseAmount(price.text.toString())==null)){msg("Captura descripción y precio");return};val old=existing?.optInt("stock")?:0;val summary="Código: "+code.text+"\nDescripción: "+name.text+"\nPrecio: "+price.text+"\nStock actual: "+old+"\nCantidad que llegó: "+q+"\nStock nuevo: "+(old+q)+"\n\nPago al proveedor: "+Store.money(payment)+"\nCaja antes del pago: "+Store.money(cash)+"\nCaja después del pago: "+Store.money(cash-payment)+"\n\nASEGÚRATE DE QUE LA CANTIDAD Y EL PAGO SEAN CORRECTOS. ESTA ACCIÓN NO SE PUEDE DESHACER."
 AlertDialog.Builder(this).setTitle("Confirmación 1 de 2").setMessage(summary).setPositiveButton("Sí, la cantidad es correcta"){_,_->AlertDialog.Builder(this).setTitle("¿Estás 100% seguro?").setMessage("Última oportunidad para revisar. Si guardas, ya no se puede deshacer.").setPositiveButton("SÍ, GUARDAR - YA REVISÉ"){_,_->save(q,payment)}.setNegativeButton("NO, ME EQUIVOQUÉ - REVISAR",null).show()}.setNegativeButton("No, revisar cantidad",null).show()}
 private fun presentCode(products:JSONArray,codeValue:String):Boolean{for(i in 0 until products.length())if(products.optJSONObject(i)?.optString("code")==codeValue)return true;return false}
 private fun save(q:Int,payment:Double){
  val currentCash=Store.cashBalance(this)
  if(payment>currentCash){msg("La caja cambió y ya no alcanza para ese pago. Revisa el monto.");return}
  val p=Store.products(this)
  val label=name.text.toString().trim()
  if(existing!=null){
   val index=(0 until p.length()).firstOrNull{p.optJSONObject(it)?.optString("code")==existing!!.optString("code")}?:run{msg("El producto ya no existe. No se guardó.");return}
   p.optJSONObject(index)!!.put("stock",p.optJSONObject(index)!!.optInt("stock")+q)
  }else{
   if(presentCode(p,code.text.toString().trim())){msg("Ese código ya existe. Busca el producto antes de agregar.");return}
   p.put(JSONObject().put("code",code.text.toString().trim()).put("name",label).put("price",price.text.toString().toDouble()).put("stock",q))
  }
  // El permiso de la carpeta INVENTARIO SENE pertenece al teléfono del patrón.
  // El trabajador debe poder registrar la recepción en su propio dispositivo sin bloquearse por ese permiso.
  val backup=StringBuilder("INVENTARIO_SENE_V1\\nActualizado: ").append(stamp()).append("\\nCódigo | Producto | Precio | Existencia")
  for(i in 0 until p.length()){
   val item=p.optJSONObject(i)?:continue
   backup.append("\\n").append(item.optString("code")).append(" | ").append(item.optString("name")).append(" | ").append(item.optDouble("price")).append(" | ").append(item.optInt("stock"))
  }
  var savedInSharedFolder=false
  val tree=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(!tree.isNullOrBlank())try{savedInSharedFolder=InventoryArchive.save(this,p)}catch(_:Exception){}
  try{
   if(!savedInSharedFolder)saveTextBackupLocal(this,"SENE-INGRESO-PROVEEDOR",backup.toString())
  }catch(e:Exception){msg("No se pudo crear el respaldo local: "+(e.message?:"error"));return}
  Store.saveProducts(this,p)
  val s=Store.shift(this)?:JSONObject()
  val now=System.currentTimeMillis()
  val time=stamp()
  Store.addMove(this,JSONObject().put("ts",now).put("time",time).put("text","INGRESO DE PROVEEDOR: +"+q+" "+label+" por "+s.optString("worker")))
  if(payment>0.0)Store.addMove(this,JSONObject().put("type","PROVIDER_PAYMENT").put("ts",now).put("time",time).put("amount",payment).put("supplier",label).put("text","PAGO A PROVEEDOR por ingreso de "+q+" "+label+" | "+Store.money(payment)).put("worker",s.optString("worker")))
  val where=if(savedInSharedFolder)"INVENTARIO SENE actualizado" else "respaldo guardado en este teléfono; el reporte de turno incluirá el stock actualizado"
  msg("Ingreso guardado. "+(if(payment>0.0)"Pago descontado de caja: "+Store.money(payment)+" | " else "")+"Caja actual: "+Store.money(Store.cashBalance(this))+" | "+where)
  finish()
}
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==SCAN_REQUEST&&c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty()){code.setText(codes.last());find()};return};val x=IntentIntegrator.parseActivityResult(r,c,d);if(x?.contents!=null){code.setText(x.contents);find()}}
}

class PatronActivity:Activity(){
 private var pendingInventorySave=false
 override fun onCreate(b:Bundle?){super.onCreate(b);pin{draw()}}
 private fun draw(){val v=page("Administrador - Patrón");v.addView(button("Hacer primer inventario"){openFirstInventory()});v.addView(button("Agregar o modificar producto",false){product()});v.addView(button("Cambiar número de WhatsApp",false){val e=input("Número con código de país");e.setText(Store.phone(this));AlertDialog.Builder(this).setTitle("WhatsApp para reportes").setView(e).setPositiveButton("Guardar"){_,_->Store.setPhone(this,e.text.toString().filter{it.isDigit()});msg("Número guardado")}.setNegativeButton("Cancelar",null).show()});v.addView(button("Ver inventario actual",false){inventory()});v.addView(button("Guardar TXT del stock",false){export()});v.addView(button("Cargar inventario desde TXT",false){chooseInventoryFolder()});v.addView(button("Volver a ventas",false){finish()});display(v)}
 private var pendingFirstInventory=false
 private var firstInventoryCode:EditText?=null
 private val firstInventoryItems=JSONArray()
 private fun openFirstInventory(){
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(saved.isNullOrBlank()){
   pendingFirstInventory=true
   AlertDialog.Builder(this).setTitle("Crear carpeta INVENTARIO SENE").setMessage("Primero crea una carpeta nueva llamada INVENTARIO SENE. En el selector de archivos, pulsa Nueva carpeta, escribe exactamente INVENTARIO SENE, ábrela y presiona Usar esta carpeta. Ahí se guardará tu inventario con fecha y hora.")
    .setPositiveButton("CREAR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,INVENTORY_TREE_REQUEST)}.setNegativeButton("Cancelar",null).show()
  }else firstInventoryScreen()
 }
 private fun firstInventoryScreen(){
  while(firstInventoryItems.length()>0)firstInventoryItems.remove(firstInventoryItems.length()-1)
  val root=page("Primer inventario");root.addView(txt("Escanea cada producto y captura su descripción, cantidad inicial y precio. Pulsa AGREGAR PRODUCTO para incluirlo en la lista."))
  val code=input("Código de barras / código del producto");firstInventoryCode=code
  val name=input("Descripción del producto");val qty=input("Cantidad inicial",true);val price=input("Precio de venta $",true)
  root.addView(code);root.addView(button("ESCANEAR PRODUCTO"){openScanner()});root.addView(name);root.addView(qty);root.addView(price)
  val list=LinearLayout(this).apply{orientation=1};val count=txt("Productos agregados: 0",15f);root.addView(count);root.addView(list)
  root.addView(button("AGREGAR PRODUCTO"){
   val q=qty.text.toString().toIntOrNull();val p=parseAmount(price.text.toString())
   if(code.text.isBlank()||name.text.isBlank()||q==null||q<0||p==null||p<0){msg("Captura código, descripción, cantidad y precio válidos");return@button}
   for(i in 0 until firstInventoryItems.length())if(firstInventoryItems.optJSONObject(i)?.optString("code")==code.text.toString().trim()){msg("Ese código ya está en la lista");return@button}
   firstInventoryItems.put(JSONObject().put("code",code.text.toString().trim()).put("name",name.text.toString().trim()).put("stock",q).put("price",p))
   list.addView(txt(code.text.toString()+" | "+name.text.toString()+"\nCantidad: "+q+"   Precio: "+Store.money(p),14f));count.text="Productos agregados: "+firstInventoryItems.length()
   code.setText("");name.setText("");qty.setText("");price.setText("");code.requestFocus()
  })
  root.addView(button("GUARDAR INVENTARIO"){
   if(firstInventoryItems.length()==0){msg("Agrega por lo menos un producto antes de guardar");return@button}
   AlertDialog.Builder(this).setTitle("Guardar inventario").setMessage("Se guardarán "+firstInventoryItems.length()+" productos en INVENTARIO SENE con la fecha y hora actuales. ¿Continuar?")
    .setPositiveButton("GUARDAR"){_,_->try{if(InventoryArchive.save(this,firstInventoryItems)){Store.saveProducts(this,firstInventoryItems);getSharedPreferences("sene_ventas_local",0).edit().putBoolean("first_inventory_created",true).apply();msg("Inventario guardado correctamente en INVENTARIO SENE");draw()}else msg("No se pudo guardar. Verifica el acceso a INVENTARIO SENE.")}catch(e:Exception){msg("Error al guardar: "+(e.message?:"desconocido"))}}
    .setNegativeButton("Revisar",null).show()
  })
  root.addView(button("VOLVER",false){draw()});display(root)
 }
 private fun saveInventorySnapshot(){try{if(InventoryArchive.save(this,Store.products(this))){msg("Inventario guardado en INVENTARIO SENE");}else{pendingInventorySave=true;AlertDialog.Builder(this).setTitle("Crear carpeta INVENTARIO SENE").setMessage("En el selector, crea una carpeta nueva llamada INVENTARIO SENE, ábrela y selecciónala. Ahí se guardará tu primer inventario y los siguientes TXT.") .setPositiveButton("CREAR / SELECCIONAR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,INVENTORY_TREE_REQUEST)}.setNegativeButton("Después",null).show()}}catch(e:Exception){msg("No se pudo guardar el inventario en INVENTARIO SENE: "+(e.message?:"error"))}}
 private var waitingProductScan=false
 private fun product(){
  waitingProductScan=true
  AlertDialog.Builder(this).setTitle("Agregar o modificar producto")
   .setMessage("Enciende el escáner para localizar el producto. Después podrás agregar existencia, quitar existencia o modificar el precio.")
   .setPositiveButton("ENCENDER ESCÁNER"){_,_->openScanner()}
   .setNegativeButton("Cancelar",null).show()
 }
 private fun showProductActions(codeValue:String){
  val products=Store.products(this)
  var found:JSONObject?=null
  for(i in 0 until products.length()){val item=products.optJSONObject(i);if(item?.optString("code")==codeValue){found=item;break}}
  if(found==null){
   val name=input("Descripción del producto");val price=input("Precio de venta $",true);val stock=input("Existencia inicial",true)
   val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8));addView(txt("Código escaneado: "+codeValue));addView(name);addView(price);addView(stock)}
   AlertDialog.Builder(this).setTitle("Producto nuevo").setView(box).setPositiveButton("GUARDAR"){_,_->
    val p=parseAmount(price.text.toString());val q=stock.text.toString().toIntOrNull()
    if(name.text.isBlank()||p==null||p<0||q==null||q<0){msg("Completa descripción, precio y existencia válidos");return@setPositiveButton}
    products.put(JSONObject().put("code",codeValue).put("name",name.text.toString().trim()).put("price",p).put("stock",q))
    Store.saveProducts(this,products);saveInventorySnapshot();msg("Producto nuevo agregado")
   }.setNegativeButton("Cancelar",null).show()
   return
  }
  val details=found!!
  AlertDialog.Builder(this).setTitle(details.optString("name"))
   .setMessage("Código: "+codeValue+"\nPrecio: "+Store.money(details.optDouble("price"))+"\nExistencia: "+details.optInt("stock"))
   .setItems(arrayOf("AGREGAR MÁS","QUITAR PRODUCTO","MODIFICAR PRECIO")){_,which->
    when(which){
     0->adjustStock(codeValue,details,true)
     1->adjustStock(codeValue,details,false)
     2->changeProductPrice(codeValue,details)
    }
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun adjustStock(codeValue:String,item:JSONObject,add:Boolean){
  val amount=input(if(add)"Cantidad que deseas agregar" else "Cantidad que deseas quitar",true)
  AlertDialog.Builder(this).setTitle(if(add)"Agregar existencia" else "Quitar existencia").setView(amount)
   .setPositiveButton("CONTINUAR"){_,_->
    val q=amount.text.toString().toIntOrNull()
    if(q==null||q<1){msg("Escribe una cantidad válida");return@setPositiveButton}
    val current=item.optInt("stock")
    if(!add&&q>current){msg("No puedes quitar más de lo que existe");return@setPositiveButton}
    val products=Store.products(this)
    for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;if(p.optString("code")==codeValue){p.put("stock",current+(if(add)q else -q));break}}
    Store.saveProducts(this,products);saveInventorySnapshot()
    Store.addMove(this,JSONObject().put("time",stamp()).put("text",(if(add)"AJUSTE PATRÓN: +" else "AJUSTE PATRÓN: -")+q+" "+item.optString("name")))
    msg(if(add)"Existencia agregada" else "Existencia retirada")
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun changeProductPrice(codeValue:String,item:JSONObject){
  val price=input("Nuevo precio de venta $",true);price.setText(item.optDouble("price").toString())
  AlertDialog.Builder(this).setTitle("Modificar precio").setView(price).setPositiveButton("GUARDAR"){_,_->
   val value=parseAmount(price.text.toString())
   if(value==null||value<0){msg("Escribe un precio válido");return@setPositiveButton}
   val products=Store.products(this)
   for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;if(p.optString("code")==codeValue){p.put("price",value);break}}
   Store.saveProducts(this,products);saveInventorySnapshot();msg("Precio actualizado")
  }.setNegativeButton("Cancelar",null).show()
 }
 private fun inventory(){val a=Store.products(this);val b=StringBuilder();for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;b.append(x.optString("code")).append(" | ").append(x.optString("name")).append(" | ").append(Store.money(x.optDouble("price"))).append(" | ").append(x.optInt("stock")).append("\n")};AlertDialog.Builder(this).setTitle("Inventario actual").setMessage(if(b.isEmpty())"Sin productos" else b.toString()).setPositiveButton("Cerrar",null).show()}
 private fun chooseInventoryFolder(){
  // Abrir directamente el selector de archivos, sin opciones intermedias.
  pickInventoryTxt()
 }
 private fun pickInventoryTxt(){
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
   addCategory(Intent.CATEGORY_OPENABLE)
   type="*/*"
   putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/plain","application/octet-stream","text/*"))
   // Abrir inicialmente en la carpeta de inventarios que ya configuró el patrón.
   if(!saved.isNullOrBlank())try{
    val tree=Uri.parse(saved)
    val initial=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
    putExtra(DocumentsContract.EXTRA_INITIAL_URI,initial)
   }catch(_:Exception){}
   addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
  }
  try{startActivityForResult(pick,IMPORT_FILE_REQUEST)}catch(e:Exception){msg("No se pudo abrir el selector de archivos TXT: "+(e.message?:"error"))}
 }
 private fun confirmSelectedInventory(uri:Uri){
  try{
   val selected=InventoryReader.readSnapshot(this,uri)
   AlertDialog.Builder(this).setTitle("Confirmar carga de inventario")
    .setMessage("Archivo: "+selected.file+"\nFecha de actualización: "+selected.updated+"\nProductos encontrados: "+selected.products.length()+"\n\nAl cargarlo se reemplazará el inventario actual. ¿Continuar?")
    .setPositiveButton("CARGAR"){_,_->Store.saveProducts(this,selected.products);getSharedPreferences("sene_ventas_local",0).edit().putBoolean("first_inventory_created",true).putString("inventory_source_file",selected.file).putString("inventory_source_updated",selected.updated).putString("inventory_source_uri",uri.toString()).apply();msg("Inventario seleccionado cargado correctamente. Ya puedes iniciar turno sin volver a elegir carpeta.");draw()}
    .setNegativeButton("Cancelar",null).show()
  }catch(e:Exception){AlertDialog.Builder(this).setTitle("No se pudo cargar el TXT").setMessage((e.message?:"El archivo no es un inventario SENE válido.")+"\n\nAbre INVENTARIO SENE y selecciona un archivo TXT de inventario con fecha.").setPositiveButton("Entendido",null).show()}
 }
 private fun browseInventoryFolder(){
  AlertDialog.Builder(this).setTitle("Abrir INVENTARIO SENE")
   .setMessage("Selecciona la carpeta INVENTARIO SENE donde están guardados los inventarios. Si no aparece o está vacía, puedes buscar otra carpeta.")
   .setPositiveButton("BUSCAR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,IMPORT_TREE_REQUEST)}
   .setNeutralButton("ELEGIR ARCHIVO TXT"){_,_->pickInventoryTxt()}
   .setNegativeButton("Cancelar",null).show()
 }
 private fun loadLatestInventory(tree:Uri){
  try{
   val snapshots=InventoryReader.listSnapshots(this,tree).sortedByDescending{snapshot->try{SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).parse(snapshot.updated)?.time?:0L}catch(_:Exception){0L}}
   if(snapshots.isEmpty()){
    AlertDialog.Builder(this).setTitle("No hay inventarios válidos")
     .setMessage("No se encontraron archivos de inventario válidos en esta carpeta. Puedes buscar otra carpeta donde esté guardado INVENTARIO SENE.")
     .setPositiveButton("BUSCAR OTRA CARPETA"){_,_->browseInventoryFolder()}
     .setNegativeButton("Cerrar",null).show()
    return
   }
   val labels=snapshots.map{snapshot->snapshot.folder+"  •  "+snapshot.file+"\nActualizado: "+snapshot.updated+"  •  "+snapshot.products.length()+" productos"}.toTypedArray()
   AlertDialog.Builder(this).setTitle("Selecciona el inventario que deseas cargar")
    .setItems(labels){_,which->
     val selected=snapshots[which]
     AlertDialog.Builder(this).setTitle("Confirmar carga de inventario")
      .setMessage("Carpeta: "+selected.folder+"\nArchivo: "+selected.file+"\nÚltima actualización: "+selected.updated+"\nProductos encontrados: "+selected.products.length()+"\n\nAl cargarlo se reemplazará el inventario actual. ¿Continuar?")
      .setPositiveButton("CARGAR"){_,_->Store.saveProducts(this,selected.products);getSharedPreferences("sene_ventas_local",0).edit().putBoolean("first_inventory_created",true).putString("inventory_source_file",selected.file).putString("inventory_source_updated",selected.updated).apply();msg("Inventario seleccionado cargado correctamente");draw()}
      .setNeutralButton("VOLVER A LA LISTA"){_,_->loadLatestInventory(tree)}
      .setNegativeButton("Cancelar",null).show()
    }.setNegativeButton("BUSCAR OTRA CARPETA"){_,_->browseInventoryFolder()}.show()
  }catch(e:Exception){
   AlertDialog.Builder(this).setTitle("No se pudo leer la carpeta")
    .setMessage("No se pudieron consultar los inventarios. Puedes seleccionar otra carpeta.\n\nDetalle: "+(e.message?:"error"))
    .setPositiveButton("BUSCAR OTRA CARPETA"){_,_->browseInventoryFolder()}
    .setNegativeButton("Cerrar",null).show()
  }
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==SCAN_REQUEST&&c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty()){if(waitingProductScan){waitingProductScan=false;showProductActions(codes.last())}else firstInventoryCode?.setText(codes.last())};return};if(r==INVENTORY_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();if(pendingFirstInventory){pendingFirstInventory=false;firstInventoryScreen()}else if(pendingInventorySave){pendingInventorySave=false;if(InventoryArchive.save(this,Store.products(this)))msg("Primer inventario guardado en INVENTARIO SENE")}else msg("Carpeta INVENTARIO SENE guardada")}catch(e:Exception){msg("No se pudo guardar el acceso a INVENTARIO SENE: "+(e.message?:"error"))};return};if(r==IMPORT_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();loadLatestInventory(tree)}catch(e:Exception){msg("No se pudo abrir la carpeta: "+(e.message?:"error"))};return};if(r==IMPORT_FILE_REQUEST&&c==RESULT_OK){val uri=d?.data?:return;try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};confirmSelectedInventory(uri)}}
 private fun export(){try{if(InventoryArchive.save(this,Store.products(this)))msg("TXT de inventario guardado en INVENTARIO SENE")else{pendingInventorySave=true;AlertDialog.Builder(this).setTitle("Crear carpeta INVENTARIO SENE").setMessage("Crea una carpeta nueva llamada INVENTARIO SENE, ábrela y selecciónala para guardar el TXT.") .setPositiveButton("CREAR / SELECCIONAR"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,INVENTORY_TREE_REQUEST)}.setNegativeButton("Cancelar",null).show()}}catch(e:Exception){msg("No se pudo guardar el TXT: "+(e.message?:"error"))}}
}

private const val MAIN_PAIR_SCAN_REQUEST=7420
private const val SCAN_REQUEST=7412
private const val SAVE_REPORT_REQUEST=7413
private const val TREE_REPORT_REQUEST=7414
private const val IMPORT_TREE_REQUEST=7415
private const val IMPORT_FILE_REQUEST=7418
private const val START_INVENTORY_TREE_REQUEST=7416
private const val INVENTORY_TREE_REQUEST=7417
private const val SCAN_CODES="scan_codes"
private fun Activity.showVentasSamePhoneRestoreDialog(){
 val prefs=getSharedPreferences("sene_ventas_server",0)
 val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
 val base=input("Dirección HTTPS oficial del servidor SENE").apply{setText(prefs.getString("server_base_url","")?: "")}
 val company=input("Nombre exacto de la empresa registrada")
 val email=input("Correo registrado de la empresa").apply{inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS}
 val phone=input("Teléfono registrado con lada").apply{inputType=android.text.InputType.TYPE_CLASS_PHONE}
 form.addView(base);form.addView(company);form.addView(email);form.addView(phone)
 AlertDialog.Builder(this).setTitle("RESTAURAR SENE VENTAS · MISMO TELÉFONO")
  .setMessage("Sin costo después de reinstalar en el mismo teléfono. El servidor comprobará el identificador físico, la empresa y los datos registrados. No crea otra licencia ni ocupa otro espacio.")
  .setView(form)
  .setPositiveButton("VERIFICAR Y RESTAURAR"){_,_->
   val url=base.text.toString().trim().trimEnd('/')
   val business=company.text.toString().trim()
   val mail=email.text.toString().trim()
   val number=phone.text.toString().filter{it.isDigit()}
   if(!url.startsWith("https://")||Uri.parse(url).host.isNullOrBlank()||business.isBlank()||
      !android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches()||number.length !in 10..15){
    msg("Revisa servidor HTTPS, empresa, correo y teléfono registrados")
    return@setPositiveButton
   }
   prefs.edit().putString("server_base_url",url).apply()
   msg("Verificando que sea el mismo teléfono y restaurando el espacio original…")
   Thread{try{
    val result=SeneVentasApiClient(this,url).restoreSamePhone(business,mail,number)
    runOnUiThread{msg("Vinculación restaurada: "+result.optString("slot")+" · "+result.optString("businessName"))}
   }catch(e:Exception){
    runOnUiThread{msg("No se pudo restaurar: "+(e.message?:"El servidor no pudo verificar este teléfono"))}
   }}.start()
  }.setNegativeButton("CANCELAR",null).show()
}
private fun Activity.openScanner(){startActivityForResult(Intent(this,ScanActivity::class.java),SCAN_REQUEST)}
class ScanActivity:Activity(){
 private lateinit var scanner:DecoratedBarcodeView
 private val scanned=ArrayList<String>()
 private val handler=Handler(Looper.getMainLooper())
 private var lastCode=""
 private var lastAt=0L
 private var tone:ToneGenerator?=null
 override fun onCreate(b:Bundle?){super.onCreate(b);requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);WindowCompat.setDecorFitsSystemWindows(window,false);window.statusBarColor=0xFF111A35.toInt();window.navigationBarColor=0xFF111A35.toInt();if(Build.VERSION.SDK_INT>=29){window.isNavigationBarContrastEnforced=false;window.isStatusBarContrastEnforced=false};window.decorView.systemUiVisibility=0;if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(android.Manifest.permission.CAMERA),91);return};setupScanner()}
 private fun setupScanner(){
  val root=LinearLayout(this).apply{
   orientation=1
   background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF111A35.toInt(),0xFF202F58.toInt()))
  }
  ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
   val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
   view.setPadding(dp(18),bars.top+dp(8),dp(18),bars.bottom+dp(16))
   insets
  }
  val title=TextView(this).apply{
   text="SENE  /  ESCÁNER";textSize=20f;gravity=Gravity.CENTER
   setTextColor(-1);letterSpacing=.08f;typeface=Typeface.create("sans-serif",Typeface.BOLD)
   setPadding(dp(12),dp(13),dp(12),dp(15))
  }
  root.addView(title,LinearLayout.LayoutParams(-1,-2))
  scanner=DecoratedBarcodeView(this)
  scanner.barcodeView.decoderFactory=DefaultDecoderFactory(BarcodeFormat.values().toList())
  val cameraParams=LinearLayout.LayoutParams(-1,0,1f);cameraParams.topMargin=dp(6);cameraParams.bottomMargin=dp(12)
  root.addView(scanner,cameraParams)
  val status=TextView(this).apply{
   text="ENFOCA EL CÓDIGO  •  ESCANEO CONTINUO"
   textSize=13f;gravity=Gravity.CENTER;letterSpacing=.035f;setTextColor(0xFF26365D.toInt())
   setPadding(dp(16),dp(14),dp(16),dp(14));background=rounded(0xFFF3F5FC.toInt(),15,0xFFD5DDF0.toInt())
  }
  root.addView(status,LinearLayout.LayoutParams(-1,-2))
  val back=Button(this).apply{
   text="VOLVER";textSize=15f;isAllCaps=false;typeface=Typeface.create("sans-serif",Typeface.BOLD);letterSpacing=.05f
   setTextColor(-1);background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFFFFB547.toInt(),0xFFE68C36.toInt())).apply{cornerRadius=dp(15).toFloat()}
   setOnClickListener{finishWithResults()}
  }
  val backParams=LinearLayout.LayoutParams(-1,dp(54));backParams.topMargin=dp(12)
  root.addView(back,backParams)
  setContentView(root);ViewCompat.requestApplyInsets(root);tone=ToneGenerator(AudioManager.STREAM_MUSIC,100)
  scanner.decodeContinuous(object:BarcodeCallback{
   override fun barcodeResult(result:BarcodeResult?){
    val value=result?.text?.trim().orEmpty();if(value.isEmpty())return
    val now=System.currentTimeMillis();if(value==lastCode&&now-lastAt<1300)return
    lastCode=value;lastAt=now;scanned.add(value);scanner.setTorchOn()
    tone?.startTone(ToneGenerator.TONE_PROP_BEEP,700);handler.removeCallbacksAndMessages(null)
    handler.postDelayed({try{scanner.setTorchOff()}catch(_:Exception){}},450)
    status.text="Detectados: "+scanned.size+"\nÚltimo código: "+value+"\nSigue escaneando; presiona VOLVER para revisar la lista."
   }
   override fun possibleResultPoints(points:MutableList<com.google.zxing.ResultPoint>?){}
  })
 }
 override fun onResume(){super.onResume();if(::scanner.isInitialized)scanner.resume()}
 override fun onPause(){if(::scanner.isInitialized)scanner.pause();super.onPause()}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==91){if(g.isNotEmpty()&&g[0]==PackageManager.PERMISSION_GRANTED)setupScanner()else{msg("Se necesita permiso de cámara para escanear");finish()}}}
 private fun finishWithResults(){setResult(RESULT_OK,Intent().putStringArrayListExtra(SCAN_CODES,scanned));finish()}
 override fun onBackPressed(){finishWithResults()}
 override fun onDestroy(){handler.removeCallbacksAndMessages(null);if(::scanner.isInitialized)try{scanner.setTorchOff()}catch(_:Exception){};tone?.release();tone=null;super.onDestroy()}
}


private fun saveReportToDownloads(activity:Activity,file:java.io.File):Uri? {
 if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q)return null
 val resolver=activity.contentResolver
 val values=android.content.ContentValues().apply {
  put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"SENE-VENTAS-INICIO-TURNO.txt")
  put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain")
  put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,android.os.Environment.DIRECTORY_DOWNLOADS+"/SENE-VENTAS")
  put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
 }
 val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:return null
 try {
  resolver.openOutputStream(uri,"w")?.use{out->file.inputStream().use{input->input.copyTo(out)}}?:throw java.io.IOException("No se pudo escribir el reporte")
  val done=android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)}
  resolver.update(uri,done,null,null)
  return uri
 }catch(e:Exception){resolver.delete(uri,null,null);throw e}
}

private const val REPORT_BACKUP_NOTE="\n\nGuarde este reporte en una carpeta segura. Será su respaldo de inventario hasta este momento."
private fun saveTextBackupLocal(activity:Activity,prefix:String,body:String):java.io.File {
 val folder=java.io.File(activity.getExternalFilesDir(null)?:activity.filesDir,"SENE VENTAS/INVENTARIO SENE")
 if(!folder.exists()&&!folder.mkdirs())throw java.io.IOException("No se pudo crear la carpeta privada de respaldo SENE VENTAS")
 val date=SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS",Locale.US).format(Date())
 val target=java.io.File(folder,"$prefix-$date.txt")
 target.writeText(body,Charsets.UTF_8)
 if(!target.exists()||target.length()==0L)throw java.io.IOException("No se pudo verificar el TXT de respaldo")
 return target
}
private fun saveTextBackup(activity:Activity,tree:Uri,prefix:String,body:String):Uri {
 val resolver=activity.contentResolver
 val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
 val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
 fun child(parent:Uri,name:String):Uri? {
  val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
  resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
   val id=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val col=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
   while(cur.moveToNext())if(id>=0&&col>=0&&cur.getString(col)==name)return DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(id))
  }
  return null
 }
 val folder=child(root,date)?:DocumentsContract.createDocument(resolver,root,DocumentsContract.Document.MIME_TYPE_DIR,date)?:throw java.io.IOException("No se pudo crear la carpeta de respaldo")
 val time=SimpleDateFormat("HH-mm-ss-SSS",Locale.US).format(Date())
 val target=DocumentsContract.createDocument(resolver,folder,"text/plain","$prefix-$date-$time.txt")?:throw java.io.IOException("No se pudo crear el TXT de respaldo")
 resolver.openOutputStream(target,"w")?.use{it.write(body.toByteArray(Charsets.UTF_8))}?:throw java.io.IOException("No se pudo guardar el TXT de respaldo")
 return target
}
private fun openWhatsAppText(activity:Activity,body:String):Boolean {
 var target=Store.phone(activity).filter{it.isDigit()}
 if(target.isBlank()){activity.msg("Configura primero el número de WhatsApp del patrón en Soy Patrón");return false}
 if(target.length==10)target="52"+target
 if(target.startsWith("521")&&target.length==13)target="52"+target.substring(3)
 if(target.length<12){activity.msg("El número de WhatsApp del patrón no parece válido. Incluye lada y código de país.");return false}
 val safeBody=if(body.length>55000)body.take(55000)+"\n\n[Reporte recortado. El respaldo TXT contiene el reporte completo.]" else body
 val encoded=URLEncoder.encode(safeBody,"UTF-8")
 val links=listOf(
  Uri.parse("https://api.whatsapp.com/send?phone="+target+"&text="+encoded),
  Uri.parse("https://wa.me/"+target+"?text="+encoded)
 )
 for(pkg in listOf("com.whatsapp","com.whatsapp.w4b")){
  for(link in links){
   try{
    val intent=Intent(Intent.ACTION_VIEW,link).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    activity.startActivity(intent);return true
   }catch(_:Exception){}
  }
 }
 // Último intento: dejar que Android resuelva el enlace al chat con WhatsApp o navegador.
 try {
  activity.startActivity(Intent(Intent.ACTION_VIEW,links.last()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  return true
 } catch(_:Exception) {}
 activity.msg("No se pudo abrir el chat del patrón. Verifica que WhatsApp esté instalado y que el número de Soy Patrón incluya lada y código de país.")
 return false
}

private fun openWhatsAppDocument(activity:Activity,uri:Uri,subject:String){ openWhatsAppText(activity,subject+REPORT_BACKUP_NOTE) }

object ReportFile {
 @Synchronized fun update(c:android.content.Context, root:org.json.JSONObject?=null):java.io.File {
  val data=root ?: run { val pref=c.getSharedPreferences("sene_ventas_local",0).getString("datos",null); try{if(pref==null)org.json.JSONObject() else org.json.JSONObject(pref)}catch(_:Exception){org.json.JSONObject()} }
  val shift=data.optJSONObject("shift") ?: org.json.JSONObject()
  data.put("sales",Store.sales(c)).put("moves",Store.moves(c))
  val out=StringBuilder("SENE-VENTAS - REPORTE DE MOVIMIENTOS\n")
  out.append("Actualizado: ").append(stamp()).append("\nTrabajador: ").append(shift.optString("worker","-")).append("\nNúmero del trabajador: ").append(shift.optString("phone","-")).append("\nInicio de turno: ").append(shift.optString("started","-")).append("\nFecha y hora de cierre: ").append(shift.optString("closed","Turno en curso")).append("\nÚltima actualización de stock: ").append(Store.stockUpdatedAtText(c)).append("\nCaja inicial: ").append(Store.money(shift.optDouble("cash"))).append("\n\nVENTAS\n")
  val sales=data.optJSONArray("sales") ?: org.json.JSONArray();var total=0.0
  for(i in 0 until sales.length()){val x=sales.optJSONObject(i)?:continue;if(x.optLong("ts")>=shift.optLong("startTs",0)){total+=x.optDouble("subtotal");out.append(x.optString("time")).append(" | ").append(x.optString("name")).append(" x").append(x.optInt("qty")).append(" | ").append(Store.money(x.optDouble("subtotal"))).append("\n")}}
  val moves=data.optJSONArray("moves") ?: org.json.JSONArray()
  var providerTotal=0.0
  for(i in 0 until moves.length()){val x=moves.optJSONObject(i)?:continue;if(x.optString("type")=="PROVIDER_PAYMENT"&&x.optLong("ts",0L)>=shift.optLong("startTs",0L))providerTotal+=x.optDouble("amount",0.0)}
  out.append("TOTAL VENDIDO: ").append(Store.money(total)).append("\nPAGOS A PROVEEDORES: ").append(Store.money(providerTotal)).append("\nCAJA ACTUAL: ").append(Store.money(shift.optDouble("cash")+total-providerTotal)).append("\n\nMOVIMIENTOS DE INVENTARIO Y GASTOS\n")
  for(i in 0 until moves.length()){val x=moves.optJSONObject(i)?:continue;out.append(x.optString("time")).append(" | ").append(x.optString("text")).append("\n")}
  out.append("\nSTOCK ACTUAL\n");val products=data.optJSONArray("products") ?: org.json.JSONArray()
  for(i in 0 until products.length()){val x=products.optJSONObject(i)?:continue;out.append(x.optString("code")).append(" | ").append(x.optString("name")).append(" | ").append(Store.money(x.optDouble("price"))).append(" | Existencia: ").append(x.optInt("stock")).append("\n")}
  val dir=c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: c.filesDir
  if(!dir.exists())dir.mkdirs()
  val file=java.io.File(dir,"SENE-VENTAS-REPORTE.txt")
  file.writeText(out.toString(),Charsets.UTF_8)
  return file
 }
}
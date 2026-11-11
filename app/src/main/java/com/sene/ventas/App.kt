package com.sene.ventas
import android.app.*
import android.content.*
import android.graphics.Typeface
import android.graphics.Rect
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private const val GREEN=0xFF168B45.toInt()
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

private fun Activity.actionCard(label:String,description:String,icon:String,tint:Int=0xFFEAF2FF.toInt(),f:()->Unit)=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL
 gravity=Gravity.CENTER_VERTICAL
 setPadding(dp(13),dp(12),dp(14),dp(12))
 background=rounded(tint,18,0xFFDCE5F2.toInt())
 elevation=dp(2).toFloat()
 isClickable=true;isFocusable=true
 contentDescription=label+". "+description
 val iconBox=FrameLayout(this@actionCard).apply{
  background=rounded(0xFFFFFFFF.toInt(),15,0xFFE4EAF4.toInt())
  addView(TextView(this@actionCard).apply{text=icon;textSize=25f;gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
 }
 addView(iconBox,LinearLayout.LayoutParams(dp(48),dp(48)))
 val words=LinearLayout(this@actionCard).apply{
  orientation=LinearLayout.VERTICAL
  gravity=Gravity.CENTER_VERTICAL
  setPadding(dp(12),0,dp(6),0)
  addView(TextView(this@actionCard).apply{
   text=label;textSize=15f;setTextColor(0xFF18345D.toInt())
   typeface=Typeface.create("sans-serif",Typeface.BOLD);maxLines=2
  })
  addView(TextView(this@actionCard).apply{
   text=description;textSize=11.5f;setTextColor(0xFF53647D.toInt())
   setPadding(0,dp(3),0,0);maxLines=2
  })
 }
 addView(words,LinearLayout.LayoutParams(0,-2,1f))
 addView(TextView(this@actionCard).apply{text="›";textSize=28f;setTextColor(0xFF7183A2.toInt());gravity=Gravity.CENTER})
 setOnClickListener{f()}
 val lp=LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);lp.bottomMargin=dp(6);layoutParams=lp
}

private fun Activity.button(s:String,green:Boolean=true,f:()->Unit)=Button(this).apply{
 text=s;textSize=15f;typeface=Typeface.create("sans-serif",Typeface.BOLD);isAllCaps=false;letterSpacing=.025f
 minHeight=dp(58);setPadding(dp(18),dp(13),dp(18),dp(13));setTextColor(if(green)-1 else 0xFF293A68.toInt())
 background=if(green)GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF496CC0.toInt(),0xFF263D79.toInt())).apply{cornerRadius=dp(18).toFloat()} else rounded(0xFFFFFFFF.toInt(),18,0xFFD9E0F0.toInt())
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
private object PatronPin {
 private const val PREFS="sene_patron_pin"
 private const val HASH_KEY="hash"
 private const val SALT_KEY="salt"
 private const val ITERATIONS=150000
 private const val DEFAULT_PIN="2910"
 private fun derive(pin:String,salt:ByteArray):ByteArray {
  val spec=PBEKeySpec(pin.toCharArray(),salt,ITERATIONS,256)
  return try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded}finally{spec.clearPassword()}
 }
 private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
 fun verify(activity:Activity,pin:String):Boolean {
  val prefs=activity.getSharedPreferences(PREFS,0)
  val saved=prefs.getString(HASH_KEY,null)
  if(saved.isNullOrBlank()){
   if(pin!=DEFAULT_PIN)return false
   val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
   return prefs.edit().putString(SALT_KEY,hex(salt)).putString(HASH_KEY,hex(derive(DEFAULT_PIN,salt))).commit()
  }
  val saltText=prefs.getString(SALT_KEY,null)?:return false
  val salt=try{saltText.chunked(2).map{it.toInt(16).toByte()}.toByteArray()}catch(_:Exception){return false}
  val expected=try{saved.chunked(2).map{it.toInt(16).toByte()}.toByteArray()}catch(_:Exception){return false}
  return java.security.MessageDigest.isEqual(expected,derive(pin,salt))
 }
}
private fun Activity.pin(ok:()->Unit){
 val e=input("PIN del patrón",true)
 e.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
 AlertDialog.Builder(this).setTitle("Soy Patrón").setView(e)
  .setPositiveButton("Entrar"){_,_->if(PatronPin.verify(this,e.text.toString()))ok()else msg("PIN incorrecto")}
  .setNegativeButton("Cancelar",null).show()
}
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

private object ShiftReportSendLock { var active = false }

class InicioTurnoActivity:Activity(){
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
   getSharedPreferences("sene_ventas_server",0).edit()
    .putString("worker_name",worker).putString("worker_phone",phone).apply()
   // Abrir el chat directo del patrón con el reporte completo ya preparado para enviar.
   pendingStartAfterWhatsApp=openWhatsAppText(this,body)
   if(!pendingStartAfterWhatsApp)msg("El turno quedó registrado y el respaldo TXT está guardado en el teléfono, en SENE VENTAS / INVENTARIO SENE. Revisa el número de WhatsApp del patrón.")
  }catch(e:Exception){AlertDialog.Builder(this).setTitle("No se pudo iniciar el turno").setMessage("No se pudo guardar el respaldo en INVENTARIO SENE. Verifica que la carpeta esté disponible y tenga permisos de escritura.\n\nDetalle: "+(e.message?:"Error desconocido.")).setPositiveButton("IR A SOY PATRÓN"){_,_->pin{startActivity(Intent(this,PatronActivity::class.java))}}.setNegativeButton("Cerrar",null).show()}
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==START_INVENTORY_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();createShiftReport(pendingWorker,pendingWorkerPhone,pendingCash,tree)}catch(e:Exception){msg("No se pudo guardar el acceso a Inventario SENE: "+(e.message?:"error"))}}}
 private fun draw(){
  val v=page("Panel principal"){showToolsMenu()}
  val welcome=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(18),dp(17),dp(18),dp(17))
   background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF17264A.toInt(),0xFF315A9B.toInt())).apply{cornerRadius=dp(22).toFloat()}
   elevation=dp(3).toFloat()
  }
  welcome.addView(TextView(this).apply{
   text="BIENVENIDO A SENE VENTAS";textSize=17f;letterSpacing=.04f
   setTextColor(0xFFFFFFFF.toInt());typeface=Typeface.create("sans-serif",Typeface.BOLD)
  })
  welcome.addView(TextView(this).apply{
   text="Tu jornada comienza aquí. Administra tu turno, consulta tus herramientas y mantén todo bajo control."
   textSize=13f;setTextColor(0xFFDCE7FF.toInt());setPadding(0,dp(7),0,0);setLineSpacing(dp(2).toFloat(),1f)
  })
  val openTools=TextView(this).apply{
   text="ABRIR HERRAMIENTAS  ›";textSize=12f;letterSpacing=.06f
   setTextColor(0xFFFFD58A.toInt());typeface=Typeface.DEFAULT_BOLD
   setPadding(0,dp(13),0,0);isClickable=true;isFocusable=true
   setOnClickListener{showToolsMenu()}
  }
  welcome.addView(openTools)
  v.addView(welcome,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(15)})

  val clockPanel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(11),dp(14),dp(11))
   background=rounded(0xFFEAF7F7.toInt(),18,0xFFD6EAE8.toInt())
  }
  clockPanel.addView(txt("HORA Y SINCRONIZACIÓN",12f).apply{
   typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF26736D.toInt())
  })
  clockPanel.addView(actionCard("Hora del celular","Fecha y hora local del dispositivo","🕒",0xFFFFFFFF.toInt()){consultInternetTime()})
  internetLabel=txt("Internet: "+internet,14f)
  clockPanel.addView(internetLabel)
  clockPanel.addView(actionCard("Verificar hora de Internet","Comprobar sincronización de la hora","🌐",0xFFFFFFFF.toInt()){consultInternetTime()})
  v.addView(clockPanel,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(15)})

  val shiftPanel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(13))
   background=rounded(0xFFFFFFFF.toInt(),20,0xFFE1E7F1.toInt());elevation=dp(2).toFloat()
  }
  shiftPanel.addView(TextView(this).apply{
   text="DATOS DEL TURNO";textSize=13f;letterSpacing=.05f
   setTextColor(0xFF49618B.toInt());typeface=Typeface.DEFAULT_BOLD
   setPadding(dp(2),0,0,dp(5))
  })
  val n=input("Nombre del trabajador")
  val p=input("Número de celular del trabajador",true)
  val cash=input("¿Con cuánto inicia caja chica? $",true)
  shiftPanel.addView(n);shiftPanel.addView(p);shiftPanel.addView(cash)
  shiftPanel.addView(powerButton("⏻  ENCENDER Y COMENZAR TURNO"){
   if(n.text.isBlank()||p.text.isBlank()||cash.text.isBlank()){msg("Completa los datos");return@powerButton}
   startShiftWithLatestInventory(n.text.toString().trim(),p.text.toString().filter{it.isDigit()},cash.text.toString())
  })
  v.addView(shiftPanel,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})

  v.addView(actionCard("Mi trabajo / Día de pago","Solicitudes, respuestas y nómina","📋",0xFFEAF2FF.toInt()){startActivity(Intent(this,WorkerPortalActivity::class.java))})
  v.addView(actionCard("Soy Patrón","Administrar inventario y configuración","👑",0xFFF2ECFF.toInt()){pin{refreshAfterPatron=true;startActivity(Intent(this,PatronActivity::class.java))}})
  val inventoryReady=Store.products(this).length()>0 || try{
   val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
   if(saved.isNullOrBlank())false else {InventoryReader.latest(this,Uri.parse(saved));true}
  }catch(_:Exception){false}
  if(!inventoryReady){
   val notice=LinearLayout(this).apply{
    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
    setPadding(dp(13),dp(11),dp(13),dp(11))
    background=rounded(0xFFFFF0E8.toInt(),15,0xFFF0D4C6.toInt())
   }
   notice.addView(TextView(this).apply{text="⚠️";textSize=21f},LinearLayout.LayoutParams(dp(34),-2))
   notice.addView(TextView(this).apply{
    text="Aún no hay un inventario válido en INVENTARIO SENE. Entra a Soy Patrón para crear o cargar el primero."
    textSize=12.5f;setTextColor(0xFF9B422B.toInt());setLineSpacing(dp(2).toFloat(),1f)
   },LinearLayout.LayoutParams(0,-2,1f))
   v.addView(notice,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8);bottomMargin=dp(8)})
  }
  val historyHeader=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
   setPadding(dp(2),dp(16),dp(2),dp(5))
  }
  historyHeader.addView(TextView(this).apply{
   text="MOVIMIENTOS RECIENTES";textSize=13f;letterSpacing=.05f
   setTextColor(0xFF49618B.toInt());typeface=Typeface.DEFAULT_BOLD
  },LinearLayout.LayoutParams(0,-2,1f))
  historyHeader.addView(TextView(this).apply{
   text="ÚLTIMOS 15";textSize=10f;setTextColor(0xFF8A94AA.toInt());typeface=Typeface.DEFAULT_BOLD
  })
  v.addView(historyHeader)
  val m=Store.moves(this)
  val historyPanel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;setPadding(dp(11),dp(9),dp(11),dp(9))
   background=rounded(0xFFFFFFFF.toInt(),18,0xFFE1E7F1.toInt())
  }
  if(m.length()==0){
   historyPanel.addView(txt("Todavía no hay movimientos registrados.",13f))
  }else{
   for(i in m.length()-1 downTo maxOf(0,m.length()-15)){
    val item=m.optJSONObject(i)?:continue
    val row=LinearLayout(this).apply{
     orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
     setPadding(dp(9),dp(9),dp(9),dp(9))
     background=rounded(if(i%2==0)0xFFF4F7FC.toInt() else 0xFFFFFFFF.toInt(),12)
    }
    row.addView(TextView(this).apply{text="•";textSize=20f;setTextColor(0xFF5277BC.toInt());gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(24),-2))
    row.addView(TextView(this).apply{
     text=item.optString("text","Movimiento");textSize=12.5f
     setTextColor(0xFF33415F.toInt());maxLines=3
    },LinearLayout.LayoutParams(0,-2,1f))
    historyPanel.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
   }
  }
  v.addView(historyPanel)
  display(v)
 }
}


class WorkerPortalActivity:Activity(){
 private var pendingPairingScan=false
 private fun prefs()=getSharedPreferences("sene_ventas_server",0)
 private fun config(after:()->Unit){
  // La dirección del servidor es configuración interna de SENE. No pedir al
  // trabajador que escriba una URL ni aceptar una dirección temporal de prueba.
  AlertDialog.Builder(this).setTitle("SERVIDOR SENE EN PREPARACIÓN")
   .setMessage("La conexión oficial todavía no está habilitada. No introduzcas direcciones manuales ni uses el servidor de prueba Hello World. La vinculación por QR y las funciones en línea estarán disponibles cuando SENE publique y configure el servidor oficial.")
   .setPositiveButton("ENTENDIDO",null).show()
 }
 private fun draw(){
  val root=page("Mi trabajo")
  root.addView(txt("Consulta tus solicitudes y el reporte de nómina cuando lo necesites. Las solicitudes se envían a SENE PATRÓN; no modifican ventas, caja ni inventario.",14f))
  root.addView(actionCard("Nueva solicitud","Préstamo, permiso, consumo y otros trámites","📝",0xFFEAF2FF.toInt()){requestsMenu()})
  root.addView(actionCard("Solicitar corrección de venta","Reportar un error y elegir los productos","🧾",0xFFFFF3E5.toInt()){chooseSaleForCorrection()})
  root.addView(actionCard("Corregir venta autorizada","Aplicar una corrección aprobada por el patrón","✏️",0xFFE8F7EF.toInt()){refreshCorrectionApprovals()})
  root.addView(actionCard("Mis solicitudes","Consultar historial y respuestas","📬",0xFFF2ECFF.toInt()){showHistory()})
  root.addView(actionCard("Día de pago / Nómina","Consultar historial y reporte de pago","📅",0xFFEAF7F7.toInt()){showPayroll()})
  root.addView(actionCard("Vincular con SENE Patrón","Escanear el QR oficial de vinculación","🔗",0xFFE8F7EF.toInt()){pairing()})
  root.addView(actionCard("Configurar conexión","Estado de conexión de SENE","⚙️",0xFFF1F3F8.toInt()){config{draw()}})
  root.addView(actionCard("Volver","Regresar a la pantalla anterior","↩️",0xFFF1F3F8.toInt()){finish()})
  display(root)
 }
 override fun onCreate(b:Bundle?){super.onCreate(b);draw();prefs().edit().putBoolean("work_queue_sending",false).apply();flushQueue();flushCorrectionQueue();resumePreparedCorrections();handleIncomingTicketCorrection(intent)}
 private fun handleIncomingTicketCorrection(source:Intent?){
  if(source?.getBooleanExtra("ticket_correction_request",false)!=true)return
  source.removeExtra("ticket_correction_request")
  val saleId=source.getStringExtra("ticket_sale_id").orEmpty()
  val summary=source.getStringExtra("ticket_sale_summary").orEmpty()
  val detail=source.getStringExtra("ticket_correction_detail").orEmpty()
  val selected=source.getStringArrayListExtra("ticket_selected_codes")?.toSet().orEmpty()
  startActivity(Intent(this,WorkerPortalActivity::class.java).apply{putExtra("ticket_correction_request",true);putExtra("ticket_sale_id",saleId);putExtra("ticket_sale_summary",summary);putExtra("ticket_correction_detail",detail);putStringArrayListExtra("ticket_selected_codes",ArrayList(selected))})
 }
 override fun onResume(){super.onResume();flushQueue();flushCorrectionQueue();resumePreparedCorrections()}
 private fun pairing(){
  if(prefs().getString("server_base_url",null).isNullOrBlank()){config{pairing()};return}
  AlertDialog.Builder(this).setTitle("Vincular SENE VENTAS")
   .setMessage("Genera el QR en SENE PATRÓN y escanéalo aquí.")
   .setPositiveButton("ESCANEAR QR"){_,_->pendingPairingScan=true;openScanner()}
   .setNegativeButton("Cerrar",null).show()
 }
 private fun requestsMenu(){
  val labels=arrayOf("Préstamo","Consumo","Permiso para faltar","Llegar tarde","Cambio de horario","Vacaciones","Reportar incidencia","Reportar faltante","Cambio de descanso","Adelanto de sueldo","Equipo dañado","Sugerencia")
  val types=arrayOf("LOAN","CONSUMPTION","ABSENCE_PERMISSION","LATE_ARRIVAL","SCHEDULE_CHANGE","VACATION","INCIDENT","SHORTAGE","REST_DAY_CHANGE","SALARY_ADVANCE","DAMAGED_EQUIPMENT","SUGGESTION")
  AlertDialog.Builder(this).setTitle("NUEVA SOLICITUD").setItems(labels){_,which->requestForm(types[which],labels[which])}.setNegativeButton("Cerrar",null).show()
 }
 private fun requestForm(type:String,label:String){
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8))}
  val description=input("Describe tu solicitud");description.minLines=3;description.maxLines=5;box.addView(description)
  val amount=if(type in setOf("LOAN","CONSUMPTION","SALARY_ADVANCE"))input("Cantidad solicitada en pesos $",true)else null
  amount?.let{box.addView(it)}
  AlertDialog.Builder(this).setTitle(label).setMessage("Se enviará a SENE PATRÓN. No cambia automáticamente caja ni inventario.")
   .setView(box).setPositiveButton("ENVIAR"){_,_->
    val text=description.text.toString().trim()
    if(text.isBlank()){msg("Describe tu solicitud");return@setPositiveButton}
    val details=JSONObject().put("description",text).put("requestedAt",System.currentTimeMillis())
    if(amount!=null){
     val value=amount.text.toString().trim().replace(",",".").toDoubleOrNull()
     if(value==null||value<=0||value>1_000_000){msg("Cantidad no válida");return@setPositiveButton}
     details.put("amountCents",Math.round(value*100.0))
    }
    val queue=prefs().getString("work_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    queue.put(JSONObject().put("key",UUID.randomUUID().toString()).put("type",type).put("label",label).put("details",details))
    prefs().edit().putString("work_queue",queue.toString()).apply()
    flushQueue(showFeedback = true)
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun correctionStatusLabel(status:String):String=when(status.lowercase(Locale.ROOT)){
  "pending"->"Pendiente"
  "approved"->"Autorizada"
  "rejected"->"Rechazada"
  "consumed"->"Utilizada"
  else->status.ifBlank{"Sin estado"}
 }
 private fun chooseSaleForCorrection(){
  val sales=Store.sales(this)
  val grouped=linkedMapOf<String,MutableList<JSONObject>>()
  for(i in 0 until sales.length()){
   val item=sales.optJSONObject(i)?:continue
   val ts=item.optLong("ts",0L);if(ts<=0L)continue
   val id=item.optString("saleId").takeIf{it.isNotBlank()}?:("legacy-"+ts)
   grouped.getOrPut(id){mutableListOf()}.add(item)
  }
  if(grouped.isEmpty()){msg("Todavía no hay ventas registradas para solicitar una corrección.");return}
  val ids=grouped.keys.toList().asReversed()
  val labels=ids.map{id->
   val rows=grouped[id].orEmpty();var total=0.0
   for(row in rows)total+=row.optDouble("subtotal",0.0)
   rows.firstOrNull()?.optString("time").orEmpty()+" · "+rows.size+" renglón(es) · "+Store.money(total)
  }
  AlertDialog.Builder(this).setTitle("ELIGE LA VENTA")
   .setItems(labels.toTypedArray()){_,which->correctionReason(ids[which],labels[which])}
   .setNegativeButton("Cancelar",null).show()
 }
 private fun correctionReason(saleId:String,summary:String){
  val categories=arrayOf("Devolución","Ajuste de precio","Eliminación de producto","Corrección de cantidad","Producto equivocado","Producto omitido","Otro")
  AlertDialog.Builder(this).setTitle("MOTIVO DE LA CORRECCIÓN")
   .setMessage("La solicitud será una sola por venta. Primero reúne todos los cambios necesarios; después el patrón aprobará el plan completo. Los productos, códigos, cantidades y precios nunca se envían al servidor.")
   .setItems(categories){_,which->
    val category=categories[which]
    val detail=input("Describe brevemente qué debe corregirse")
    detail.minLines=2;detail.maxLines=4
    detail.filters=arrayOf(android.text.InputFilter.LengthFilter(500))
    AlertDialog.Builder(this).setTitle(category).setView(detail)
     .setPositiveButton("CONTINUAR"){_,_->
      val localDetail=detail.text.toString().trim()
      if(localDetail.isBlank()){msg("Describe brevemente la corrección");return@setPositiveButton}
      startCorrectionPlan(saleId,summary,category,localDetail)
     }.setNegativeButton("Cancelar",null).show()
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun correctionSaleRows(saleId:String):MutableList<JSONObject>{
  val result=mutableListOf<JSONObject>();val sales=Store.sales(this)
  for(i in 0 until sales.length()){
   val row=sales.optJSONObject(i)?:continue
   if(row.optString("saleId")==saleId)result.add(JSONObject(row.toString()))
  }
  return result
 }
 private fun startCorrectionPlan(saleId:String,summary:String,reason:String,detail:String){
  if(saleId.isBlank()||saleId.startsWith("legacy-")){msg("La venta no tiene un folio válido para solicitar autorización.");return}
  val rows=correctionSaleRows(saleId)
  if(rows.isEmpty()){msg("La venta ya no está disponible en el historial local.");return}
  if(rows.any{it.has("correctionOpId")}){msg("Esta venta ya fue corregida.");return}
  val plan=JSONArray()
  addCorrectionPlanChange(saleId,summary,reason,detail,rows,plan)
 }
 private fun startCorrectionPlanForSelectedRows(saleId:String,summary:String,detail:String,selectedCodes:Set<String>){
  val rows=correctionSaleRows(saleId)
  if(rows.isEmpty()){msg("La venta original ya no está disponible.");return}
  if(rows.any{it.has("correctionOpId")}){msg("Esta venta ya fue corregida.");return}
  if(selectedCodes.isEmpty()||rows.none{it.optString("code") in selectedCodes}){msg("No hay productos válidos seleccionados.");return}
  val plan=JSONArray()
  addCorrectionPlanChange(saleId,summary,"Solicitud desde ticket escaneado",detail,rows,plan,selectedCodes)
 }
 private fun addCorrectionPlanChange(saleId:String,summary:String,primaryReason:String,detail:String,rows:List<JSONObject>,plan:JSONArray,selectedCodes:Set<String>?=null){
  val used=mutableSetOf<String>()
  for(i in 0 until plan.length())used.add(plan.optJSONObject(i)?.optString("fromCode").orEmpty())
  val options=mutableListOf<String>();val rowIndexes=mutableListOf<Int>()
  rows.forEachIndexed{index,row->
   val code=row.optString("code")
   if(code !in used&&(selectedCodes==null||code in selectedCodes)){rowIndexes.add(index);options.add(row.optString("name","Producto")+" · "+row.optInt("qty")+" pza. · "+Store.money(row.optDouble("subtotal")))}
  }
  if(selectedCodes==null)options.add("AGREGAR PRODUCTO OMITIDO")
  if(options.isEmpty()){reviewCorrectionPlan(saleId,summary,primaryReason,detail,rows,plan,selectedCodes);return}
  AlertDialog.Builder(this).setTitle("AGREGAR CAMBIO A LA SOLICITUD")
   .setMessage("Puedes reunir varios cambios antes de enviar una sola solicitud al patrón.")
   .setItems(options.toTypedArray()){_,which->
    if(which==rowIndexes.size)choosePlanTarget(saleId,summary,primaryReason,detail,rows,plan,null,selectedCodes)
    else{
     val source=rows[rowIndexes[which]]
     AlertDialog.Builder(this).setTitle(source.optString("name"))
      .setItems(arrayOf("Cambiar producto o cantidad","Eliminar este producto")){_,action->
       if(action==0)choosePlanTarget(saleId,summary,primaryReason,detail,rows,plan,source,selectedCodes)
       else recordPlanChange(saleId,summary,primaryReason,detail,rows,plan,source,"",0,0L,"",selectedCodes)
      }.setNegativeButton("Cancelar",null).show()
    }
   }.setNegativeButton("Terminar después",null).show()
 }
 private fun choosePlanTarget(saleId:String,summary:String,primaryReason:String,detail:String,rows:List<JSONObject>,plan:JSONArray,source:JSONObject?,selectedCodes:Set<String>?=null){
  val products=Store.products(this);val candidates=mutableListOf<JSONObject>()
  for(i in 0 until products.length()){
   val p=products.optJSONObject(i)?:continue
   val code=p.optString("code").trim()
   if(code.isBlank())continue
   if(source!=null&&code==source.optString("code")){candidates.add(JSONObject(p.toString()));continue}
   if(p.optInt("stock",-1)>0)candidates.add(JSONObject(p.toString()))
  }
  if(candidates.isEmpty()){msg("No hay productos disponibles en el catálogo.");return}
  val labels=candidates.map{it.optString("name")+" · "+it.optString("code")+" · "+Store.money(it.optDouble("price"))+" · existencia "+it.optInt("stock")}
  AlertDialog.Builder(this).setTitle(if(source==null)"PRODUCTO OMITIDO" else "PRODUCTO CORRECTO")
   .setItems(labels.toTypedArray()){_,which->
    val target=candidates[which];val qty=input("Cantidad nueva",true)
    qty.inputType=android.text.InputType.TYPE_CLASS_NUMBER
    qty.setText(if(source!=null)source.optInt("qty").toString() else "1")
    AlertDialog.Builder(this).setTitle("CANTIDAD")
     .setMessage("Precio actual del catálogo: "+Store.money(target.optDouble("price")))
     .setView(qty).setPositiveButton("AGREGAR CAMBIO"){_,_->
      val count=qty.text.toString().trim().toIntOrNull()
      if(count==null||count !in 1..100000){msg("La cantidad debe ser un entero mayor que cero.");return@setPositiveButton}
      val cents=kotlin.math.round(target.optDouble("price")*100.0).toLong()
      if(cents<0L){msg("El precio del producto no es válido.");return@setPositiveButton}
      recordPlanChange(saleId,summary,primaryReason,detail,rows,plan,source,target.optString("code"),count,cents,target.optString("name"),selectedCodes)
     }.setNegativeButton("Cancelar",null).show()
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun recordPlanChange(saleId:String,summary:String,primaryReason:String,detail:String,rows:List<JSONObject>,plan:JSONArray,source:JSONObject?,targetCode:String,newQty:Int,targetPriceCents:Long=0L,targetName:String="",selectedCodes:Set<String>?=null){
  val categories=arrayOf("Devolución","Ajuste de precio","Eliminación de producto","Corrección de cantidad","Producto equivocado","Producto omitido","Otro")
  AlertDialog.Builder(this).setTitle("MOTIVO DE ESTE CAMBIO")
   .setItems(categories){_,which->
    val from=source?.optString("code").orEmpty()
    val change=JSONObject().put("fromCode",from).put("toCode",targetCode).put("newQty",newQty)
     .put("targetPriceCents",targetPriceCents).put("reason",categories[which])
     .put("fromName",source?.optString("name").orEmpty()).put("toName",targetName)
     .put("oldQty",source?.optInt("qty",0)?:0)
     .put("oldSubtotalCents",kotlin.math.round((source?.optDouble("subtotal",0.0)?:0.0)*100.0).toLong())
    plan.put(change)
    AlertDialog.Builder(this).setTitle("CAMBIO AGREGADO")
     .setMessage("Se guardó localmente. ¿Deseas agregar otro cambio a la misma solicitud?")
     .setPositiveButton("AGREGAR OTRO"){_,_->addCorrectionPlanChange(saleId,summary,primaryReason,detail,rows,plan,selectedCodes)}
     .setNegativeButton("REVISAR SOLICITUD"){_,_->reviewCorrectionPlan(saleId,summary,primaryReason,detail,rows,plan)}
     .show()
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun correctionPlanHash(plan:JSONArray):String{
  val canonical=JSONArray()
  for(i in 0 until plan.length()){
   val x=plan.optJSONObject(i)?:continue
   // Vincular todos los campos del cambio, incluidos los valores originales
   // y los nombres locales, para que ninguna parte del plan pueda alterarse
   // después de que el patrón haya autorizado su huella.
   canonical.put(JSONObject().put("fromCode",x.optString("fromCode"))
    .put("toCode",x.optString("toCode")).put("newQty",x.optInt("newQty"))
    .put("targetPriceCents",x.optLong("targetPriceCents"))
    .put("oldQty",x.optInt("oldQty"))
    .put("oldSubtotalCents",x.optLong("oldSubtotalCents"))
    .put("fromName",x.optString("fromName"))
    .put("toName",x.optString("toName"))
    .put("reason",x.optString("reason")))
  }
  return MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
   .joinToString(""){"%02x".format(it.toInt() and 0xff)}
 }
 private fun reviewCorrectionPlan(saleId:String,summary:String,primaryReason:String,detail:String,rows:List<JSONObject>,plan:JSONArray,selectedCodes:Set<String>?=null){
  if(plan.length()==0){msg("Agrega al menos un cambio antes de enviar.");return}
  val catalog=Store.products(this);val productByCode=mutableMapOf<String,JSONObject>()
  for(i in 0 until catalog.length()){val p=catalog.optJSONObject(i)?:continue;val code=p.optString("code");if(code.isNotBlank()){if(productByCode.containsKey(code)){msg("El catálogo tiene códigos duplicados. Corrige el catálogo antes de continuar.");return};productByCode[code]=p}}
  val sourceCodes=mutableSetOf<String>();val finalCodes=rows.map{it.optString("code")}.toMutableList()
  val stockDelta=mutableMapOf<String,Int>();var oldTotal=0L;var newTotal=0L
  for(row in rows){val cents=kotlin.math.round(row.optDouble("subtotal")*100.0).toLong();if(cents<0L){msg("El total original no es válido.");return};oldTotal+=cents;newTotal+=cents}
  for(i in 0 until plan.length()){
   val x=plan.optJSONObject(i)?:continue
   val from=x.optString("fromCode");val to=x.optString("toCode");val qty=x.optInt("newQty")
   if(from.isNotBlank()){
    if(!sourceCodes.add(from)){msg("No puedes modificar dos veces el mismo renglón.");return}
    val row=rows.firstOrNull{it.optString("code")==from}?:run{msg("Un renglón de la venta ya no existe.");return}
    val oldCents=kotlin.math.round(row.optDouble("subtotal")*100.0).toLong()
    newTotal-=oldCents;stockDelta[from]=(stockDelta[from]?:0)+row.optInt("qty")
    finalCodes.remove(from)
   }
   if(to.isNotBlank()){
    val p=productByCode[to]?:run{msg("Un producto del plan ya no existe en el catálogo.");return}
    val currentCents=kotlin.math.round(p.optDouble("price")*100.0).toLong()
    if(currentCents!=x.optLong("targetPriceCents")){msg("Cambió el precio del catálogo de "+p.optString("name")+". Crea una solicitud nueva con el precio vigente.");return}
    if(qty !in 1..100000){msg("Una cantidad del plan no es válida.");return}
    newTotal+=currentCents*qty;stockDelta[to]=(stockDelta[to]?:0)-qty;finalCodes.add(to)
   }
  }
  if(finalCodes.size!=finalCodes.toSet().size){msg("El plan dejaría productos duplicados en el ticket. Ajusta los cambios antes de enviar.");return}
  for((code,delta) in stockDelta){
   val p=productByCode[code]?:run{msg("No se encontró existencia para un producto del plan.");return}
   if(p.optInt("stock",-1)+delta<0){msg("La existencia no alcanza para aplicar todos los cambios juntos. No se envió la solicitud.");return}
  }
  if(newTotal<0L){msg("El total corregido no puede ser negativo.");return}
  val delta=newTotal-oldTotal
  val changesText=(0 until plan.length()).joinToString("\n"){i->val x=plan.optJSONObject(i)!!;"• "+(x.optString("fromName").ifBlank{"Producto omitido"})+" → "+(x.optString("toName").ifBlank{"Eliminado"})+" · cantidad "+x.optInt("newQty")+" · "+x.optString("reason")}
  val impact=if(delta>0)"\nSe cobrará una diferencia de "+Store.money(delta/100.0) else if(delta<0)"\nSe devolverá "+Store.money(kotlin.math.abs(delta)/100.0) else "\nNo cambia el total de la venta."
  AlertDialog.Builder(this).setTitle("REVISAR PLAN COMPLETO")
   .setMessage("Folio: "+saleId+"\nCambios: "+plan.length()+"\nTotal original: "+Store.money(oldTotal/100.0)+"\nTotal corregido: "+Store.money(newTotal/100.0)+impact+"\n\n"+changesText+"\n\nSENE PATRÓN aprobará o rechazará este plan completo. Los detalles comerciales permanecerán en este teléfono.")
   .setPositiveButton("CONTINUAR"){_,_->
    if(delta==0L)enqueueCorrectionPlan(saleId,summary,primaryReason,detail,plan,oldTotal,newTotal,"")
    else{
     val cashReasons=arrayOf("Devolución al cliente","Diferencia por precio vigente","Cantidad corregida","Producto eliminado o incorrecto","Producto omitido","Otro")
     AlertDialog.Builder(this).setTitle("MOTIVO DEL CAMBIO EN CAJA")
      .setMessage("Como el total cambia, selecciona por qué habrá un cobro o devolución. Este motivo también lo verá el patrón.")
      .setItems(cashReasons){_,which->enqueueCorrectionPlan(saleId,summary,primaryReason,detail,plan,oldTotal,newTotal,cashReasons[which])}
      .setNegativeButton("Cancelar",null).show()
    }
   }.setNegativeButton("Seguir editando"){_,_->addCorrectionPlanChange(saleId,summary,primaryReason,detail,rows,plan,selectedCodes)}
   .show()
 }
 private fun enqueueCorrectionPlan(saleId:String,summary:String,primaryReason:String,detail:String,plan:JSONArray,oldTotalCents:Long,newTotalCents:Long,cashReason:String){
  val reasons=JSONArray();val seen=mutableSetOf<String>()
  if(primaryReason.isNotBlank()){seen.add(primaryReason);reasons.put(primaryReason)}
  for(i in 0 until plan.length()){val reason=plan.optJSONObject(i)?.optString("reason").orEmpty();if(reason.isNotBlank()&&seen.add(reason))reasons.put(reason)}
  val hash=correctionPlanHash(plan)
  val item=JSONObject().put("saleId",saleId).put("reason",primaryReason).put("detail",detail)
   .put("summary",summary).put("key",UUID.randomUUID().toString()).put("plan",plan)
   .put("planHash",hash).put("reasons",reasons).put("cashImpact",oldTotalCents!=newTotalCents)
   .put("cashReason",cashReason).put("oldTotalCents",oldTotalCents).put("newTotalCents",newTotalCents)
  synchronized(prefs()){
   val queue=prefs().getString("correction_queue","[]")?.let{JSONArray(it)}?:JSONArray()
   queue.put(item);prefs().edit().putString("correction_queue",queue.toString()).commit()
  }
  flushCorrectionQueue(true)
 }
 private fun refreshCorrectionApprovals(){
  val base=prefs().getString("server_base_url",null)
  if(base.isNullOrBlank()){config{refreshCorrectionApprovals()};return}
  if(prefs().getString("device_id",null).isNullOrBlank()){pairing();return}
  Thread{
   try{
    val since=prefs().getLong("work_updates_since",0L)
    val updates=SeneApiClient(this,base).requestUpdates(since)
    val corrections=updates.optJSONArray("corrections")?:JSONArray()
    var newest=since
    synchronized(prefs()){
     val history=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
     for(i in 0 until corrections.length()){
      val item=corrections.optJSONObject(i)?:continue
      val id=item.optString("requestId")
      if(id.isBlank())continue
      var found=false
      for(j in 0 until history.length()){
       val old=history.optJSONObject(j)?:continue
       if(old.optString("requestId")==id){
        old.put("status",item.optString("status"))
        if(!item.isNull("grantId"))old.put("grantId",item.optString("grantId"))
        old.put("serverPlanHash",item.optString("planHash"))
        old.put("serverCashImpact",item.optBoolean("cashImpact"))
        old.put("serverCashReason",item.optString("cashReason"))
        old.put("decidedAt",item.optLong("decidedAt",0L))
        found=true
       }
      }
      if(!found)history.put(JSONObject().put("requestId",id).put("saleId",item.optString("saleId"))
       .put("reason",item.optString("reason")).put("status",item.optString("status"))
       .put("grantId",item.optString("grantId")).put("planHash",item.optString("planHash"))
       .put("serverPlanHash",item.optString("planHash")).put("cashImpact",item.optBoolean("cashImpact"))
       .put("serverCashImpact",item.optBoolean("cashImpact")).put("cashReason",item.optString("cashReason"))
       .put("serverCashReason",item.optString("cashReason")).put("reasons",item.optJSONArray("reasons")?:JSONArray())
       .put("createdAt",item.optLong("createdAt",0L)).put("decidedAt",item.optLong("decidedAt",0L)))
      newest=maxOf(newest,item.optLong("decidedAt",0L))
     }
     prefs().edit().putString("correction_history",history.toString())
      .putLong("work_updates_since",maxOf(0L,newest-1L)).commit()
    }
    runOnUiThread{showApprovedCorrectionPicker()}
   }catch(e:Exception){runOnUiThread{msg("No se pudieron consultar las autorizaciones. Revisa la conexión e inténtalo de nuevo.")}}
  }.start()
 }
 private fun showApprovedCorrectionPicker(){
  val history=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
  val approved=mutableListOf<JSONObject>()
  for(i in 0 until history.length()){
   val item=history.optJSONObject(i)?:continue
   if(item.optString("status")=="approved"&&item.optString("grantId").isNotBlank()&&item.optString("requestId").isNotBlank()&&item.optJSONArray("plan")?.length()?.let{it>0}==true&&item.optString("planHash").isNotBlank())approved.add(JSONObject(item.toString()))
  }
  if(approved.isEmpty()){msg("No hay correcciones autorizadas pendientes de realizar.");return}
  val labels=approved.map{it.optString("reason","Corrección")+" · "+it.optString("saleId")}
  AlertDialog.Builder(this).setTitle("CORRECCIONES AUTORIZADAS")
   .setItems(labels.toTypedArray()){_,which->beginApprovedCorrection(approved[which])}
   .setNegativeButton("Cerrar",null).show()
 }
 private fun beginApprovedCorrection(request:JSONObject){
  val saleId=request.optString("saleId")
  val plan=request.optJSONArray("plan")?:JSONArray()
  val expectedHash=request.optString("planHash")
  if(saleId.isBlank()||saleId.startsWith("legacy-")||plan.length()==0||expectedHash.isBlank()){
   msg("La autorización no contiene el plan local completo. No se puede ejecutar con seguridad.");return
  }
  if(correctionPlanHash(plan)!=expectedHash||request.optString("serverPlanHash",expectedHash)!=expectedHash||
   request.optBoolean("serverCashImpact",request.optBoolean("cashImpact"))!=request.optBoolean("cashImpact")||
   request.optString("serverCashReason",request.optString("cashReason"))!=request.optString("cashReason")){
   msg("El plan o el motivo de caja local no coincide con lo registrado por SENE PATRÓN. No se consumió la autorización.");return
  }
  val rows=correctionSaleRows(saleId)
  if(rows.isEmpty()){msg("La venta autorizada no se encuentra en el historial local.");return}
  if(rows.any{it.has("correctionOpId")}){msg("Esta venta ya tiene una corrección aplicada.");return}
  val pending=Store.pendingCorrectionOperations(this)
  for(i in 0 until pending.length()){
   val op=pending.optJSONObject(i)?:continue
   if(op.optString("saleId")==saleId){msg("Esta venta ya tiene una corrección en proceso. Revisa su estado.");return}
  }
  var oldTotal=0L;var newTotal=0L
  for(row in rows){val cents=kotlin.math.round(row.optDouble("subtotal")*100.0).toLong();if(cents<0L){msg("El total original no es válido.");return};oldTotal+=cents;newTotal+=cents}
  val catalog=Store.products(this);val byCode=mutableMapOf<String,JSONObject>()
  for(i in 0 until catalog.length()){val p=catalog.optJSONObject(i)?:continue;val code=p.optString("code");if(code.isNotBlank())byCode[code]=p}
  val changedSources=mutableSetOf<String>()
  for(i in 0 until plan.length()){
   val change=plan.optJSONObject(i)?:run{msg("El plan contiene un cambio inválido.");return}
   val from=change.optString("fromCode");val to=change.optString("toCode");val qty=change.optInt("newQty")
   if(from.isNotBlank()){
    if(!changedSources.add(from)){msg("El plan repite un renglón de origen.");return}
    val source=rows.firstOrNull{it.optString("code")==from}?:run{msg("El renglón original ya no coincide con el plan.");return}
    val oldCents=kotlin.math.round(source.optDouble("subtotal")*100.0).toLong()
    if(oldCents!=change.optLong("oldSubtotalCents")||source.optInt("qty")!=change.optInt("oldQty")){
     msg("La venta cambió desde que se solicitó la autorización. Se necesita una solicitud nueva.");return
    }
    newTotal-=oldCents
   }
   if(to.isNotBlank()){
    val target=byCode[to]?:run{msg("Un producto autorizado ya no existe en el catálogo.");return}
    val currentPrice=kotlin.math.round(target.optDouble("price")*100.0).toLong()
    if(currentPrice!=change.optLong("targetPriceCents")){msg("Cambió el precio del catálogo desde la aprobación. Solicita una nueva autorización.");return}
    if(qty !in 1..100000){msg("Una cantidad del plan no es válida.");return}
    newTotal+=currentPrice*qty
   }
  }
  val delta=newTotal-oldTotal
  if(request.optBoolean("cashImpact")!=(delta!=0L)|| (delta!=0L&&request.optString("cashReason").isBlank())){
   msg("El impacto de caja ya no coincide con el plan aprobado. No se consumió la autorización.");return
  }
  val changesText=(0 until plan.length()).joinToString("\n"){i->val x=plan.optJSONObject(i)!!;"• "+x.optString("fromName").ifBlank{"Producto omitido"}+" → "+x.optString("toName").ifBlank{"Eliminado"}+" · "+x.optInt("newQty")+" pza."}
  val cashText=if(delta>0)"\nCobrar diferencia: "+Store.money(delta/100.0) else if(delta<0)"\nDevolver: "+Store.money(kotlin.math.abs(delta)/100.0) else "\nSin cambio de caja."
  AlertDialog.Builder(this).setTitle("EJECUTAR CORRECCIÓN AUTORIZADA")
   .setMessage("SENE PATRÓN aprobó este plan completo.\nFolio: "+saleId+"\nCambios: "+plan.length()+"\nTotal anterior: "+Store.money(oldTotal/100.0)+"\nTotal corregido: "+Store.money(newTotal/100.0)+cashText+"\nMotivo de caja: "+request.optString("cashReason","No aplica")+"\n\n"+changesText+"\n\nNo agregues ni cambies renglones después de la aprobación.")
   .setPositiveButton(if(delta==0L)"APLICAR PLAN" else "REGISTRAR COBRO / DEVOLUCIÓN"){_,_->
    if(delta==0L)persistAndConsumeCorrectionPlan(request,plan,null)
    else collectCashEvidenceForPlan(request,plan,delta)
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun collectCashEvidenceForPlan(request:JSONObject,plan:JSONArray,deltaCents:Long){
  val labels=arrayOf("Efectivo","Tarjeta","Transferencia","Otro")
  val methods=arrayOf("cash","card","transfer","other")
  AlertDialog.Builder(this).setTitle(if(deltaCents>0)"CONFIRMAR COBRO DE DIFERENCIA" else "CONFIRMAR DEVOLUCIÓN")
   .setMessage("Importe neto exacto: "+Store.money(kotlin.math.abs(deltaCents)/100.0)+"\nMotivo aprobado: "+request.optString("cashReason")+"\n\nContinúa únicamente después de recibir o entregar realmente el dinero.")
   .setItems(labels){_,which->
    val reference=input("Referencia o nota del movimiento")
    reference.setText(if(methods[which]=="cash")"Efectivo confirmado" else "")
    AlertDialog.Builder(this).setTitle("EVIDENCIA LOCAL DE CAJA").setView(reference)
     .setMessage("La referencia y el importe exacto se guardarán solo en este teléfono.")
     .setPositiveButton("CONFIRMAR MOVIMIENTO REAL"){_,_->
      val note=reference.text.toString().trim()
      if(note.length !in 3..120){msg("La referencia debe tener entre 3 y 120 caracteres.");return@setPositiveButton}
      val evidence=JSONObject().put("method",methods[which]).put("reference",note)
       .put("amountCents",kotlin.math.abs(deltaCents)).put("confirmedAt",System.currentTimeMillis())
      persistAndConsumeCorrectionPlan(request,plan,evidence)
     }.setNegativeButton("Cancelar",null).show()
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun persistAndConsumeCorrectionPlan(request:JSONObject,plan:JSONArray,evidence:JSONObject?){
  val base=prefs().getString("server_base_url",null);val device=prefs().getString("device_id",null)
  if(base.isNullOrBlank()||device.isNullOrBlank()){msg("No se puede ejecutar sin conexión segura con SENE PATRÓN.");return}
  val requestId=request.optString("requestId");val grantId=request.optString("grantId");val saleId=request.optString("saleId")
  val planHash=request.optString("planHash")
  if(requestId.isBlank()||grantId.isBlank()||saleId.isBlank()||planHash.isBlank()||correctionPlanHash(plan)!=planHash){
   msg("Falta la autorización exacta del plan. No se consumió el permiso.");return
  }
  val changes=JSONArray()
  for(i in 0 until plan.length()){
   val x=plan.optJSONObject(i)?:continue
   changes.put(JSONObject().put("fromCode",x.optString("fromCode"))
    .put("toCode",x.optString("toCode")).put("newQty",x.optInt("newQty")))
  }
  val operation=JSONObject().put("opId",UUID.randomUUID().toString())
   .put("requestId",requestId).put("grantId",grantId).put("saleId",saleId).put("planHash",planHash)
   .put("reason",request.optString("reason","Otro")).put("detail",request.optString("detail").take(1000))
   .put("cashReason",request.optString("cashReason")).put("state","prepared")
   .put("cashConfirmed",evidence!=null).put("changes",changes).put("preparedAt",System.currentTimeMillis())
  if(evidence!=null)operation.put("cashEvidence",evidence)
  if(!Store.saveCorrectionOperation(this,operation)){msg("No se pudo guardar la operación local. No se consumió la autorización.");return}
  Thread{
   try{
    val result=SeneApiClient(this,base).consumeCorrectionGrant(requestId,grantId,saleId,planHash,device)
    if(!result.optBoolean("authorized",false)||result.optString("requestId")!=requestId||
     result.optString("grantId")!=grantId||result.optString("saleId")!=saleId||result.optString("planHash")!=planHash)
     throw IllegalStateException("El servidor no confirmó el permiso para este plan exacto.")
    val consumed=JSONObject(operation.toString()).put("state","grant_consumed")
     .put("consumedAt",result.optLong("consumedAt",System.currentTimeMillis()/1000)*1000L)
    if(!Store.updateCorrectionOperation(this,operation.optString("opId"),"grant_consumed",consumed))
     throw IllegalStateException("El permiso se consumió, pero el registro local se recuperará al reintentar.")
    if(!Store.applySaleCorrection(this,consumed))
     throw IllegalStateException("La autorización se consumió, pero la transacción local no pasó las validaciones. No se alteró la venta.")
    synchronized(prefs()){
     val history=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
     for(i in 0 until history.length()){val item=history.optJSONObject(i)?:continue;if(item.optString("requestId")==requestId)item.put("status","consumed")}
     prefs().edit().putString("correction_history",history.toString()).commit()
    }
    runOnUiThread{msg("Plan completo aplicado. Venta, inventario y auditoría local actualizados con un solo ajuste neto de caja.")}
   }catch(e:Exception){runOnUiThread{msg("No se pudo completar: "+(e.message?:"error de conexión")+". La operación quedó guardada para recuperación segura.")}}
  }.start()
 }
 private fun resumePreparedCorrections(){
  val base=prefs().getString("server_base_url",null)?:return
  val device=prefs().getString("device_id",null)?:return
  val pending=Store.pendingCorrectionOperations(this)
  if(pending.length()==0)return
  Thread{
   for(i in 0 until pending.length()){
    val operation=pending.optJSONObject(i)?:continue
    val state=operation.optString("state")
    if(state !in setOf("prepared","grant_consumed"))continue
    val requestId=operation.optString("requestId");val grantId=operation.optString("grantId");val saleId=operation.optString("saleId")
    if(requestId.isBlank()||grantId.isBlank()||saleId.isBlank())continue
    try{
     var ready=JSONObject(operation.toString())
     if(state=="prepared"){
      val result=SeneApiClient(this,base).consumeCorrectionGrant(requestId,grantId,saleId,operation.optString("planHash"),device)
      if(!result.optBoolean("authorized",false)||result.optString("grantId")!=grantId||result.optString("saleId")!=saleId||result.optString("planHash")!=operation.optString("planHash"))continue
      ready.put("state","grant_consumed").put("consumedAt",result.optLong("consumedAt",System.currentTimeMillis()/1000)*1000L)
      if(!Store.updateCorrectionOperation(this,operation.optString("opId"),"grant_consumed",ready))continue
     }
     if(Store.applySaleCorrection(this,ready)){
      synchronized(prefs()){
       val history=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
       for(j in 0 until history.length()){
        val item=history.optJSONObject(j)?:continue
        if(item.optString("requestId")==requestId)item.put("status","consumed")
       }
       prefs().edit().putString("correction_history",history.toString()).commit()
      }
     }
    }catch(_:Exception){}
   }
  }.start()
 }
 private fun flushCorrectionQueue(showFeedback:Boolean=false){
  val base=prefs().getString("server_base_url",null)
  if(base.isNullOrBlank()||prefs().getString("device_id",null).isNullOrBlank()){
   if(showFeedback)msg("La solicitud quedó guardada. Configura y vincula SENE VENTAS para enviarla.")
   return
  }
  // Reclamo atómico: evita que onResume y varios callbacks lancen remitentes simultáneos.
  // Un lease vencido permite recuperarse si Android mata el proceso mientras enviaba.
  val senderToken=UUID.randomUUID().toString()
  val claimed=synchronized(prefs()){
   val now=System.currentTimeMillis()
   val active=prefs().getBoolean("correction_queue_sending",false)
   val started=prefs().getLong("correction_queue_sending_at",0L)
   if(active && started>0L && now-started<5*60*1000L)false
   else{
    val pending=prefs().getString("correction_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    if(pending.length()==0)false
    else prefs().edit().putBoolean("correction_queue_sending",true)
     .putLong("correction_queue_sending_at",now)
     .putString("correction_queue_sender_token",senderToken).commit()
   }
  }
  if(!claimed)return
  Thread{
   var sent=0
   try{
    val api=SeneApiClient(this,base)
    var current=prefs().getString("correction_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    while(current.length()>0){
     // Renueva el lease antes de cada petición y abandona si otro remitente lo recuperó.
     val ownsLease=synchronized(prefs()){
      if(prefs().getString("correction_queue_sender_token",null)==senderToken){
       prefs().edit().putLong("correction_queue_sending_at",System.currentTimeMillis()).commit()
       true
      }else false
     }
     if(!ownsLease)break
     val item=current.optJSONObject(0)?:break
     // El texto libre puede contener productos, códigos, cantidades o importes.
     // El detalle queda local; al servidor solo se envía un motivo genérico.
     // Compatibilidad segura con solicitudes antiguas en cola: nunca enviar al servidor
     // texto libre que pudiera contener productos, códigos, cantidades o importes.
     val allowedReasons=setOf("Devolución","Ajuste de precio","Eliminación de producto",
      "Corrección de cantidad","Producto equivocado","Producto omitido","Otro")
     val safeReason=item.optString("reason").takeIf{it in allowedReasons}?: "Otro"
     val result=api.createCorrectionRequest(
      item.optString("saleId"),
      safeReason,
      item.optString("key"),
      item.optString("planHash"),
      item.optJSONArray("reasons") ?: JSONArray().put(safeReason),
      item.optBoolean("cashImpact",false),
      item.optString("cashReason")
     )
     // Un HTTP exitoso sin identificador no demuestra que la solicitud quedó registrada.
     // Mantenerla en cola para reintentar con la misma clave idempotente.
     if(result.optString("requestId").isBlank())throw IllegalStateException("El servidor no confirmó el identificador de la solicitud")
     // Actualizar historial y cola bajo el mismo bloqueo que usa la consulta de respuestas.
     // Así una respuesta aprobada/grantId nunca se pisa con el estado "pending" del envío.
     val remaining=JSONArray()
     synchronized(prefs()){
      val history=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
      val requestId=result.optString("requestId")
      var exists=false
      for(i in 0 until history.length())if(history.optJSONObject(i)?.optString("requestId")==requestId)exists=true
      if(!exists)history.put(JSONObject().put("requestId",requestId).put("saleId",item.optString("saleId"))
       .put("reason",item.optString("reason")).put("detail",item.optString("detail"))
       .put("summary",item.optString("summary")).put("plan",item.optJSONArray("plan") ?: JSONArray())
       .put("planHash",item.optString("planHash")).put("reasons",item.optJSONArray("reasons") ?: JSONArray())
       .put("cashImpact",item.optBoolean("cashImpact",false)).put("cashReason",item.optString("cashReason"))
       .put("oldTotalCents",item.optLong("oldTotalCents")).put("newTotalCents",item.optLong("newTotalCents"))
       .put("status",result.optString("status","pending")).put("createdAt",result.optLong("createdAt",System.currentTimeMillis()/1000)))
      val latest=prefs().getString("correction_queue","[]")?.let{JSONArray(it)}?:JSONArray()
      for(i in 0 until latest.length()){
       val queued=latest.optJSONObject(i)?:continue
       if(queued.optString("key")!=item.optString("key"))remaining.put(queued)
      }
      prefs().edit().putString("correction_history",history.toString()).putString("correction_queue",remaining.toString()).commit()
     }
     current=remaining;sent++
    }
   }catch(_:Exception){
    if(showFeedback)runOnUiThread{msg("No se pudo enviar todavía. La solicitud quedó guardada y se reintentará al volver a Mi trabajo.")}
   }finally{
    // Solo el remitente dueño del lease puede liberarlo; uno antiguo no borra
    // el bloqueo de un remitente nuevo tras una recuperación por timeout.
    synchronized(prefs()){
     if(prefs().getString("correction_queue_sender_token",null)==senderToken){
      prefs().edit().putBoolean("correction_queue_sending",false)
       .remove("correction_queue_sending_at").remove("correction_queue_sender_token").commit()
     }
    }
    if(sent>0&&showFeedback)runOnUiThread{msg("Solicitud de corrección enviada a SENE PATRÓN.")}
   }
  }.start()
 }
 private fun flushQueue(showFeedback:Boolean=false){
  val base=prefs().getString("server_base_url",null)
  if(base.isNullOrBlank()){
   if(showFeedback)msg("Solicitud guardada en este teléfono. Configura la conexión para enviarla al patrón.")
   return
  }
  if(prefs().getString("device_id",null).isNullOrBlank()){
   if(showFeedback)msg("Solicitud guardada. Vincula SENE VENTAS con el QR de SENE PATRÓN para enviarla.")
   return
  }
  if(prefs().getBoolean("work_queue_sending",false))return
  val queue=prefs().getString("work_queue","[]")?.let{JSONArray(it)}?:JSONArray()
  if(queue.length()==0)return
  prefs().edit().putBoolean("work_queue_sending",true).apply()
  Thread{
   var sent=0
   try{
    val api=SeneApiClient(this,base)
    var current=prefs().getString("work_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    var index=0
    while(index<current.length()){
     val item=current.optJSONObject(index)
     if(item==null){index++;continue}
     val result=api.submitWorkRequest(item.optString("type"),item.optJSONObject("details")?:JSONObject(),item.optString("key"))
     val history=prefs().getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
     history.put(JSONObject().put("requestId",result.optString("requestId")).put("type",item.optString("label")).put("details",item.optJSONObject("details")?:JSONObject()).put("status",result.optString("status","pending")).put("createdAt",result.optLong("createdAt",System.currentTimeMillis()/1000)))
     val remaining=JSONArray()
     for(i in 0 until current.length())if(i!=index)remaining.put(current.get(i))
     prefs().edit().putString("work_history",history.toString()).putString("work_queue",remaining.toString()).apply()
     current=remaining;sent++
    }
   }catch(_:Exception){
    if(showFeedback)runOnUiThread{msg("No se pudo enviar ahora. La solicitud quedó guardada y se reintentará al volver a abrir Mi trabajo.")}
   }finally{
    prefs().edit().putBoolean("work_queue_sending",false).apply()
    if(sent>0&&showFeedback)runOnUiThread{msg("Solicitud enviada a SENE PATRÓN.")}
   }
  }.start()
 }
 private fun compactHistory(source:JSONArray):JSONArray{
  val result=JSONArray()
  for(i in maxOf(0,source.length()-30) until source.length()){
   val item=source.optJSONObject(i)?:continue
   result.put(JSONObject().put("requestId",item.optString("requestId"))
    .put("type",item.optString("type"))
    .put("details",JSONObject().put("description",item.optJSONObject("details")?.optString("description").orEmpty().take(200)))
    .put("status",item.optString("status"))
    .put("decisionDetails",item.optJSONObject("decisionDetails")?:JSONObject())
    .put("createdAt",item.optLong("createdAt",0L))
    .put("decidedAt",item.optLong("decidedAt",0L)))
  }
  return result
 }
 private fun showHistory(){
  val base=prefs().getString("server_base_url",null)
  if(base.isNullOrBlank()){config{showHistory()};return}
  if(prefs().getString("device_id",null).isNullOrBlank()){pairing();return}
  Thread{
   try{
    val since=prefs().getLong("work_updates_since",0L)
    val updates=SeneApiClient(this,base).requestUpdates(since)
    val decisions=updates.optJSONArray("items")?:JSONArray()
    val corrections=updates.optJSONArray("corrections")?:JSONArray()
    var history=JSONArray()
    var correctionHistory=JSONArray()
    var newest=since
    synchronized(prefs()){
     history=prefs().getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
     correctionHistory=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
     for(i in 0 until decisions.length()){
      val item=decisions.getJSONObject(i);val id=item.optString("requestId")
      for(j in 0 until history.length()){
       val old=history.optJSONObject(j)?:continue
       if(old.optString("requestId")==id){
        old.put("status",item.optString("status"))
        old.put("decisionDetails",item.optJSONObject("decisionDetails")?:JSONObject())
        old.put("decidedAt",item.optLong("decidedAt"))
       }
      }
      newest=maxOf(newest,item.optLong("decidedAt",0L))
     }
     for(i in 0 until corrections.length()){
      val item=corrections.optJSONObject(i)?:continue;val id=item.optString("requestId")
      for(j in 0 until correctionHistory.length()){
       val old=correctionHistory.optJSONObject(j)?:continue
       if(old.optString("requestId")==id){
        old.put("status",item.optString("status"))
        old.put("decidedAt",item.optLong("decidedAt",0L))
        if(!item.isNull("grantId"))old.put("grantId",item.optString("grantId"))
       }
      }
      newest=maxOf(newest,item.optLong("decidedAt",0L))
     }
     prefs().edit().putString("work_history",history.toString()).putString("correction_history",correctionHistory.toString())
      .putLong("work_updates_since",maxOf(0L,newest-1L)).commit()
    }
    val displayText=buildString{
     if(history.length()==0&&correctionHistory.length()==0)append("Todavía no tienes solicitudes registradas.")
     for(i in history.length()-1 downTo 0){
      val item=history.optJSONObject(i)?:continue
      append(item.optString("type")).append("\nEstado: ").append(item.optString("status")).append("\n")
      append(item.optJSONObject("details")?.optString("description").orEmpty()).append("\n\n")
     }
     if(correctionHistory.length()>0)append("CORRECCIONES DE VENTA\n")
     for(i in correctionHistory.length()-1 downTo 0){
      val item=correctionHistory.optJSONObject(i)?:continue
      append(item.optString("summary")).append("\nEstado: ").append(correctionStatusLabel(item.optString("status"))).append("\n")
      append("Motivo: ").append(item.optString("reason")).append("\n\n")
     }
    }
    runOnUiThread{AlertDialog.Builder(this).setTitle("MIS SOLICITUDES").setMessage(displayText).setPositiveButton("CERRAR",null).show()}
   }catch(e:Exception){
    val local=prefs().getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
    val localCorrections=prefs().getString("correction_history","[]")?.let{JSONArray(it)}?:JSONArray()
    val queuedCorrections=prefs().getString("correction_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    val localText=buildString{
     if(local.length()==0&&localCorrections.length()==0&&queuedCorrections.length()==0)append("Todavía no tienes solicitudes registradas.")
     for(i in local.length()-1 downTo 0){
      val item=local.optJSONObject(i)?:continue
      append(item.optString("type")).append("\nEstado: ").append(item.optString("status")).append("\n")
      append(item.optJSONObject("details")?.optString("description").orEmpty()).append("\n\n")
     }
     if(localCorrections.length()>0)append("CORRECCIONES DE VENTA\n")
     for(i in localCorrections.length()-1 downTo 0){
      val item=localCorrections.optJSONObject(i)?:continue
      append(item.optString("summary")).append("\nEstado: ").append(correctionStatusLabel(item.optString("status"))).append("\n")
      append("Motivo: ").append(item.optString("reason")).append("\n\n")
     }
     if(queuedCorrections.length()>0)append("Correcciones pendientes de envío: ").append(queuedCorrections.length()).append("\n")
     append("\nSin conexión: se muestra el historial guardado en este teléfono.")
    }
    runOnUiThread{AlertDialog.Builder(this).setTitle("MIS SOLICITUDES").setMessage(localText).setPositiveButton("CERRAR",null).show()}
   }
  }.start()
 }
 private fun showPayroll(){
  val full=prefs().getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
  val history=compactHistory(full)
  val worker=prefs().getString("worker_name","").orEmpty()
  val phone=prefs().getString("worker_phone","").orEmpty()
  val period=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
  val report=JSONObject().put("worker",worker).put("workerPhone",phone).put("periodKey",period).put("generatedAt",System.currentTimeMillis()).put("requests",history)
  prefs().edit().putString("payroll_local_$period",report.toString()).apply()
  val base=prefs().getString("server_base_url",null)
  val device=prefs().getString("device_id",null)
  AlertDialog.Builder(this).setTitle("DÍA DE PAGO · NÓMINA")
   .setMessage("Trabajador: "+worker.ifBlank{"No registrado"}+"\nSolicitudes: "+history.length()+"\nFecha: "+period+"\n\nPuedes consultar este reporte cuando quieras. El servidor solo lo recibe en la fecha configurada por el patrón.")
   .setNeutralButton("CONSULTAR"){_,_->AlertDialog.Builder(this).setTitle("REPORTE "+period).setMessage(report.toString(2)).setPositiveButton("CERRAR",null).show()}
   .setPositiveButton(if(base.isNullOrBlank()||device.isNullOrBlank())"VINCULAR PARA ENVIAR" else "ENVIAR AL SERVIDOR"){_,_->
    if(base.isNullOrBlank()||device.isNullOrBlank()){pairing();return@setPositiveButton}
    Thread{
     try{
      val result=SeneApiClient(this,base).sendPayrollReport(period,report,device)
      runOnUiThread{msg(if(result.optBoolean("duplicate"))"El reporte ya estaba registrado." else "Reporte enviado a SENE PATRÓN.")}
     }catch(e:Exception){
      val detail=(e as? SeneVentasApiException)?.responseBody.orEmpty()
      val friendly=when{
       detail.contains("INVALID_PAYROLL_PERIOD")->"La fecha del teléfono no coincide con la fecha del servidor. Verifica la hora del celular."
       detail.contains("PAYROLL_NOT_DUE")->"Hoy no es fecha de pago. Puedes consultar el reporte local."
       detail.contains("PAYROLL_NOT_CONFIGURED")->"El patrón aún no configuró sueldo y fecha de pago."
       else->"No se pudo enviar el reporte. Revisa la conexión."
      }
      runOnUiThread{msg(friendly)}
     }
    }.start()
   }.setNegativeButton("CERRAR",null).show()
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){
  super.onActivityResult(r,c,d)
  if(r==SCAN_REQUEST){
   if(c==RESULT_OK){
    val codes=d?.getStringArrayListExtra(SCAN_CODES)
    if(pendingPairingScan&&!codes.isNullOrEmpty()){
     pendingPairingScan=false
     val base=prefs().getString("server_base_url",null)?:return
     Thread{try{val result=SeneApiClient(this,base).pair(codes.last());runOnUiThread{msg("SENE VENTAS vinculado. Espacio: "+result.optString("slot"));flushQueue()}}catch(e:Exception){runOnUiThread{msg("No se pudo vincular: "+(e.message?:"Error"))}}}.start()
    }
   }else pendingPairingScan=false
  }
 }
}

class MainActivity:Activity(){
 private var toolsDialog:AlertDialog?=null

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
 private val ticket=linkedMapOf<String,Int>();private var pendingTicketScan=false;private val checklistScannedCodes=mutableListOf<String>();private val checklistUncheckedCodes=mutableListOf<String>();private lateinit var code:EditText;private lateinit var paid:EditText;private lateinit var ticketView:TextView;private lateinit var totalView:TextView;private lateinit var sell:Button;private var paidValidationError=false;private val h=Handler(Looper.getMainLooper())
 private var connectivityManager:ConnectivityManager?=null
 private val connectivityCallback=object:ConnectivityManager.NetworkCallback(){
  override fun onAvailable(network:Network){
   runOnUiThread{flushPendingShiftReports();flushPendingWorkRequests()}
  }
 }
 private var reportDialog:AlertDialog?=null
 private lateinit var countdownView:TextView
 private var pendingFinalReport=false
 private var pendingShareUri:Uri?=null
 private var pendingFinalShare=false
 private var pendingServerDelivery=false
 private var confirmingReport=false
 private var refreshAfterPatron=false
 private var pendingPairingScan=false
 private val lock=object:Runnable{override fun run(){if(::sell.isInitialized){updateReportCountdown();if(due())showReportAlert() else {sell.isEnabled=true;sell.alpha=1f}};h.postDelayed(this,1000)}}
 private fun updateReportCountdown(){if(!::countdownView.isInitialized)return;val s=Store.shift(this)?:return;val remaining=(7200000L-(System.currentTimeMillis()-s.optLong("lastReport",System.currentTimeMillis()))).coerceAtLeast(0L);if(remaining==0L){countdownView.text="\u26A0 REPORTE PENDIENTE: ENV\u00CDA PARA CONTINUAR";countdownView.setTextColor(0xFFB3261E.toInt());sell.isEnabled=false;sell.alpha=0.45f}else{val total=remaining/1000;val hh=total/3600;val mm=(total%3600)/60;val ss=total%60;countdownView.text=String.format(Locale.US,"SIGUIENTE REPORTE EN: %02d:%02d:%02d",hh,mm,ss);countdownView.setTextColor(0xFF168B45.toInt())}}
 private fun due():Boolean{val s=Store.shift(this)?:return false;return System.currentTimeMillis()-s.optLong("lastReport",System.currentTimeMillis())>=7200000}
 override fun onCreate(b:Bundle?){super.onCreate(b);if(Store.shift(this)==null){startActivity(Intent(this,InicioTurnoActivity::class.java));finish();return};draw();h.post(lock);salesClockHandler.post(salesClockTick);refreshSalesInternetClock();seneServerPrefs().edit().putBoolean("work_queue_sending",false).apply();flushPendingShiftReports();flushPendingWorkRequests();watchConnectivity()}
 override fun onResume(){super.onResume();if(::salesDeviceClock.isInitialized){salesClockHandler.removeCallbacks(salesClockTick);salesClockHandler.post(salesClockTick)};if(::salesInternetClock.isInitialized)refreshSalesInternetClock();if(!pendingServerDelivery)flushPendingShiftReports();flushPendingWorkRequests();if(pendingShareUri!=null&&!pendingServerDelivery)h.post{confirmReportSent()}else if(pendingServerDelivery)h.post{flushPendingShiftReports{pendingServerDelivery=false;launchPendingReportShare()}}else if(refreshAfterPatron){refreshAfterPatron=false;draw()}else if(::sell.isInitialized&&due())h.post{showReportAlert()}}
 override fun onDestroy(){h.removeCallbacks(lock);salesClockHandler.removeCallbacks(salesClockTick);salesClockHandler.removeCallbacks(salesInternetClockTick);try{connectivityManager?.unregisterNetworkCallback(connectivityCallback)}catch(_:Exception){};super.onDestroy()}
 private fun watchConnectivity(){
  try{
   connectivityManager=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
   connectivityManager?.registerDefaultNetworkCallback(connectivityCallback)
  }catch(_:Exception){connectivityManager=null}
 }
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
 private fun showReportAlert(){if(reportDialog?.isShowing==true)return;reportDialog=AlertDialog.Builder(this).setTitle("REPORTE CADA 2 HORAS").setMessage("Envía el resumen del reporte como texto por WhatsApp. El archivo TXT se guarda por separado en INVENTARIO SENE.").setCancelable(false).setPositiveButton("ENVIAR"){_,_->sendReport(false)}.create().also{it.setCanceledOnTouchOutside(false);it.show();it.window?.setLayout(-1,-2)}}
 private fun showToolsMenu(){
  val items=arrayOf(
   "Ventas hasta el momento","Llegó proveedor / Agregar inventario","Gastos a proveedores",
   "Ver stock","Soy Patrón / Administrador","Cerrar turno","Guardar reporte TXT",
   "Mi horario (8 horas)","Identidad de este equipo","Mi trabajo / Mis solicitudes",
   "Vincular con SENE PATRÓN","Día de pago / Reporte de nómina","AYUDA OFICIAL SENE"
  )
  val icons=arrayOf("💰","📦","🧾","🗃️","👑","🔒","📄","🕒","📱","📋","🔗","📅","🛟")
  val colors=intArrayOf(0xFFEAF2FF.toInt(),0xFFE8F7EF.toInt(),0xFFFFF3E5.toInt(),0xFFEAF7F7.toInt(),0xFFF2ECFF.toInt(),0xFFFFEEEE.toInt(),0xFFEAF2FF.toInt(),0xFFEAF7F7.toInt(),0xFFF1F3F8.toInt(),0xFFEAF2FF.toInt(),0xFFE8F7EF.toInt(),0xFFFFF3E5.toInt(),0xFFF2ECFF.toInt())
  val descriptions=arrayOf("Resumen de ventas y caja","Registrar mercancía recibida","Consultar pagos y gastos","Existencias y precios","Funciones del patrón","Finalizar jornada","Guardar copia del reporte","Consultar entrada y salida","Comprobar este teléfono","Solicitudes y respuestas","Conectar con el patrón","Historial de pago","Contactar soporte verificado")
  val actions:Array<()->Unit> = arrayOf(
   {showCurrentSalesSummary()},
   {startActivity(Intent(this,AgregarInventarioActivity::class.java))},
   {showProviderExpenses()},
   {stock()},
   {pin{refreshAfterPatron=true;startActivity(Intent(this,PatronActivity::class.java))}},
   {requestCloseShift()},
   {saveReportToPhone()},
   {showWorkerSchedule()},
   {showDeviceIdentity()},
   {startActivity(Intent(this,WorkerPortalActivity::class.java))},
   {showVentasPairing()},
   {showPayrollReport()},
   {showOfficialSupportContacts()}
  )
  val grid=android.widget.GridLayout(this).apply{
   columnCount=2;useDefaultMargins=false;alignmentMode=android.widget.GridLayout.ALIGN_MARGINS
   setPadding(dp(10),dp(8),dp(10),dp(8))
  }
  for(i in items.indices){
   val tile=LinearLayout(this).apply{
    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
    setPadding(dp(9),dp(10),dp(9),dp(9))
    background=rounded(colors[i],16,0xFFDCE5F2.toInt())
    isClickable=true;isFocusable=true
    contentDescription=items[i]+". "+descriptions[i]
    setOnClickListener{toolsDialog?.dismiss();actions[i].invoke()}
   }
   tile.addView(TextView(this).apply{text=icons[i];textSize=27f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(36)))
   tile.addView(TextView(this).apply{
    text=items[i];textSize=13.5f;gravity=Gravity.CENTER
    setTextColor(0xFF18345D.toInt());typeface=Typeface.create("sans-serif",Typeface.BOLD);maxLines=2
   },LinearLayout.LayoutParams(-1,-2))
   tile.addView(TextView(this).apply{
    text=descriptions[i];textSize=10.5f;gravity=Gravity.CENTER
    setTextColor(0xFF53647D.toInt());setPadding(0,dp(4),0,0);maxLines=2
   },LinearLayout.LayoutParams(-1,-2))
   val params=android.widget.GridLayout.LayoutParams().apply{
    width=0;height=dp(112);columnSpec=android.widget.GridLayout.spec(i%2,1f)
    rowSpec=android.widget.GridLayout.spec(i/2);setMargins(dp(5),dp(5),dp(5),dp(5))
   }
   grid.addView(tile,params)
  }
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(grid)}
  val toolsPanel=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(8),dp(8),dp(8),dp(8))
   background=rounded(0xFFF4F6FB.toInt(),20)
  }
  val toolsHeader=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL
   gravity=Gravity.CENTER_VERTICAL
   setPadding(dp(12),dp(5),dp(4),dp(8))
  }
  toolsHeader.addView(TextView(this).apply{
   text="SENE · HERRAMIENTAS"
   textSize=19f;setTextColor(0xFF18345D.toInt())
   typeface=Typeface.create("sans-serif",Typeface.BOLD)
  },LinearLayout.LayoutParams(0,-2,1f))
  val closeTools=TextView(this).apply{
   text="×";textSize=30f;gravity=Gravity.CENTER
   setTextColor(0xFF53647D.toInt())
   background=rounded(0xFFFFFFFF.toInt(),14,0xFFDCE5F2.toInt())
   contentDescription="Cerrar herramientas"
   isClickable=true;isFocusable=true
   setOnClickListener{toolsDialog?.dismiss()}
  }
  toolsHeader.addView(closeTools,LinearLayout.LayoutParams(dp(44),dp(44)))
  toolsPanel.addView(toolsHeader)
  toolsPanel.addView(scroll,LinearLayout.LayoutParams(-1,dp(510)))
  toolsDialog=AlertDialog.Builder(this).setView(toolsPanel).create()
  toolsDialog?.show()
  toolsDialog?.window?.setLayout((resources.displayMetrics.widthPixels*0.94f).toInt(),-2)
 }

 private fun showOfficialSupportContacts(){
  val base=seneServerPrefs().getString("server_base_url",null)
  if(base.isNullOrBlank()){msg("Conecta primero SENE VENTAS con SENE PATRÓN para consultar la AYUDA oficial.");return}
  msg("Consultando y verificando la firma digital de AYUDA…")
  Thread{
   try{
    val contacts=SeneApiClient(this,base).officialSupportContacts()
    val email=contacts.getString("officialEmail")
    val phone1=contacts.getString("helpPhone1")
    val phone2=contacts.getString("helpPhone2")
    runOnUiThread{
     AlertDialog.Builder(this)
      .setTitle("CONTACTOS OFICIALES SENE · VERIFICADOS")
      .setMessage("Datos comprobados con la firma digital del servidor. Revisión: "+contacts.getLong("revision")+
       "\n\nAYUDA 1: "+phone1+"\nAYUDA 2: "+phone2+"\nCorreo: "+email)
      .setItems(arrayOf("Abrir WhatsApp · AYUDA 1","Abrir WhatsApp · AYUDA 2","Enviar correo oficial")){_,which->
       val uri=when(which){
        0->Uri.parse("https://wa.me/"+phone1.filter{it.isDigit()})
        1->Uri.parse("https://wa.me/"+phone2.filter{it.isDigit()})
        else->Uri.parse("mailto:"+email)
       }
       try{startActivity(Intent(Intent.ACTION_VIEW,uri))}
       catch(_:Exception){msg("No se pudo abrir la aplicación correspondiente.")}
      }
      .setPositiveButton("CERRAR",null).show()
    }
   }catch(e:Exception){
    runOnUiThread{
     AlertDialog.Builder(this)
      .setTitle("AYUDA OFICIAL NO VERIFICADA")
      .setMessage("Por seguridad, SENE no mostrará ni utilizará números o correos que no pueda verificar con la firma digital del servidor.\n\nDetalle: "+(e.message?:"Error de verificación"))
      .setPositiveButton("ENTENDIDO",null).show()
    }
   }
  }.start()
 }

 private fun seneServerPrefs()=getSharedPreferences("sene_ventas_server",0)


 private fun compactPayrollHistory(source:JSONArray):JSONArray{
  val result=JSONArray()
  val start=maxOf(0,source.length()-30)
  for(i in start until source.length()){
   val item=source.optJSONObject(i)?:continue
   val details=item.optJSONObject("details")?:JSONObject()
   val description=details.optString("description").take(200)
   val compactDetails=JSONObject().put("description",description)
   if(details.has("amountCents"))compactDetails.put("amountCents",details.optLong("amountCents"))
   val compact=JSONObject()
    .put("requestId",item.optString("requestId"))
    .put("type",item.optString("type"))
    .put("details",compactDetails)
    .put("status",item.optString("status"))
    .put("decisionDetails",item.optJSONObject("decisionDetails")?:JSONObject())
    .put("createdAt",item.optLong("createdAt",0L))
    .put("decidedAt",item.optLong("decidedAt",0L))
   result.put(compact)
  }
  return result
 }
 private fun showPayrollReport(){
  val prefs=seneServerPrefs()
  val base=prefs.getString("server_base_url",null)
  val device=prefs.getString("device_id",null)
  val worker=prefs.getString("worker_name",Store.shift(this)?.optString("worker").orEmpty()).orEmpty()
  val phone=prefs.getString("worker_phone",Store.shift(this)?.optString("phone").orEmpty()).orEmpty()
  fun reportedRequestIds():Set<String>{
   val ids=prefs.getString("payroll_reported_request_ids","[]")?.let{JSONArray(it)}?:JSONArray()
   val result=LinkedHashSet<String>()
   for(i in 0 until ids.length())ids.optString(i).takeIf{it.isNotBlank()}?.let{result.add(it)}
   return result
  }
  var history=JSONArray()
  var payrollRequestIds=emptyList<String>()
  fun refreshPayrollScope(source:JSONArray){
   history=compactPayrollHistory(source)
   val sent=reportedRequestIds()
   val ids=ArrayList<String>()
   for(i in 0 until history.length()){
    val id=history.optJSONObject(i)?.optString("requestId").orEmpty()
    if(id.isNotBlank()&&id !in sent)ids.add(id)
   }
   payrollRequestIds=ids
  }
  refreshPayrollScope(prefs.getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray())
  fun syncDecisions(api:SeneApiClient):Boolean{
   return try{
    val since=prefs.getLong("work_updates_since",0L)
    val updates=api.requestUpdates(since,device!!)
    val decisions=updates.optJSONArray("items")?:JSONArray()
    val full=prefs.getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
    var newest=since
    for(i in 0 until decisions.length()){
     val item=decisions.optJSONObject(i)?:continue
     val id=item.optString("requestId")
     for(j in 0 until full.length()){
      val old=full.optJSONObject(j)?:continue
      if(old.optString("requestId")==id){
       old.put("status",item.optString("status"))
       old.put("decisionDetails",item.optJSONObject("decisionDetails")?:JSONObject())
       old.put("decidedAt",item.optLong("decidedAt",0L))
      }
     }
     newest=maxOf(newest,item.optLong("decidedAt",0L))
    }
    prefs.edit().putString("work_history",full.toString())
     .putLong("work_updates_since",maxOf(0L,newest-1L)).apply()
    refreshPayrollScope(full)
    true
   }catch(_:Exception){false}
  }
  fun reportFor(periodKey:String):JSONObject{
   val report=JSONObject()
    .put("worker",worker)
    .put("workerPhone",phone)
    .put("periodKey",periodKey)
    .put("generatedAt",System.currentTimeMillis())
    .put("requests",history)
    .put("payrollRequestIds",JSONArray(payrollRequestIds))
   prefs.edit().putString("payroll_local_$periodKey",report.toString()).apply()
   return report
  }
  fun showReport(periodKey:String,report:JSONObject,canSend:Boolean,statusMessage:String){
   val message=buildString{
    append("Trabajador: ").append(worker.ifBlank{"No registrado"}).append("\n")
    append("Solicitudes incluidas: ").append(history.length()).append("\n")
    append("Fecha del reporte (servidor): ").append(periodKey).append("\n\n")
    append(statusMessage).append("\n\n")
    append("Este reporte no se envía por WhatsApp. Actualiza primero Mis solicitudes para incluir las respuestas recientes del patrón.")
   }
   val builder=AlertDialog.Builder(this).setTitle("DÍA DE PAGO · NÓMINA").setMessage(message)
    .setNeutralButton("CONSULTAR REPORTE"){_,_->
     AlertDialog.Builder(this).setTitle("REPORTE DE NÓMINA · $periodKey")
      .setMessage(report.toString(2))
      .setPositiveButton("CERRAR",null).show()
    }
   if(canSend)builder.setPositiveButton("ENVIAR AL SERVIDOR"){_,_->
    Thread{
     try{
      val submissionKey="payroll_submission_$periodKey"
      val savedSubmission=prefs.getString(submissionKey,null)
      val submission=savedSubmission?.let{JSONObject(it)}?:report
      if(savedSubmission==null&&!prefs.edit().putString(submissionKey,submission.toString()).commit()){
       throw IllegalStateException("No se pudo guardar el reporte pendiente")
      }
      val result=SeneApiClient(this,base!!).sendPayrollReport(periodKey,submission,device!!)
      val storedReport=result.optJSONObject("report")?:submission
      val storedRequests=storedReport.optJSONArray("requests")?:JSONArray()
      val payrollIds=storedReport.optJSONArray("payrollRequestIds")?:JSONArray()
      val sentIds=LinkedHashSet<String>().apply{addAll(reportedRequestIds())}
      val requestStates=HashMap<String,String>()
      for(i in 0 until storedRequests.length()){
       val item=storedRequests.optJSONObject(i)?:continue
       requestStates[item.optString("requestId")]=item.optString("status")
      }
      for(i in 0 until payrollIds.length()){
       val id=payrollIds.optString(i)
       val state=requestStates[id]
       if(id.isNotBlank()&&(state=="approved"||state=="rejected"))sentIds.add(id)
      }
      val idArray=JSONArray()
      sentIds.toList().takeLast(1000).forEach{idArray.put(it)}
      prefs.edit().putString("payroll_reported_request_ids",idArray.toString()).apply()
      runOnUiThread{
       msg(if(result.optBoolean("duplicate"))"El reporte de este día ya estaba registrado; se recuperó su contenido original." else "Reporte de nómina enviado a SENE PATRÓN.")
      }
     }catch(e:Exception){
      val detail=(e as? SeneVentasApiException)?.responseBody.orEmpty()
      val friendly=when{
       detail.contains("INVALID_PAYROLL_PERIOD")->"La fecha del servidor cambió. Actualiza el reporte e inténtalo de nuevo."
       detail.contains("PAYROLL_NOT_DUE")->"Hoy no es una fecha de pago configurada por el patrón."
       detail.contains("PAYROLL_NOT_CONFIGURED")->"El patrón todavía no configuró sueldo y día de pago."
       else->"No se pudo enviar el reporte. Revisa la conexión."
      }
      runOnUiThread{msg(friendly)}
     }
    }.start()
   }
   builder.setNegativeButton("CERRAR",null).show()
  }
  if(base.isNullOrBlank()||device.isNullOrBlank()){
   val period=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
   val report=reportFor(period)
   showReport(period,report,false,"Vincula SENE VENTAS para consultar la fecha de pago y habilitar el envío.")
   return
  }
  Thread{
   try{
    val api=SeneApiClient(this,base)
    val session=api.session(device)
    val historyFresh=syncDecisions(api)
    val serverTime=session.optLong("serverTime",System.currentTimeMillis()/1000)
    val dateFormat=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{timeZone=TimeZone.getTimeZone("America/Mexico_City")}
    val period=dateFormat.format(Date(serverTime*1000))
    val report=reportFor(period)
    val payroll=session.optJSONObject("payroll")
    val configured=payroll?.optBoolean("configured",false)==true
    val due=payroll?.optBoolean("dueToday",false)==true
    val canSend=due && historyFresh
    val status=when{
     !configured->"El patrón todavía no configuró el sueldo y la fecha de pago de este equipo. El envío permanece deshabilitado."
     !due->"Hoy no es fecha de pago de este equipo. Puedes consultar el reporte; el envío está deshabilitado."
     !historyFresh->"No se pudieron actualizar las respuestas del patrón. Actualiza Mis solicitudes; el envío queda deshabilitado para no mandar datos incompletos."
     else->"Hoy es fecha de pago confirmada por el servidor y las respuestas están actualizadas. El envío está habilitado."
    }
    runOnUiThread{showReport(period,report,canSend,status)}
   }catch(e:Exception){
    val period=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
    val report=reportFor(period)
    runOnUiThread{showReport(period,report,false,"No se pudo validar la fecha con el servidor. Por seguridad, el envío está deshabilitado.")}
   }
  }.start()
 }
 private fun showVentasServerConfig(after:()->Unit){
  val prefs=seneServerPrefs()
  val field=input("https://tu-worker.workers.dev")
  field.setText(prefs.getString("server_base_url",""))
  AlertDialog.Builder(this).setTitle("Conexión segura SENE")
   .setMessage("Dirección HTTPS del servidor SENE. No uses la dirección de prueba Hello World.")
   .setView(field).setPositiveButton("Guardar"){_,_->
    val value=field.text.toString().trim().trimEnd('/')
    val uri=Uri.parse(value)
    if(!value.startsWith("https://")||uri.host.isNullOrBlank()||value.any{it.isWhitespace()}){
     msg("Dirección HTTPS no válida");return@setPositiveButton
    }
    prefs.edit().putString("server_base_url",value).apply()
    after()
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun showVentasPairing(){
  val prefs=seneServerPrefs()
  if(prefs.getString("server_base_url",null).isNullOrBlank()){
   showVentasServerConfig{showVentasPairing()};return
  }
  AlertDialog.Builder(this).setTitle("Vincular SENE VENTAS")
   .setMessage("En SENE PATRÓN genera el código QR para este equipo. Después pulsa ESCANEAR QR aquí. La vinculación usa el espacio libre de Equipo 1 o Equipo 2.")
   .setPositiveButton("ESCANEAR QR"){_,_->pendingPairingScan=true;openScanner()}
   .setNeutralButton("Cambiar servidor"){_,_->showVentasServerConfig{showVentasPairing()}}
   .setNegativeButton("Cerrar",null).show()
 }
 private fun finishVentasPairing(qr:String){
  val base=seneServerPrefs().getString("server_base_url",null)?:return
  Thread{
   try{
    val result=SeneApiClient(this,base).pair(qr)
    runOnUiThread{msg("SENE VENTAS vinculado correctamente. Espacio: "+result.optString("slot"));flushPendingShiftReports();flushPendingWorkRequests()}
   }catch(e:Exception){
    runOnUiThread{msg("No se pudo vincular: "+(e.message?:"Error de conexión"))}
   }
  }.start()
 }
 private fun showWorkerRequestsMenu(){
  if(seneServerPrefs().getString("device_id",null).isNullOrBlank()){
   AlertDialog.Builder(this).setTitle("SENE VENTAS no está vinculado")
    .setMessage("Primero vincula este teléfono con el QR generado desde SENE PATRÓN.")
    .setPositiveButton("VINCULAR"){_,_->showVentasPairing()}
    .setNegativeButton("Cerrar",null).show();return
  }
  flushPendingWorkRequests()
  val labels=arrayOf("Préstamo","Consumo","Permiso para faltar","Llegar tarde","Cambio de horario","Vacaciones","Reportar incidencia","Reportar faltante","Cambio de descanso","Adelanto de sueldo","Equipo dañado","Sugerencia")
  val types=arrayOf("LOAN","CONSUMPTION","ABSENCE_PERMISSION","LATE_ARRIVAL","SCHEDULE_CHANGE","VACATION","INCIDENT","SHORTAGE","REST_DAY_CHANGE","SALARY_ADVANCE","DAMAGED_EQUIPMENT","SUGGESTION")
  val icons=arrayOf("💵","🛒","🗓️","⏰","🔄","🌴","⚠️","📦","📆","💰","🛠️","💡")
  val tints=intArrayOf(0xFFE8F7EF.toInt(),0xFFFFF3E5.toInt(),0xFFEAF2FF.toInt(),0xFFF2ECFF.toInt(),0xFFEAF7F7.toInt(),0xFFE8F7EF.toInt(),0xFFFFEEEE.toInt(),0xFFEAF2FF.toInt(),0xFFEAF7F7.toInt(),0xFFFFF3E5.toInt(),0xFFF1F3F8.toInt(),0xFFF2ECFF.toInt())
  val descriptions=arrayOf("Solicitar un préstamo al patrón","Registrar un consumo pendiente","Pedir autorización para faltar","Avisar que llegarás tarde","Solicitar un ajuste de horario","Solicitar días de vacaciones","Informar una situación del trabajo","Reportar productos faltantes","Pedir cambio de día de descanso","Solicitar un adelanto","Informar equipo con daño","Compartir una propuesta")
  val content=LinearLayout(this).apply{orientation=1;setPadding(dp(14),dp(8),dp(14),dp(8));background=rounded(0xFFF4F6FB.toInt(),20)}
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(content)}
  for(i in labels.indices){
   content.addView(actionCard(labels[i],descriptions[i],icons[i],tints[i]){showWorkRequestForm(types[i],labels[i])})
  }
  content.addView(actionCard("Mis solicitudes","Consultar estados y respuestas del patrón","📋",0xFFEAF2FF.toInt()){showMyRequests()})
  AlertDialog.Builder(this).setTitle("MI TRABAJO · SOLICITUDES")
   .setView(scroll).setNegativeButton("CERRAR",null).show()
 }
 private fun showWorkRequestForm(type:String,label:String){
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(16),dp(12),dp(16),dp(12));background=rounded(0xFFF4F6FB.toInt(),18)}
  box.addView(txt("DETALLES DE LA SOLICITUD",12f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF53647D.toInt());setPadding(dp(3),dp(2),0,dp(7))})
  val details=input("Describe tu solicitud")
  details.minLines=3;details.maxLines=5
  box.addView(details)
  val amountField=if(type in setOf("LOAN","CONSUMPTION","SALARY_ADVANCE")) input("Cantidad solicitada en pesos $",true) else null
  amountField?.let{
   box.addView(txt("CANTIDAD SOLICITADA",12f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF53647D.toInt());setPadding(dp(3),dp(12),0,dp(5))})
   box.addView(it)
  }
  box.addView(txt("Se enviará a SENE PATRÓN. No modifica automáticamente caja, ventas ni inventario.",12f).apply{setTextColor(0xFF53647D.toInt());setPadding(dp(3),dp(10),dp(3),dp(2))})
  AlertDialog.Builder(this).setTitle(label)
   .setView(box).setPositiveButton("ENVIAR SOLICITUD"){_,_->
    val description=details.text.toString().trim()
    if(description.isBlank()){msg("Escribe los detalles de tu solicitud");return@setPositiveButton}
    val key=UUID.randomUUID().toString()
    val payload=JSONObject().put("description",description).put("requestedAt",System.currentTimeMillis())
    if(amountField!=null){
     val amountValue=amountField.text.toString().trim().replace(",", ".").toDoubleOrNull()
     if(amountValue==null||amountValue<=0||amountValue>1_000_000){msg("Captura una cantidad válida");return@setPositiveButton}
     payload.put("amountCents",Math.round(amountValue*100.0))
    }
    val prefs=seneServerPrefs()
    val queue=prefs.getString("work_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    queue.put(JSONObject().put("key",key).put("type",type).put("label",label).put("details",payload))
    prefs.edit().putString("work_queue",queue.toString()).apply()
    flushPendingWorkRequests()
   }.setNegativeButton("Cancelar",null).show()
 }

 private fun flushPendingWorkRequests(){
  val prefs=seneServerPrefs()
  if(prefs.getBoolean("work_queue_sending",false))return
  val base=prefs.getString("server_base_url",null)?:return
  val queue=prefs.getString("work_queue","[]")?.let{JSONArray(it)}?:JSONArray()
  if(queue.length()==0)return
  prefs.edit().putBoolean("work_queue_sending",true).apply()
  Thread{
   var sent=0
   try{
    val api=SeneApiClient(this,base)
    var current=prefs.getString("work_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    var index=0
    while(index<current.length()){
     val item=current.optJSONObject(index)
     if(item==null){index++;continue}
     val result=api.submitWorkRequest(item.optString("type"),item.optJSONObject("details")?:JSONObject(),item.optString("key"))
     val history=prefs.getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
     val requestId=result.optString("requestId")
     var exists=false
     for(j in 0 until history.length())if(history.optJSONObject(j)?.optString("requestId")==requestId)exists=true
     if(!exists)history.put(JSONObject().put("requestId",requestId).put("type",item.optString("label")).put("details",item.optJSONObject("details")?:JSONObject()).put("status",result.optString("status","pending")).put("createdAt",result.optLong("createdAt",System.currentTimeMillis()/1000)))
     val remaining=JSONArray()
     for(j in 0 until current.length())if(j!=index)remaining.put(current.get(j))
     prefs.edit().putString("work_history",history.toString()).putString("work_queue",remaining.toString()).apply()
     current=remaining
     sent++
    }
   }catch(_:Exception){}finally{
    prefs.edit().putBoolean("work_queue_sending",false).apply()
    if(sent>0)runOnUiThread{if(!isFinishing)Toast.makeText(this,"Solicitud enviada a SENE PATRÓN.",Toast.LENGTH_LONG).show()}
   }
  }.start()
 }
 private fun showMyRequests(){
  val prefs=seneServerPrefs()
  val base=prefs.getString("server_base_url",null)
  if(base.isNullOrBlank()){showVentasServerConfig{showMyRequests()};return}
  Thread{
   try{
    val since=prefs.getLong("work_updates_since",0L)
    val updates=SeneApiClient(this,base).requestUpdates(since)
    val items=updates.optJSONArray("items")?:JSONArray()
    val history=prefs.getString("work_history","[]")?.let{JSONArray(it)}?:JSONArray()
    var newest=since
    for(i in 0 until items.length()){
     val item=items.getJSONObject(i);val id=item.optString("requestId")
     for(j in 0 until history.length()){
      val old=history.optJSONObject(j)?:continue
      if(old.optString("requestId")==id){old.put("status",item.optString("status"));old.put("decisionDetails",item.optJSONObject("decisionDetails")?:JSONObject());old.put("decidedAt",item.optLong("decidedAt"))}
     }
     newest=maxOf(newest,item.optLong("decidedAt",0L))
    }
    prefs.edit().putString("work_history",history.toString()).putLong("work_updates_since",maxOf(0L,newest-1L)).apply()
    val message=buildString{
     if(history.length()==0)append("Todavía no tienes solicitudes registradas.")
     for(i in history.length()-1 downTo 0){
      val item=history.optJSONObject(i)?:continue
      append(item.optString("type")).append("\nEstado: ").append(item.optString("status")).append("\n")
      append(item.optJSONObject("details")?.optString("description").orEmpty()).append("\n\n")
     }
    }
    runOnUiThread{AlertDialog.Builder(this).setTitle("MIS SOLICITUDES").setMessage(message).setPositiveButton("Actualizar",null).setNegativeButton("Cerrar",null).show()}
   }catch(e:Exception){runOnUiThread{msg("No se pudo actualizar el historial: "+(e.message?:"Error"))}}
  }.start()
 }

 private fun queueShiftReportForServer(onSuccess:(()->Unit)?=null){
  val shift=Store.shift(this)?:return
  val start=shift.optLong("startTs",0L)
  if(start<=0L)return
  val key="shift-$start"
  val sales=Store.sales(this)
  var total=0.0
  var tickets=0
  for(i in 0 until sales.length()){
   val item=sales.optJSONObject(i)?:throw IllegalStateException("Hay una venta dañada; no se puede preparar el reporte del turno.")
   if(item.optLong("ts",0L)>=start){
    val amount=item.optDouble("subtotal",Double.NaN)
    val qty=item.optInt("qty",0)
    check(amount.isFinite()&&amount>=0.0&&qty>0){"Hay una venta con importe o cantidad inválidos; revisa Tickets antes de cerrar."}
    total+=amount
    check(total.isFinite()){"El total vendido no es válido; no se puede preparar el reporte."}
    tickets++
   }
  }
  val openingCash=shift.optDouble("cash",Double.NaN)
  check(openingCash.isFinite()&&openingCash>=0.0){"La caja inicial es inválida; no se puede preparar el reporte."}
  val expenses=try{Store.providerExpenses(this,start)}catch(e:Exception){throw IllegalStateException("No se pudo preparar el reporte del turno porque hay movimientos inválidos. Revisa el historial antes de cerrar.",e)}
  val cashAtClose=openingCash+total-expenses
  check(cashAtClose.isFinite()&&cashAtClose>=0.0){"El saldo de cierre no es válido; revisa ventas y pagos antes de cerrar."}
  val payload=JSONObject()
   .put("worker",shift.optString("worker"))
   .put("shiftStartedAt",start)
   .put("shiftClosedAt",shift.optLong("closingTs",System.currentTimeMillis()))
   .put("openingCash",openingCash)
   .put("salesTotal",total)
   .put("salesLines",tickets)
   .put("supplierExpenses",expenses)
   .put("cashAtClose",cashAtClose)
   // El inventario completo se envía una sola vez como parte del reporte final.
   .put("inventory",Store.products(this))
  val prefs=seneServerPrefs()
  val base=prefs.getString("server_base_url",null)
  val device=prefs.getString("device_id",null)
  check(!base.isNullOrBlank()&&!device.isNullOrBlank()){"No se puede enviar el inventario: primero debe estar activa la vinculación con el servidor SENE."}
  val sentKeys=prefs.getString("shift_report_sent_keys","[]")?.let{JSONArray(it)}?:JSONArray()
  for(i in 0 until sentKeys.length())if(sentKeys.optString(i)==key){onSuccess?.invoke();return}
  val queue=prefs.getString("shift_report_queue","[]")?.let{JSONArray(it)}?:JSONArray()
  var exists=false
  for(i in 0 until queue.length())if(queue.optJSONObject(i)?.optString("shiftKey")==key)exists=true
  if(!exists)queue.put(JSONObject().put("shiftKey",key).put("report",payload))
  check(prefs.edit().putString("shift_report_queue",queue.toString()).commit()){
   "No se pudo guardar el reporte pendiente; no se confirmó su envío."
  }
  flushPendingShiftReports(onSuccess)
 }
 private fun flushPendingShiftReports(onSuccess:(()->Unit)?=null){
  // SharedPreferences no ofrece compare-and-set: serializa la reserva del envío
  // entre distintas instancias de Activity dentro del mismo proceso.
  val reservation=synchronized(ShiftReportSendLock){
   val prefs=seneServerPrefs()
   val now=System.currentTimeMillis()
   val sending=prefs.getBoolean("shift_reports_sending",false)
   val sendingAt=prefs.getLong("shift_reports_sending_at",0L)
   // El bloqueo en memoria protege todo el envío mientras el proceso siga vivo,
   // aunque la cola tarde más de cinco minutos o cambie el reloj del teléfono.
   if(ShiftReportSendLock.active)return@synchronized null
   // Tras reiniciar el proceso, el bloqueo en memoria desaparece. La marca
   // temporal permite esperar una petición anterior antes de recuperar la cola.
   if(sending && sendingAt>0L && now>=sendingAt && now-sendingAt<300000L)return@synchronized null
   val base=prefs.getString("server_base_url",null)?:return@synchronized null
   val device=prefs.getString("device_id",null)?:return@synchronized null
   val queue=prefs.getString("shift_report_queue","[]")?.let{JSONArray(it)}?:JSONArray()
   if(queue.length()==0)return@synchronized null
   if(!prefs.edit().putBoolean("shift_reports_sending",true).putLong("shift_reports_sending_at",now).commit())return@synchronized null
   ShiftReportSendLock.active=true
   Triple(prefs,base,device)
  }?:return
  val prefs=reservation.first
  val base=reservation.second
  val device=reservation.third
  Thread{
   try{
    val api=SeneApiClient(this,base)
    var current=prefs.getString("shift_report_queue","[]")?.let{JSONArray(it)}?:JSONArray()
    while(current.length()>0){
     val item=current.optJSONObject(0)?:break
     val sentKey=item.optString("shiftKey")
     check(sentKey.isNotBlank()){"La cola contiene un reporte sin identificador de turno."}
     api.sendShiftReport(sentKey,item.optJSONObject("report")?:JSONObject(),device)
     // Read the latest persisted queue after the network call. A new report may
     // have been appended while this request was in flight; never overwrite it
     // with the stale snapshot captured before sending.
     val latest=prefs.getString("shift_report_queue","[]")?.let{JSONArray(it)}?:JSONArray()
     val remaining=JSONArray()
     var removed=false
     for(i in 0 until latest.length()){
      val queued=latest.optJSONObject(i)
      if(!removed && queued?.optString("shiftKey")==sentKey)removed=true
      else remaining.put(latest.get(i))
     }
     check(removed){"El servidor recibió el reporte, pero no se encontró su entrada local para confirmar la cola."}
     val sent=prefs.getString("shift_report_sent_keys","[]")?.let{JSONArray(it)}?:JSONArray()
     var alreadySent=false
     for(i in 0 until sent.length())if(sent.optString(i)==sentKey)alreadySent=true
     if(!alreadySent)sent.put(sentKey)
     val sentTrimmed=JSONArray()
     for(i in maxOf(0,sent.length()-1000) until sent.length())sentTrimmed.put(sent.get(i))
     check(prefs.edit().putString("shift_report_queue",remaining.toString())
      .putString("shift_report_sent_keys",sentTrimmed.toString()).commit()){
      "El reporte fue enviado, pero no se pudo confirmar localmente su recepción."
     }
     current=remaining
    }
    onSuccess?.let{callback->runOnUiThread{callback()}}
   }catch(e:Exception){
    val detail=(e.message?:e.javaClass.simpleName).replace("\n"," ").take(140)
    runOnUiThread{
     android.widget.Toast.makeText(
      applicationContext,
      "El reporte sigue guardado y pendiente de envío. Se reintentará al recuperar la conexión. Detalle: $detail",
      android.widget.Toast.LENGTH_LONG
     ).show()
    }
   }finally{
    synchronized(ShiftReportSendLock){
     prefs.edit().putBoolean("shift_reports_sending",false).remove("shift_reports_sending_at").commit()
     ShiftReportSendLock.active=false
    }
   }
  }.apply {
   try{start()}catch(e:Exception){
    synchronized(ShiftReportSendLock){
     prefs.edit().putBoolean("shift_reports_sending",false).remove("shift_reports_sending_at").commit()
     ShiftReportSendLock.active=false
    }
    throw e
   }
  }
 }
 private fun showCurrentSalesSummary(){
  val shift=Store.shift(this)?:JSONObject()
  val all=Store.sales(this)
  val today=day()
  val startTs=shift.optLong("startTs",0L)
  var dayTotal=0.0;var shiftTotal=0.0
  var dayUnits=0;var shiftUnits=0
  val dayTickets=mutableSetOf<Long>();val shiftTickets=mutableSetOf<Long>()
  try{
   for(i in 0 until all.length()){
    val sale=all.optJSONObject(i)?:throw IllegalStateException("Hay una venta dañada en el historial.")
    val time=sale.optString("time")
    val ts=sale.optLong("ts",0L)
    val amount=sale.optDouble("subtotal",Double.NaN)
    val qty=sale.optInt("qty",0)
    check(amount.isFinite()&&amount>=0.0&&qty>0){"Hay una venta con importe o cantidad inválidos en el historial."}
    if(time.startsWith(today)){
     dayTotal+=amount;dayUnits+=qty
     check(dayTotal.isFinite()){"El total del día no es válido."}
     dayTickets.add(if(ts>0L)ts else time.hashCode().toLong())
    }
    if(startTs>0L && ts>=startTs){
     shiftTotal+=amount;shiftUnits+=qty
     check(shiftTotal.isFinite()){"El total del turno no es válido."}
     shiftTickets.add(if(ts>0L)ts else time.hashCode().toLong())
    }
   }
  }catch(e:Exception){msg("No se puede calcular el resumen con seguridad: "+(e.message?:"revisa el historial."));return}
  val opening=shift.optDouble("cash",Double.NaN)
  if(!opening.isFinite()||opening<0.0){msg("La caja inicial tiene un importe inválido. Revisa el turno.");return}
  val providerExpenses=try{Store.providerExpenses(this,startTs)}catch(e:Exception){msg("No se puede calcular la caja con seguridad: "+(e.message?:"revisa los movimientos registrados."));return}
  val estimated=opening+shiftTotal-providerExpenses
  if(!estimated.isFinite()||estimated<0.0){msg("El saldo estimado no es válido. Revisa ventas y pagos antes de continuar.");return}
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

 private fun showWorkerSchedule(){
  val shift=Store.shift(this)
  if(shift==null){
   AlertDialog.Builder(this).setTitle("MI HORARIO").setMessage("No hay un turno activo. Inicia tu turno para consultar tu horario.").setPositiveButton("CERRAR",null).show()
   return
  }
  val start=shift.optLong("startTs",0L)
  if(start<=0L){
   AlertDialog.Builder(this).setTitle("MI HORARIO").setMessage("No encontramos la hora de inicio de este turno.").setPositiveButton("CERRAR",null).show()
   return
  }
  val end=start+8L*60L*60L*1000L
  val locale=Locale("es","MX")
  val timeFormat=SimpleDateFormat("hh:mm a",locale)
  val dateFormat=SimpleDateFormat("EEEE dd 'de' MMMM 'de' yyyy",locale)
  val content=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL
   setPadding(dp(22),dp(14),dp(22),dp(12))
  }
  content.addView(TextView(this).apply{
   text="Comenzaste tu turno a las "+timeFormat.format(Date(start))
   textSize=18f;setTextColor(0xFF203A2B.toInt());typeface=Typeface.DEFAULT_BOLD
  })
  content.addView(TextView(this).apply{
   text="Fecha: "+dateFormat.format(Date(start))
   textSize=14f;setTextColor(0xFF596579.toInt());setPadding(0,dp(5),0,dp(18))
  })
  content.addView(TextView(this).apply{
   text="Deberías salir a las "+timeFormat.format(Date(end))
   textSize=18f;setTextColor(0xFF245DB8.toInt());typeface=Typeface.DEFAULT_BOLD
  })
  content.addView(TextView(this).apply{
   text="Hora aproximada de salida · jornada estimada de 8 horas"
   textSize=13f;setTextColor(0xFF596579.toInt());setPadding(0,dp(5),0,dp(20))
  })
  content.addView(TextView(this).apply{
   text="TIEMPO RESTANTE";textSize=13f;setTextColor(0xFF596579.toInt());typeface=Typeface.DEFAULT_BOLD
  })
  val counter=TextView(this).apply{
   textSize=30f;setTextColor(0xFF16834A.toInt());typeface=Typeface.DEFAULT_BOLD
   setPadding(0,dp(5),0,dp(4))
  }
  content.addView(counter)
  val dialog=AlertDialog.Builder(this).setTitle("MI HORARIO").setView(content).setPositiveButton("CERRAR",null).create()
  val handler=Handler(Looper.getMainLooper())
  val tick=object:Runnable{
   override fun run(){
    val remaining=end-System.currentTimeMillis()
    if(remaining>0L){
     val seconds=remaining/1000L
     counter.text=String.format(Locale.US,"%02d:%02d:%02d",seconds/3600L,(seconds%3600L)/60L,seconds%60L)
    }else{
     val elapsed=(-remaining)/1000L
     counter.setTextColor(0xFFB3261E.toInt())
     counter.text="Horario cumplido · +"+String.format(Locale.US,"%02d:%02d:%02d",elapsed/3600L,(elapsed%3600L)/60L,elapsed%60L)
    }
    if(dialog.isShowing)handler.postDelayed(this,1000L)
   }
  }
  dialog.setOnDismissListener{handler.removeCallbacks(tick)}
  dialog.setOnShowListener{handler.post(tick)}
  dialog.show()
 }
 private fun showDeviceIdentity(){
  try{
   val identity=DeviceIdentity.get(this)
   // Autocomprobación local: confirma que Android Keystore puede firmar
   // y que la clave pública corresponde a la privada de esta instalación.
   // No registra ni autoriza el dispositivo en ningún servidor.
   val challenge=ByteArray(32).also{java.security.SecureRandom().nextBytes(it)}
   val signature=DeviceIdentity.signChallenge(this,challenge)
   val validSignature=DeviceIdentity.verifyChallenge(identity,challenge,signature)
   val alteredChallenge=challenge.copyOf().also{
    it[0]=(it[0].toInt() xor 1).toByte()
   }
   val alteredChallengeRejected=!DeviceIdentity.verifyChallenge(identity,alteredChallenge,signature)
   val alteredSignature=android.util.Base64.decode(signature,android.util.Base64.NO_WRAP).also{
    it[it.lastIndex]=(it.last().toInt() xor 1).toByte()
   }
   val alteredSignatureRejected=!DeviceIdentity.verifyChallenge(
    identity,challenge,android.util.Base64.encodeToString(alteredSignature,android.util.Base64.NO_WRAP)
   )
   val alteredFingerprint=identity.copy(fingerprint=identity.fingerprint.reversed())
   val alteredFingerprintRejected=!DeviceIdentity.verifyChallenge(alteredFingerprint,challenge,signature)
   val integrityOk=validSignature&&alteredChallengeRejected&&alteredSignatureRejected&&alteredFingerprintRejected
   val integrityText=if(integrityOk)"CORRECTA" else "NO SUPERADA"
   val message="APLICACIÓN: SENE VENTAS"+
    "\nESTADO: NO VINCULADO"+
    "\nESPACIO: PENDIENTE DE ASIGNAR (EQUIPO 1 O EQUIPO 2)"+
    "\nPRUEBA LOCAL DE IDENTIDAD: "+integrityText+
    "\nRETO ALTERADO: "+(if(alteredChallengeRejected)"RECHAZADO" else "ACEPTADO")+
    "\nFIRMA ALTERADA: "+(if(alteredSignatureRejected)"RECHAZADA" else "ACEPTADA")+
    "\nHUELLA ALTERADA: "+(if(alteredFingerprintRejected)"RECHAZADA" else "ACEPTADA")+
    "\n\nID DE ESTA INSTALACIÓN:\n"+identity.installationId+
    "\n\nID DE CONTINUIDAD DEL DISPOSITIVO:\n"+identity.continuityId+
    "\n\nHUELLA DE SU CLAVE:\n"+identity.fingerprint+
    "\n\nEl ID de continuidad permite reconocer este mismo teléfono después de reinstalar SENE VENTAS, mientras Android conserve su identificador de dispositivo y se mantengan el perfil y la firma de la app. La clave criptográfica sí se renueva al desinstalar y volver a instalar. El servidor deberá localizar la vinculación anterior y exigir autenticación y una nueva comprobación antes de recuperarla. Todavía no se ha registrado en Firebase ni autorizado por SENE PATRÓN."
   AlertDialog.Builder(this).setTitle("IDENTIDAD DEL EQUIPO").setMessage(message).setPositiveButton("CERRAR",null).show()
  }catch(e:Exception){
   AlertDialog.Builder(this).setTitle("IDENTIDAD DEL EQUIPO").setMessage("No se pudo crear, firmar o comprobar la identidad segura de este teléfono.\n\nDetalle: "+(e.message?:"Error desconocido")).setPositiveButton("CERRAR",null).show()
  }
 }
 private fun showProviderExpenses(){
  val shift=Store.shift(this)?:JSONObject()
  val start=shift.optLong("startTs",0L)
  val moves=Store.moves(this)
  val rows=mutableListOf<String>()
  val total=try{Store.providerExpenses(this,start)}catch(e:Exception){msg("No se pueden mostrar los gastos con seguridad: "+(e.message?:"revisa el historial."));return}
  for(i in 0 until moves.length()){
   val move=moves.optJSONObject(i)?:run{msg("Hay un movimiento dañado; no se puede mostrar un total confiable.");return}
   if(move.optString("type")!="PROVIDER_PAYMENT"||move.optLong("ts",0L)<start)continue
   val amount=move.optDouble("amount",Double.NaN)
   if(!amount.isFinite()||amount<0.0){msg("Hay un pago a proveedor con importe inválido; revisa el historial.");return}
   rows.add(move.optString("time")+"\n"+move.optString("supplier","Proveedor")+" · "+move.optString("text")+"\nPago: "+Store.money(amount))
  }
  val view=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
  view.addView(txt("TOTAL PAGADO EN ESTE TURNO\n"+Store.money(total),19f))
  val cash=try{Store.cashBalance(this)}catch(e:Exception){msg("No se puede calcular la caja con seguridad: "+(e.message?:"revisa los movimientos registrados."));return}
  view.addView(txt("CAJA ACTUAL ESTIMADA\n"+Store.money(cash),21f))
  view.addView(txt(if(rows.isEmpty())"Aún no hay pagos a proveedores registrados en este turno." else rows.asReversed().joinToString("\n\n"),14f))
  AlertDialog.Builder(this).setTitle("GASTOS A PROVEEDORES").setView(view).setPositiveButton("CERRAR",null).show()
 }
 private fun showTickets(){
  val sales=Store.sales(this)
  val groups=linkedMapOf<String,MutableList<JSONObject>>()
  for(i in 0 until sales.length()){
   val row=sales.optJSONObject(i)?:continue
   val ts=row.optLong("ts",0L)
   val id=row.optString("saleId").takeIf{it.isNotBlank()}?:("legacy-"+ts)
   groups.getOrPut(id){mutableListOf()}.add(row)
  }
  if(groups.isEmpty()){msg("Todavía no hay tickets de ventas realizadas");return}
  val entries=groups.entries.toList().asReversed()
  val labels=entries.map{entry->
   val rows=entry.value
   val first=rows.first()
   val total=first.optDouble("saleTotal",rows.sumOf{it.optDouble("subtotal",0.0)})
   first.optString("time","Fecha no disponible")+" · "+rows.size+" productos · "+Store.money(total)
  }.toTypedArray()
  AlertDialog.Builder(this).setTitle("TICKETS DE VENTAS")
   .setItems(labels){_,which->
    val rows=entries[which].value
    val first=rows.first()
    val body=buildString{
     append("VENTA REALIZADA\n").append(first.optString("time","Fecha no disponible"))
     append("\nFolio: ").append(first.optString("saleId","Venta anterior sin folio"))
     append("\n\nPRODUCTOS\n")
     for(row in rows)append("• ").append(row.optString("name","Producto"))
      .append(" x").append(row.optInt("qty"))
      .append(" · ").append(Store.money(row.optDouble("subtotal"))).append("\n")
     append("\nTOTAL ACTUAL DEL TICKET: ").append(Store.money(first.optDouble("saleTotal",rows.sumOf{it.optDouble("subtotal",0.0)})))
     append("\nPago recibido al realizar la venta: ").append(Store.money(first.optDouble("amountPaid")))
     append("\nCambio entregado originalmente: ").append(Store.money(first.optDouble("change")))
     val correctionMoves=Store.moves(this@MainActivity)
     val ticketCorrections=(0 until correctionMoves.length()).mapNotNull{idx->correctionMoves.optJSONObject(idx)}
      .filter{it.optString("type")=="CORRECTION_AUDIT" && it.optString("saleId")==first.optString("saleId")}
      .sortedBy{it.optLong("ts",0L)}
     if(ticketCorrections.isNotEmpty()){
      append("\n\nAJUSTES POSTERIORES REGISTRADOS")
      for(audit in ticketCorrections){
       val evidence=audit.optJSONObject("cashEvidence")?:JSONObject()
       val method=when(evidence.optString("method").lowercase()){
        "cash"->"Efectivo";"card"->"Tarjeta";"transfer"->"Transferencia";"other"->"Otro";else->"No indicado"
       }
       val at=audit.optLong("ts",0L)
       append("\n\n").append(if(at>0)SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(at)) else "Fecha no disponible")
       append("\nMotivo: ").append(audit.optString("reason","Sin motivo"))
       if(audit.optString("detail").isNotBlank())append("\nDetalle: ").append(audit.optString("detail"))
       append("\nTotal anterior: ").append(Store.money(audit.optDouble("previousTotal")))
       append("\nTotal corregido: ").append(Store.money(audit.optDouble("newTotal")))
       when(audit.optString("direction")){
        "COBRO"->append("\nDiferencia cobrada después: ").append(Store.money(audit.optDouble("amount")))
        "DEVOLUCION"->append("\nDiferencia devuelta después: ").append(Store.money(audit.optDouble("amount")))
        else->append("\nSin movimiento adicional de dinero")
       }
       if(audit.optDouble("amount",0.0)>0.0){
        append("\nConfirmación: ").append(if(audit.optBoolean("cashConfirmed"))"Registrada" else "No registrada")
        append("\nMedio: ").append(method)
        append("\nReferencia: ").append(evidence.optString("reference","-"))
       }
       if(audit.optString("cashReason").isNotBlank())append("\nMotivo del ajuste de caja: ").append(audit.optString("cashReason"))
      }
      append("\n\nNota: el total corregido ya está reflejado en el total actual de la venta. Los ajustes posteriores se muestran como evidencia y no deben sumarse otra vez.")
     }
     val checklist=first.optString("checklistStatus","")
     append("\n\nVERIFICACIÓN DE PRODUCTOS: ").append(when(checklist){"COMPLETO"->"COMPLETA";"INCOMPLETO"->"INCOMPLETA";"NO_REALIZADO"->"NO REALIZADA";else->"SIN DATOS (VENTA ANTERIOR)"})
     val scanned=first.optJSONArray("checklistScannedCodes")?:JSONArray()
     val unchecked=first.optJSONArray("checklistUncheckedCodes")?:JSONArray()
     if(scanned.length()>0){
      append("\nCódigos revisados: ")
      for(i in 0 until scanned.length()){if(i>0)append(", ");append(scanned.optString(i))}
     }
     if(unchecked.length()>0){
      append("\nCódigos que quedaron sin marcar: ")
      for(i in 0 until unchecked.length()){if(i>0)append(", ");append(unchecked.optString(i))}
     }
     append("\n\nEsta consulta es solo de lectura. Para solicitar una corrección, usa la opción de correcciones; no se modifica la venta desde aquí.")
    }
    AlertDialog.Builder(this).setTitle("DETALLE DEL TICKET").setMessage(body)
     .setPositiveButton("CERRAR",null).show()
   }.setNegativeButton("CERRAR",null).show()
 }
 private fun ticketRowsBySale():LinkedHashMap<String,MutableList<JSONObject>>{
  val grouped=linkedMapOf<String,MutableList<JSONObject>>()
  val sales=Store.sales(this)
  for(i in 0 until sales.length()){
   val row=sales.optJSONObject(i)?:continue
   val id=row.optString("saleId")
   if(id.isBlank()||id.startsWith("legacy-"))continue
   grouped.getOrPut(id){mutableListOf()}.add(JSONObject(row.toString()))
  }
  return grouped
 }
 private fun identifyOriginalTicket(scanned:ArrayList<String>):Pair<String,List<JSONObject>>?{
  val scannedCodes=scanned.map{it.trim()}.filter{it.isNotBlank()}.toSet()
  if(scannedCodes.isEmpty()){msg("No se detectaron códigos válidos.");return null}
  val matches=ticketRowsBySale().filterValues{rows->
   rows.map{it.optString("code").trim()}.filter{it.isNotBlank()}.toSet()==scannedCodes
  }
  if(matches.isEmpty()){
   msg("No se encontró una venta original que coincida exactamente con los códigos escaneados. No se enviará ninguna solicitud.")
   return null
  }
  if(matches.size>1){
   msg("Los códigos coinciden con más de una venta. Para evitar una corrección equivocada, no se seleccionó ninguna. Escanea el ticket completo con todos sus códigos.")
   return null
  }
  val entry=matches.entries.first()
  return entry.key to entry.value
 }
 private fun showTicketActionPicker(saleId:String,rows:List<JSONObject>,scanned:ArrayList<String>){
  val first=rows.firstOrNull()?:return
  val total=first.optDouble("saleTotal",rows.sumOf{it.optDouble("subtotal",0.0)})
  val info=first.optString("time","Fecha no disponible")+"\nFolio: "+saleId+"\nProductos: "+rows.size+"\nTotal: "+Store.money(total)
  val actions=arrayOf("VERIFICAR TICKET","CONSULTAR TICKET","SOLICITAR CORRECCIÓN")
  var selected=-1
  val dialog=AlertDialog.Builder(this).setTitle("VENTA ORIGINAL IDENTIFICADA")
   .setMessage(info+"\n\n¿Qué deseas hacer con este ticket?")
   .setSingleChoiceItems(actions,-1){_,which->selected=which}
   .setPositiveButton("ABRIR"){_,_->
    when(selected){
     0->showMatchedTicketVerification(saleId,rows,scanned)
     1->showMatchedTicketConsultation(saleId,rows)
     2->showScannedTicketCorrectionForm(saleId,rows)
     else->msg("Selecciona una opción para continuar.")
    }
   }.setNegativeButton("CANCELAR",null).create()
  dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true}
  dialog.show()
 }
 private fun showMatchedTicketConsultation(saleId:String,rows:List<JSONObject>){
  val first=rows.firstOrNull()?:return
  val body=buildString{
   append("VENTA ORIGINAL IDENTIFICADA\n").append(first.optString("time","Fecha no disponible"))
   append("\nFolio: ").append(saleId).append("\n\nPRODUCTOS\n")
   for(row in rows)append("• ").append(row.optString("name","Producto"))
    .append(" · Código: ").append(row.optString("code"))
    .append(" · Cantidad: ").append(row.optInt("qty"))
    .append(" · Importe: ").append(Store.money(row.optDouble("subtotal"))).append("\n")
   append("\nTOTAL: ").append(Store.money(first.optDouble("saleTotal",rows.sumOf{it.optDouble("subtotal",0.0)})))
   append("\nPago recibido: ").append(Store.money(first.optDouble("amountPaid")))
   append("\nCambio: ").append(Store.money(first.optDouble("change")))
   append("\n\nConsulta de solo lectura. No se modificó la venta.")
  }
  AlertDialog.Builder(this).setTitle("CONSULTAR TICKET").setMessage(body).setPositiveButton("CERRAR",null).show()
 }
 private fun showMatchedTicketVerification(saleId:String,rows:List<JSONObject>,scanned:ArrayList<String>){
  val scannedSet=scanned.map{it.trim()}.toSet()
  val list=LinearLayout(this).apply{orientation=1;setPadding(dp(14),dp(8),dp(14),dp(8))}
  list.addView(txt("Folio: "+saleId+"\nMarca los renglones que verificaste físicamente.",14f))
  val checks=mutableListOf<CheckBox>()
  for((index,row) in rows.withIndex()){
   val codeValue=row.optString("code")
   val matched=codeValue in scannedSet
   val check=CheckBox(this).apply{
    text=(index+1).toString()+". "+row.optString("name")+" · "+row.optInt("qty")+" pza.\nCódigo: "+codeValue+" · "+if(matched)"CÓDIGO ENCONTRADO" else "NO ENCONTRADO"
    isChecked=matched;textSize=14f;setTextColor(if(matched)0xFF1D6B3A.toInt() else 0xFFB3261E.toInt())
    setPadding(dp(4),dp(8),dp(4),dp(8))
   }
   checks.add(check);list.addView(check)
  }
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(list)}
  AlertDialog.Builder(this).setTitle("VERIFICAR TICKET").setView(scroll)
   .setPositiveButton("CONFIRMAR"){_,_->
    val pending=checks.count{!it.isChecked}
    msg(if(pending==0)"Verificación completada para el folio "+saleId else "Verificación incompleta: "+pending+" renglón(es) quedaron sin confirmar.")
   }.setNegativeButton("CERRAR",null).show()
 }
 private fun showScannedTicketCorrectionForm(saleId:String,rows:List<JSONObject>){
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(14),dp(8),dp(14),dp(8))}
  box.addView(txt("Venta original: "+saleId+"\nMarca únicamente los productos que necesitan corregirse.",14f))
  val checks=mutableListOf<Pair<String,CheckBox>>()
  for(row in rows){
   val codeValue=row.optString("code")
   val cb=CheckBox(this).apply{
    text=row.optString("name","Producto")+" · "+row.optInt("qty")+" pza. · "+Store.money(row.optDouble("subtotal"))+"\nCódigo: "+codeValue
    textSize=14f;setTextColor(0xFF26365D.toInt());setPadding(dp(4),dp(7),dp(4),dp(7))
   }
   checks.add(codeValue to cb);box.addView(cb)
  }
  val reason=EditText(this).apply{
   hint="Escribe el motivo de la corrección";textSize=15f;minLines=3;maxLines=5
   gravity=Gravity.TOP or Gravity.START;setPadding(dp(14),dp(12),dp(14),dp(12))
   setTextColor(0xFF17213D.toInt());background=rounded(0xFFFFFFFF.toInt(),14,0xFFDCE2EF.toInt())
   filters=arrayOf(android.text.InputFilter.LengthFilter(500))
  }
  val reasonParams=LinearLayout.LayoutParams(-1,-2);reasonParams.topMargin=dp(12);box.addView(reason,reasonParams)
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(box)}
  AlertDialog.Builder(this).setTitle("SOLICITAR CORRECCIÓN").setView(scroll)
   .setPositiveButton("PREPARAR SOLICITUD"){_,_->
    val selected=checks.filter{it.second.isChecked}.map{it.first}.toSet()
    val detail=reason.text.toString().trim()
    if(selected.isEmpty()){msg("Marca al menos un producto que necesite corrección.");return@setPositiveButton}
    if(detail.isBlank()){msg("Escribe el motivo de la corrección.");return@setPositiveButton}
    val summary=rows.firstOrNull()?.optString("time","Venta original")+" · Folio "+saleId
    startActivity(Intent(this,WorkerPortalActivity::class.java).apply{putExtra("ticket_correction_request",true);putExtra("ticket_sale_id",saleId);putExtra("ticket_sale_summary",summary);putExtra("ticket_correction_detail",detail);putStringArrayListExtra("ticket_selected_codes",ArrayList(selected))})
   }.setNegativeButton("CANCELAR",null).show()
 }
 private fun handleTicketScan(codes:ArrayList<String>){
  val match=identifyOriginalTicket(codes)?:return
  showTicketActionPicker(match.first,match.second,codes)
 }
 private fun draw(){
  val v=page("VENTAS"){showToolsMenu()}
  val sales=Store.sales(this);var sum=0.0;var count=0
  for(i in 0 until sales.length()){val x=sales.optJSONObject(i)?:continue;if(x.optString("time").startsWith(day())){sum+=x.optDouble("subtotal");count++}}
  val shift=Store.shift(this)?:JSONObject()
  val startTs=shift.optLong("startTs",0L)
  var shiftSales=0.0
  for(i in 0 until sales.length()){val item=sales.optJSONObject(i)?:continue;if(startTs>0L&&item.optLong("ts",0L)>=startTs)shiftSales+=item.optDouble("subtotal",0.0)}
  val opening=shift.optDouble("cash",0.0)
  val expenses=try{Store.providerExpenses(this,startTs)}catch(_:Exception){0.0}
  val cashNow=(opening+shiftSales-expenses).coerceAtLeast(0.0)
  v.addView(txt("HOY\nDINERO EN CAJA: "+Store.money(cashNow)+"\nVENTAS DE HOY: "+Store.money(sum)+"  ·  $count ventas",16f).apply{
   gravity=Gravity.CENTER;setPadding(dp(18),dp(18),dp(18),dp(18))
   background=rounded(0xFFE8F0FF.toInt(),20,0xFFD5E1F5.toInt())
  })
  v.addView(actionCard("Escanear producto","Agregar artículos al ticket de venta","📷",0xFFE8F7EF.toInt()){openScanner()})
  v.addView(txt("CÓDIGO DE PRODUCTO",13f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF49618B.toInt())})
  code=input("Código de barras");code.imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
  code.setOnEditorActionListener{_,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){addCode(code.text.toString());code.setText("");true}else false}
  v.addView(code)
  ticketView=txt("Ticket vacío",18f);v.addView(ticketView)
  v.addView(txt("PAGO DEL CLIENTE",13f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF49618B.toInt())})
  paid=input("¿Cuánto te pagan?",true);v.addView(paid)
  totalView=txt("TOTAL: $0.00\nCambio: $0.00 | Falta: $0.00",22f);v.addView(totalView)
  paid.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,d:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){refresh()};override fun afterTextChanged(s:android.text.Editable?) {}})
  sell=button("COBRAR"){sellTicket()};v.addView(sell)
  countdownView=txt("",13f).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD);setPadding(dp(8),dp(9),dp(8),dp(9))}
  v.addView(countdownView)
  v.addView(actionCard("Tickets","Escanear un ticket para verificarlo o solicitar corrección","🎟️",0xFFFFF3E5.toInt()){pendingTicketScan=true;openScanner()})
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
    for(i in codes.indices){checklistScannedCodes.add(codes[i]);if(boxes[i].isChecked){selected++;addCode(codes[i])}else checklistUncheckedCodes.add(codes[i])}
    if(selected==0)msg("No seleccionaste productos verificados")
   }
   .setNegativeButton("Cancelar",null)
   .show()
 }
 private fun offerSupplierInventory(codeValue:String, productName:String?){
  val title=if(productName==null)"Producto no registrado" else "Producto sin existencia"
  val message=if(productName==null)
   "El código "+codeValue+" no está registrado en el inventario. ¿Deseas agregarlo ahora?"
   else
   productName+" no tiene existencia disponible. ¿Deseas agregar la cantidad que llegó del proveedor?"
  AlertDialog.Builder(this).setTitle(title).setMessage(message)
   .setPositiveButton("SÍ"){_,_->
    startActivity(Intent(this,AgregarInventarioActivity::class.java).putExtra("prefill_code",codeValue).putExtra("from_sales_missing",true))
   }.setNegativeButton("NO",null).show()
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
 private fun refresh(){
  if(!::ticketView.isInitialized)return
  val p=Store.products(this);val b=StringBuilder();var sum=0.0
  for((c,q) in ticket)for(i in 0 until p.length()){
   val x=p.optJSONObject(i)?:continue
   if(x.optString("code")==c){val sub=x.optDouble("price")*q;sum+=sub;b.append(x.optString("name")).append(" x").append(q).append(" = ").append(Store.money(sub)).append("\n");break}
  }
  ticketView.text=if(b.isEmpty())"Ticket vacío" else b.toString()
  val entered=parseAmount(paid.text.toString())
  val canPay=ticket.isNotEmpty()&&entered!=null&&entered>=sum
  val diff=(entered?:0.0)-sum
  totalView.text="TOTAL: "+Store.money(sum)+"\n"+if(diff>=0)"Cambio a dar: "+Store.money(diff) else "Falta: "+Store.money(-diff)
  if(::sell.isInitialized){
   sell.text="COBRAR"
   sell.background=rounded(if(canPay)0xFF1769D2.toInt() else 0xFFD92727.toInt(),14)
   sell.setTextColor(0xFFFFFFFF.toInt())
  }
  if(::paid.isInitialized){
   if(canPay){paidValidationError=false;paid.error=null}
   paid.background=rounded(0xFFFFFFFF.toInt(),14,if(paidValidationError)0xFFD92727.toInt() else 0xFFDCE2EF.toInt())
  }
}
 private fun sellTicket(){if(due()){showReportAlert();return};if(ticket.isEmpty()){msg("Ticket vacio. Escanea un producto.");return}
 val p=Store.products(this);val list=mutableListOf<Pair<JSONObject,Int>>();var sum=0.0
 for((c,q) in ticket){var found:JSONObject?=null;for(i in 0 until p.length()){val x=p.optJSONObject(i);if(x?.optString("code")==c){found=x;break}};if(found==null){msg("Producto no encontrado: "+c);return};if(found.optInt("stock")<q){msg("Stock insuficiente: "+found.optString("name")+". Quedan "+found.optInt("stock"));return};list.add(found to q);sum+=found.optDouble("price")*q}
 val pay=parseAmount(paid.text.toString());if(pay==null||pay<sum){paidValidationError=true;paid.background=rounded(0xFFFFFFFF.toInt(),14,0xFFD92727.toInt());paid.requestFocus();paid.error="Escribe cuánto te pagan";sell.background=rounded(0xFFD92727.toInt(),14);sell.setTextColor(0xFFFFFFFF.toInt());msg("Primero escribe cuánto te paga el cliente. El botón COBRAR se habilitará en azul cuando la cantidad sea suficiente.");return};val s=Store.shift(this)?:JSONObject()
 val saleTime=stamp();val saleTs=System.currentTimeMillis();val saleId=UUID.randomUUID().toString();val saleItems=ArrayList<JSONObject>()
 for((x,q) in list){x.put("stock",x.optInt("stock")-q);saleItems.add(JSONObject().put("saleId",saleId).put("ts",saleTs).put("time",saleTime).put("code",x.optString("code")).put("name",x.optString("name")).put("qty",q).put("price",x.optDouble("price")).put("subtotal",x.optDouble("price")*q).put("worker",s.optString("worker")).put("saleTotal",sum).put("amountPaid",pay).put("change",pay-sum).put("saleItemCount",list.size).put("checklistScannedCount",checklistScannedCodes.size).put("checklistCheckedCount",checklistScannedCodes.size-checklistUncheckedCodes.size).put("checklistStatus",if(checklistScannedCodes.isEmpty())"NO_REALIZADO" else if(checklistUncheckedCodes.isEmpty())"COMPLETO" else "INCOMPLETO").put("checklistComplete",checklistScannedCodes.isNotEmpty()&&checklistUncheckedCodes.isEmpty()).put("checklistScannedCodes",JSONArray(checklistScannedCodes)).put("checklistUncheckedCodes",JSONArray(checklistUncheckedCodes)))}
 if(!Store.recordSale(this,p,saleItems)){msg("Esta venta ya quedó registrada o no se pudo validar su folio. Revisa Tickets antes de intentarlo de nuevo.");return};val change=pay-sum;val n=list.size;ticket.clear();checklistScannedCodes.clear();checklistUncheckedCodes.clear();paid.setText("");msg("Venta guardada: "+n+" productos | Total "+Store.money(sum)+" | Cambio "+Store.money(change));draw()}
 private fun reportText(final:Boolean):String{
  val s=Store.shift(this)?:JSONObject()
  val start=s.optLong("startTs",0L)
  val end=s.optLong("closingTs",0L).takeIf{it>0L}?:System.currentTimeMillis()
  val formatter=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX"))
  val opening=s.optDouble("cash",Double.NaN)
  check(opening.isFinite()&&opening>=0.0){"La caja inicial es inválida; no se puede generar el reporte."}
  val all=Store.sales(this)
  val products=Store.products(this)
  val stockByCode=HashMap<String,Int>()
  for(i in 0 until products.length()){
   val product=products.optJSONObject(i)?:throw IllegalStateException("Hay un producto dañado; no se puede generar el reporte.")
   stockByCode[product.optString("code")]=product.optInt("stock")
  }
  val b=StringBuilder("SENE VENTAS - "+if(final)"CIERRE DE TURNO" else "REPORTE DE TURNO")
  var sum=0.0
  b.append("\nTRABAJADOR: ").append(s.optString("worker","Sin nombre"))
   .append("\nTELÉFONO: ").append(s.optString("phone","No registrado"))
   .append("\nCOMENZÓ EL TURNO: ").append(if(start>0L)formatter.format(Date(start)) else s.optString("started","No registrado"))
   .append("\nFINALIZÓ EL TURNO: ").append(if(final)formatter.format(Date(end)) else "Turno en curso · "+stamp())
   .append("\nCAJA INICIAL: ").append(Store.money(opening))
   .append("\n\nVENTAS DEL TURNO")
   .append("\n").append("PRODUCTO".padEnd(24)).append("HORA DE VENTA".padEnd(21))
   .append("CANT.".padEnd(7)).append("STOCK".padEnd(8)).append("IMPORTE")
  var saleCount=0
  for(i in 0 until all.length()){
   val x=all.optJSONObject(i)?:throw IllegalStateException("Hay una venta dañada en el historial; no se puede generar el reporte.")
   val ts=x.optLong("ts",0L)
   if(ts>=start && ts<=end){
    val amount=x.optDouble("subtotal",Double.NaN)
    val qty=x.optInt("qty",0)
    check(amount.isFinite()&&amount>=0.0&&qty>0){"Hay una venta con importe o cantidad inválidos; no se puede generar el reporte."}
    sum+=amount
    check(sum.isFinite()){"El total de ventas no es válido."}
    val name=x.optString("name","Producto").take(23)
    val time=x.optString("time").ifBlank{if(ts>0L)formatter.format(Date(ts)) else "Sin hora"}.take(20)
    val stock=stockByCode[x.optString("code")]?.toString()?: "N/D"
    b.append("\n").append(name.padEnd(24)).append(time.padEnd(21))
     .append(qty.toString().padEnd(7)).append(stock.padEnd(8)).append(Store.money(amount))
    saleCount++
   }
  }
  if(saleCount==0)b.append("\nSin ventas registradas en este turno.")
  val expenses=Store.providerExpenses(this,start)
  val cash=opening+sum-expenses
  check(cash.isFinite()&&cash>=0.0){"El saldo de cierre no es válido; revisa ventas y pagos."}
  b.append("\n\nTOTAL DE VENTAS: ").append(Store.money(sum))
   .append("\nPAGOS A PROVEEDORES: ").append(Store.money(expenses))
   .append("\nCAJA FINAL ESTIMADA: ").append(Store.money(cash))
   .append("\n\nEXISTENCIAS ACTUALES")
   .append("\n").append("CÓDIGO".padEnd(16)).append("PRODUCTO".padEnd(28)).append("PRECIO".padEnd(12)).append("STOCK")
  for(i in 0 until products.length()){
   val x=products.optJSONObject(i)?:throw IllegalStateException("Hay un producto dañado; no se puede generar el reporte.")
   b.append("\n").append(x.optString("code").take(15).padEnd(16))
    .append(x.optString("name").take(27).padEnd(28))
    .append(Store.money(x.optDouble("price")).padEnd(12)).append(x.optInt("stock"))
  }
  return b.toString()
 }
 private fun requestCloseShift(){
  val shift=Store.shift(this)?:return
  val start=shift.optLong("startTs",0L)
  if(start<=0L){msg("No se puede cerrar el turno porque falta una hora de inicio válida.");return}
  val allowedAt=start+8L*60L*60L*1000L
  val remaining=allowedAt-System.currentTimeMillis()
  if(remaining>0L){
   val seconds=(remaining+999L)/1000L
   val hours=seconds/3600L
   val minutes=(seconds%3600L)/60L
   val secs=seconds%60L
   AlertDialog.Builder(this).setTitle("AÚN NO PUEDES FINALIZAR")
    .setMessage("El turno debe cumplir 8 horas completas.\n\nPodrás finalizarlo a las: "+SimpleDateFormat("hh:mm a",Locale("es","MX")).format(Date(allowedAt))+"\n\nTiempo restante: "+String.format(Locale.US,"%02d:%02d:%02d",hours,minutes,secs))
    .setPositiveButton("ENTENDIDO",null).show()
   return
  }
  AlertDialog.Builder(this).setTitle("Cerrar turno")
   .setMessage("Ya se cumplieron las 8 horas de trabajo. Primero se enviará al servidor el inventario y reporte final una sola vez; después se abrirá el chat de WhatsApp del patrón con el reporte en texto. El respaldo completo quedará guardado como TXT. ¿Deseas continuar?")
   .setPositiveButton("ENVIAR Y CERRAR"){_,_->sendReport(true)}
   .setNegativeButton("CANCELAR",null).show()
 }
 private fun sendReport(final:Boolean){
  if(Store.shift(this)==null)return
  if(final){
   val start=Store.shift(this)?.optLong("startTs",0L)?:0L
   val allowedAt=start+8L*60L*60L*1000L
   if(start<=0L||System.currentTimeMillis()<allowedAt){
    msg("No puedes finalizar el turno antes de completar 8 horas de trabajo.")
    return
   }
  }
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
 private fun completeReport(final:Boolean,tree:Uri){
  val s=Store.shift(this)?:return
  if(final){
   val start=s.optLong("startTs",0L)
   if(start<=0L||System.currentTimeMillis()<start+8L*60L*60L*1000L){
    msg("No puedes finalizar el turno antes de completar 8 horas de trabajo.")
    return
   }
  }
  val original=try{JSONObject(s.toString())}catch(_:Exception){s}
  var closeCommitted=false
  var dailyUri:Uri?=null
  var dailyCleanupFailed=false
  try{
   if(final && s.optLong("closingTs",0L)==0L){
    s.put("closingTs",System.currentTimeMillis()).put("closed",stamp())
    Store.saveShift(this,s)
    closeCommitted=true
   }
   val file=ReportFile.update(this)
   val body=file.readText(Charsets.UTF_8)+REPORT_BACKUP_NOTE
   file.writeText(body,Charsets.UTF_8)
   dailyUri=saveDailyReport(tree,file)
   if(final){
    pendingShareUri=dailyUri
    pendingReportText=body
    pendingFinalShare=true
    pendingServerDelivery=true
    queueShiftReportForServer{
     pendingServerDelivery=false
     launchPendingReportShare()
    }
   }else{
    pendingShareUri=dailyUri
    pendingReportText=body
    pendingFinalShare=false
    launchPendingReportShare()
   }
  }catch(e:Exception){
   dailyUri?.let{uri->
    try{DocumentsContract.deleteDocument(contentResolver,uri)}
    catch(_:Exception){dailyCleanupFailed=true}
   }
   if(final && closeCommitted)try{Store.saveShift(this,original)}catch(_:Exception){}
   val cleanupNote=if(dailyCleanupFailed)" El proveedor de archivos no permitió borrar el TXT parcial; revisa la carpeta del día antes de reintentar." else ""
   msg("No se pudo completar el reporte. El turno permanece abierto y las ventas no se reactivaron: "+(e.message?:"error")+cleanupNote)
  }
 }
 private fun launchPendingReportShare(){
  // El TXT ya está guardado localmente/en INVENTARIO SENE. WhatsApp recibe
  // únicamente el texto; nunca se adjunta ni se comparte el archivo.
  if(pendingShareUri==null)return
  val body=pendingReportText?:return
  if(!openWhatsAppText(this,body)){
   msg("El reporte TXT quedó guardado en INVENTARIO SENE, pero no se pudo abrir el chat de WhatsApp. Puedes volver a intentarlo.")
  }
 }
 private fun confirmReportSent(){
  if(confirmingReport||pendingShareUri==null)return
  confirmingReport=true
  AlertDialog.Builder(this).setTitle("Confirmar envío del reporte")
   .setMessage("¿Ya enviaste el texto del reporte por WhatsApp? El respaldo TXT completo está guardado por separado en INVENTARIO SENE. Las ventas seguirán bloqueadas hasta que confirmes.")
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
  try{
   resolver.openOutputStream(target,"w")?.use{out->source.inputStream().use{it.copyTo(out)}}?:throw java.io.IOException("No se pudo escribir el TXT")
   return target
  }catch(e:Exception){
   try{
    if(!DocumentsContract.deleteDocument(resolver,target)){
     throw java.io.IOException("El proveedor de archivos rechazó la eliminación del TXT parcial")
    }
   }catch(cleanup:Exception){
    val failure=java.io.IOException("No se pudo escribir el TXT y tampoco se pudo eliminar el archivo parcial; revisa la carpeta del día.",e)
    failure.addSuppressed(cleanup)
    throw failure
   }
   throw e
  }
 }
 private fun stock(){
  val products=Store.products(this)
  val sorted=ArrayList<JSONObject>()
  for(i in 0 until products.length())products.optJSONObject(i)?.let{sorted.add(it)}
  sorted.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.optString("name")})
  val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8))}
  if(sorted.isEmpty())list.addView(txt("Sin productos registrados"))
  for(product in sorted){
   val row=TextView(this).apply{
    text=product.optString("name")+"  ·  Stock: "+product.optInt("stock")+
      "\nCódigo: "+product.optString("code","Sin código")+
      "\nPrecio: "+Store.money(product.optDouble("price"))
    textSize=15f;setTextColor(0xFF202B49.toInt());setPadding(dp(13),dp(11),dp(13),dp(11))
    background=rounded(0xFFFFFFFF.toInt(),12,0xFFE2E6F0.toInt())
    setLineSpacing(dp(2).toFloat(),1f)
   }
   val params=LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(7);list.addView(row,params)
  }
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(list)}
  AlertDialog.Builder(this).setTitle("STOCK ACTUAL · ORDEN ALFABÉTICO")
   .setView(scroll).setPositiveButton("CERRAR",null).show()
 }
 private fun saveReportToPhone(){val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/plain";putExtra(Intent.EXTRA_TITLE,"SENE-VENTAS-REPORTE.txt")};try{startActivityForResult(intent,SAVE_REPORT_REQUEST)}catch(_:Exception){msg("No se pudo abrir el selector para guardar")}}
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==TREE_REPORT_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();completeReport(pendingFinalReport,tree)}catch(e:Exception){msg("No se pudo guardar el acceso a la carpeta: "+(e.message?:"error"))};return};if(r==SAVE_REPORT_REQUEST&&c==RESULT_OK){val uri=d?.data?:return;try{val file=ReportFile.update(this);contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}};msg("Reporte TXT guardado en la ubicación que elegiste")}catch(e:Exception){msg("No se pudo guardar el TXT: "+(e.message?:"error"))};return};if(r==SCAN_REQUEST){if(c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty()){if(pendingPairingScan){pendingPairingScan=false;finishVentasPairing(codes.last())}else if(pendingTicketScan){pendingTicketScan=false;handleTicketScan(codes)}else reviewScannedCodes(codes)}else if(pendingTicketScan){pendingTicketScan=false;msg("No se escanearon códigos.")}}else{pendingPairingScan=false;pendingTicketScan=false};return};val x=IntentIntegrator.parseActivityResult(r,c,d);if(x?.contents!=null)addCode(x.contents)}
}

class AgregarInventarioActivity:Activity(){
 private lateinit var code:EditText;private lateinit var name:EditText;private lateinit var price:EditText;private lateinit var qty:EditText;private lateinit var providerPayment:EditText;private var existing:JSONObject?=null
 private val fromSalesMissing by lazy{intent.getBooleanExtra("from_sales_missing",false)}
 override fun onCreate(b:Bundle?){super.onCreate(b);draw();intent.getStringExtra("prefill_code")?.takeIf{it.isNotBlank()}?.let{code.setText(it);find()}}
 private lateinit var deviceClock:TextView;private lateinit var internetClock:TextView;private val clockHandler=Handler(Looper.getMainLooper());private val clockTick=object:Runnable{override fun run(){if(::deviceClock.isInitialized)deviceClock.text="Hora del celular: "+stamp();clockHandler.postDelayed(this,1000)}}
 override fun onResume(){super.onResume();if(!fromSalesMissing&&::deviceClock.isInitialized)clockHandler.post(clockTick)}
 override fun onPause(){clockHandler.removeCallbacks(clockTick);super.onPause()}
 private fun consultProviderInternetTime(){if(!::internetClock.isInitialized)return;internetClock.text="Hora de Internet: consultando…";Thread{val value=internetTimeMillis();val text=value?.let{SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(it))}?: "No disponible";runOnUiThread{if(::internetClock.isInitialized)internetClock.text="Hora de Internet: "+text}}.start()}
 private fun draw(){
  val v=page(if(fromSalesMissing)"Agregar inventario" else "Agregar Inventario - Llegó proveedor")
  if(!fromSalesMissing){
   deviceClock=txt("Hora del celular: "+stamp(),14f);internetClock=txt("Hora de Internet: consultando…",14f)
   v.addView(deviceClock);v.addView(internetClock)
   val cashLabel=try{"CAJA DISPONIBLE: "+Store.money(Store.cashBalance(this))}catch(e:Exception){"CAJA NO DISPONIBLE: revisa el historial"}
   v.addView(txt(cashLabel,17f))
   v.addView(actionCard("Actualizar hora de Internet","Consultar la hora oficial disponible","🌐",0xFFEAF7F7.toInt()){consultProviderInternetTime()})
  }
  v.addView(txt("DATOS DEL PRODUCTO",13f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF49618B.toInt())})
  code=input("Código del producto");name=input("Nombre del producto")
  qty=input(if(fromSalesMissing)"Cantidad para agregar al inventario" else "Cantidad que llegó del proveedor",true)
  price=input("Precio de venta $",true)
  providerPayment=input("¿Cuánto pagarás al proveedor? $",true)
  v.addView(code)
  if(!fromSalesMissing){
   v.addView(actionCard("Escanear código","Leer el código de barras del producto","📷",0xFFE8F7EF.toInt()){openScanner()})
   v.addView(actionCard("Buscar producto","Comprobar si ya existe en el inventario","🔎",0xFFEAF2FF.toInt()){find()})
  }
  v.addView(name);v.addView(qty);v.addView(price)
  if(!fromSalesMissing){v.addView(providerPayment);v.addView(txt("El pago se descontará de la caja al guardar. Si no pagarás ahora, escribe 0.",13f))}
  v.addView(actionCard(if(fromSalesMissing)"Agregar inventario" else "Continuar y confirmar",if(fromSalesMissing)"Guardar el producto y aumentar la existencia" else "Revisar cantidad recibida y pago al proveedor","✓",0xFFFFF3E5.toInt()){confirm()})
  v.addView(actionCard("Volver","Regresar a ventas sin guardar cambios","↩️",0xFFF1F3F8.toInt()){finish()})
  display(v);if(!fromSalesMissing)consultProviderInternetTime()
 }
 private fun find(){existing=null;val p=Store.products(this);for(i in 0 until p.length()){val x=p.optJSONObject(i)?:continue;if(x.optString("code")==code.text.toString())existing=x};if(existing!=null){name.setText(existing!!.optString("name"));price.setText(existing!!.optDouble("price").toString());price.isEnabled=false;msg("Producto existente. El precio no se puede cambiar.")}else{price.isEnabled=true;name.setText("");price.setText("");msg("Producto nuevo. Captura nombre y precio.")}}
 private fun confirm(){
  val q=qty.text.toString().toIntOrNull()
  val payment=if(fromSalesMissing)0.0 else if(providerPayment.text.isBlank())0.0 else parseAmount(providerPayment.text.toString())
  val cash=if(fromSalesMissing)0.0 else try{Store.cashBalance(this)}catch(e:Exception){msg("No se puede validar el pago porque la caja tiene movimientos inválidos. Revisa el historial.");return}
  if(code.text.isBlank()||q==null||q<1){msg("Revisa el código y la cantidad");return}
  if(payment==null||!payment.isFinite()||payment<0){msg("Captura un pago válido");return}
  if(!fromSalesMissing&&payment>cash){msg("El pago supera la caja disponible ("+Store.money(cash)+"). No se puede registrar.");return}
  val parsedPrice=parseAmount(price.text.toString())
  if(existing==null&&(name.text.isBlank()||parsedPrice==null||parsedPrice<0)){msg("Captura nombre y precio válidos");return}
  val old=existing?.optInt("stock")?:0
  val summary=if(fromSalesMissing)
   "Código: "+code.text+"\nProducto: "+name.text+"\nCantidad a agregar: "+q+"\nExistencia nueva: "+(old+q)+"\nPrecio: "+price.text+"\n\n¿Confirmas agregarlo al inventario?"
  else
   "Código: "+code.text+"\nDescripción: "+name.text+"\nPrecio: "+price.text+"\nStock actual: "+old+"\nCantidad que llegó: "+q+"\nStock nuevo: "+(old+q)+"\n\nPago al proveedor: "+Store.money(payment)+"\nCaja antes del pago: "+Store.money(cash)+"\nCaja después del pago: "+Store.money(cash-payment)+"\n\nASEGÚRATE DE QUE LA CANTIDAD Y EL PAGO SEAN CORRECTOS. ESTA ACCIÓN NO SE PUEDE DESHACER."
  AlertDialog.Builder(this).setTitle(if(fromSalesMissing)"Confirmar agregar inventario" else "Confirmación 1 de 2").setMessage(summary)
   .setPositiveButton(if(fromSalesMissing)"AGREGAR" else "Sí, la cantidad es correcta"){_,_->
    if(fromSalesMissing)save(q,0.0)
    else AlertDialog.Builder(this).setTitle("¿Estás 100% seguro?").setMessage("Última oportunidad para revisar. Si guardas, ya no se puede deshacer.").setPositiveButton("SÍ, GUARDAR - YA REVISÉ"){_,_->save(q,payment)}.setNegativeButton("NO, ME EQUIVOQUÉ - REVISAR",null).show()
   }.setNegativeButton(if(fromSalesMissing)"CANCELAR" else "No, revisar cantidad",null).show()
 }
 private fun presentCode(products:JSONArray,codeValue:String):Boolean{for(i in 0 until products.length())if(products.optJSONObject(i)?.optString("code")==codeValue)return true;return false}
 private fun save(q:Int,payment:Double){
  if(!payment.isFinite()||payment<0.0){msg("El pago no es válido. No se guardó.");return}
  val currentCash=if(fromSalesMissing)0.0 else try{Store.cashBalance(this)}catch(e:Exception){msg("No se puede validar el pago porque la caja tiene movimientos inválidos. No se guardó el ingreso.");return}
  if(!fromSalesMissing&&payment>currentCash){msg("La caja cambió y ya no alcanza para ese pago. Revisa el monto.");return}
  val p=Store.products(this);val label=name.text.toString().trim()
  if(existing!=null){
   val index=(0 until p.length()).firstOrNull{p.optJSONObject(it)?.optString("code")==existing!!.optString("code")}?:run{msg("El producto ya no existe. No se guardó.");return}
   p.optJSONObject(index)!!.put("stock",p.optJSONObject(index)!!.optInt("stock")+q)
  }else{
   if(presentCode(p,code.text.toString().trim())){msg("Ese código ya existe. Busca el producto antes de agregar.");return}
   val parsedPrice=parseAmount(price.text.toString())?:run{msg("El precio no es válido.");return}
   p.put(JSONObject().put("code",code.text.toString().trim()).put("name",label).put("price",parsedPrice).put("stock",q))
  }
  val backup=StringBuilder("INVENTARIO_SENE_V1\nActualizado: ").append(stamp()).append("\nCódigo | Producto | Precio | Existencia")
  for(i in 0 until p.length()){val item=p.optJSONObject(i)?:continue;backup.append("\n").append(item.optString("code")).append(" | ").append(item.optString("name")).append(" | ").append(item.optDouble("price")).append(" | ").append(item.optInt("stock"))}
  var savedInSharedFolder=false
  val tree=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(!tree.isNullOrBlank())try{savedInSharedFolder=InventoryArchive.save(this,p)}catch(_:Exception){}
  try{if(!savedInSharedFolder)saveTextBackupLocal(this,if(fromSalesMissing)"SENE-ALTA-INVENTARIO" else "SENE-INGRESO-PROVEEDOR",backup.toString())}
  catch(e:Exception){msg("No se pudo crear el respaldo local: "+(e.message?:"error"));return}
  Store.saveProducts(this,p)
  val s=Store.shift(this)?:JSONObject();val now=System.currentTimeMillis();val time=stamp()
  Store.addMove(this,JSONObject().put("ts",now).put("time",time).put("text",(if(fromSalesMissing)"ALTA DESDE VENTAS: +" else "INGRESO DE PROVEEDOR: +")+q+" "+label+" por "+s.optString("worker")))
  if(payment>0.0)Store.addMove(this,JSONObject().put("type","PROVIDER_PAYMENT").put("ts",now).put("time",time).put("amount",payment).put("supplier",label).put("text","PAGO A PROVEEDOR por ingreso de "+q+" "+label+" | "+Store.money(payment)).put("worker",s.optString("worker")))
  val where=if(savedInSharedFolder)"INVENTARIO SENE actualizado" else "respaldo guardado en este teléfono; el reporte de turno incluirá el stock actualizado"
  val cashAfter=if(fromSalesMissing)null else try{Store.money(Store.cashBalance(this))}catch(e:Exception){null}
  msg(if(fromSalesMissing)"Producto agregado al inventario correctamente. "+where else "Ingreso guardado. "+(if(payment>0.0)"Pago descontado de caja: "+Store.money(payment)+" | " else "")+(cashAfter?.let{"Caja actual: "+it+" | "}?:"Caja no disponible: revisa el historial | ")+where)
  finish()
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==SCAN_REQUEST&&c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty()){code.setText(codes.last());find()};return};val x=IntentIntegrator.parseActivityResult(r,c,d);if(x?.contents!=null){code.setText(x.contents);find()}}
}

class PatronActivity:Activity(){
 private var pendingInventorySave=false
 override fun onCreate(b:Bundle?){super.onCreate(b);pin{draw()}}
 private fun draw(){
  val v=page("Administrador - Patrón")
  v.addView(actionCard("Hacer primer inventario","Registrar productos, cantidades y precios iniciales","📦",0xFFE8F7EF.toInt()){openFirstInventory()})
  v.addView(actionCard("Agregar o modificar producto","Ajustar existencias o precio mediante escáner","🛠️",0xFFEAF2FF.toInt()){product()})
  v.addView(actionCard("Cambiar número de WhatsApp","Actualizar el número del patrón para reportes","📱",0xFFEAF7F7.toInt()){
   val e=input("Número con código de país");e.setText(Store.phone(this))
   AlertDialog.Builder(this).setTitle("WhatsApp para reportes").setView(e)
    .setPositiveButton("Guardar"){_,_->Store.setPhone(this,e.text.toString().filter{it.isDigit()});msg("Número guardado")}
    .setNegativeButton("Cancelar",null).show()
  })
  v.addView(actionCard("Ver inventario actual","Consultar productos, precios y existencias","🗃️",0xFFF2ECFF.toInt()){inventory()})
  v.addView(actionCard("Guardar TXT del stock","Crear una copia del inventario","📄",0xFFFFF3E5.toInt()){export()})
  v.addView(actionCard("Cargar inventario desde TXT","Importar un inventario guardado","📂",0xFFEAF2FF.toInt()){chooseInventoryFolder()})
  v.addView(actionCard("Volver a ventas","Regresar a la pantalla de ventas","↩️",0xFFF1F3F8.toInt()){finish()})
  display(v)
 }
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
  root.addView(code);root.addView(actionCard("Escanear producto","Leer el código de barras","📷",0xFFE8F7EF.toInt()){openScanner()});root.addView(name);root.addView(qty);root.addView(price)
  val list=LinearLayout(this).apply{orientation=1};val count=txt("Productos agregados: 0",15f);root.addView(count);root.addView(list)
  root.addView(actionCard("Agregar producto","Validar y añadir este producto a la lista","➕",0xFFEAF2FF.toInt()){
   val q=qty.text.toString().toIntOrNull();val p=parseAmount(price.text.toString())
   if(code.text.isBlank()||name.text.isBlank()||q==null||q<0||p==null||p<0){msg("Captura código, descripción, cantidad y precio válidos");return@actionCard}
   for(i in 0 until firstInventoryItems.length())if(firstInventoryItems.optJSONObject(i)?.optString("code")==code.text.toString().trim()){msg("Ese código ya está en la lista");return@actionCard}
   firstInventoryItems.put(JSONObject().put("code",code.text.toString().trim()).put("name",name.text.toString().trim()).put("stock",q).put("price",p))
   list.addView(txt(code.text.toString()+" | "+name.text.toString()+"\nCantidad: "+q+"   Precio: "+Store.money(p),14f));count.text="Productos agregados: "+firstInventoryItems.length()
   code.setText("");name.setText("");qty.setText("");price.setText("");code.requestFocus()
  })
  root.addView(actionCard("Guardar inventario","Guardar todos los productos en INVENTARIO SENE","💾",0xFFFFF3E5.toInt()){
   if(firstInventoryItems.length()==0){msg("Agrega por lo menos un producto antes de guardar");return@actionCard}
   AlertDialog.Builder(this).setTitle("Guardar inventario").setMessage("Se guardarán "+firstInventoryItems.length()+" productos en INVENTARIO SENE con la fecha y hora actuales. ¿Continuar?")
    .setPositiveButton("GUARDAR"){_,_->try{if(InventoryArchive.save(this,firstInventoryItems)){Store.saveProducts(this,firstInventoryItems);getSharedPreferences("sene_ventas_local",0).edit().putBoolean("first_inventory_created",true).apply();msg("Inventario guardado correctamente en INVENTARIO SENE");draw()}else msg("No se pudo guardar. Verifica el acceso a INVENTARIO SENE.")}catch(e:Exception){msg("Error al guardar: "+(e.message?:"desconocido"))}}
    .setNegativeButton("Revisar",null).show()
  })
  root.addView(actionCard("Volver","Regresar al administrador","↩️",0xFFF1F3F8.toInt()){draw()});display(root)
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
 private fun inventory(){
  val products=Store.products(this)
  val root=LinearLayout(this).apply{orientation=1;setPadding(dp(16),dp(10),dp(16),dp(10))}
  val total=products.length()
  val units=(0 until total).sumOf{products.optJSONObject(it)?.optInt("stock")?:0}
  root.addView(txt("PRODUCTOS REGISTRADOS: "+total+"     UNIDADES: "+units,14f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF49618B.toInt())})
  val scroll=ScrollView(this)
  val list=LinearLayout(this).apply{orientation=1}
  val ordered=(0 until total).mapNotNull{products.optJSONObject(it)}.sortedBy{it.optString("name").lowercase(Locale("es","MX"))}
  if(ordered.isEmpty())list.addView(txt("Todavía no hay productos registrados.",15f))
  for(item in ordered){
   val card=LinearLayout(this).apply{orientation=1;setPadding(dp(14),dp(11),dp(14),dp(11));background=rounded(0xFFF3F6FC.toInt(),14)}
   card.addView(txt(item.optString("name","Producto"),16f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF17345F.toInt())})
   card.addView(txt("Código: "+item.optString("code"),13f))
   val detail=LinearLayout(this).apply{orientation=0;gravity=Gravity.CENTER_VERTICAL}
   detail.addView(txt("Precio: "+Store.money(item.optDouble("price")),14f).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)})
   detail.addView(txt("Existencia: "+item.optInt("stock"),14f).apply{typeface=Typeface.DEFAULT_BOLD;setTextColor(if(item.optInt("stock")>0)0xFF16834A.toInt() else 0xFFC62828.toInt())})
   card.addView(detail);list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
  }
  scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,dp(440)))
  AlertDialog.Builder(this).setTitle("Inventario actual").setView(root).setPositiveButton("CERRAR",null).show()
 }
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

private const val SCAN_REQUEST=7412
private const val SAVE_REPORT_REQUEST=7413
private const val TREE_REPORT_REQUEST=7414
private const val IMPORT_TREE_REQUEST=7415
private const val IMPORT_FILE_REQUEST=7418
private const val START_INVENTORY_TREE_REQUEST=7416
private const val INVENTORY_TREE_REQUEST=7417
private const val SCAN_CODES="scan_codes"
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

private fun openWhatsAppDocument(activity:Activity,uri:Uri,subject:String):Boolean {
 val share=Intent(Intent.ACTION_SEND).apply {
  type="text/plain"
  putExtra(Intent.EXTRA_STREAM,uri)
  putExtra(Intent.EXTRA_SUBJECT,subject)
  putExtra(Intent.EXTRA_TEXT,subject)
  clipData=android.content.ClipData.newUri(activity.contentResolver,subject,uri)
  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
 }
 for(pkg in listOf("com.whatsapp","com.whatsapp.w4b")){
  try{
   activity.startActivity(Intent(share).setPackage(pkg))
   return true
  }catch(_:Exception){}
 }
 return try{
  activity.startActivity(Intent.createChooser(share,"Enviar reporte TXT"))
  true
 }catch(_:Exception){false}
}

object ReportFile {
 @Synchronized fun update(c:android.content.Context, root:org.json.JSONObject?=null):java.io.File {
  val pref=c.getSharedPreferences("sene_ventas_local",0).getString("datos",null)
  val data=root ?: try { if(pref==null)org.json.JSONObject() else org.json.JSONObject(pref) }
   catch(e:Exception){throw IllegalStateException("Los datos locales están dañados; no se generó un reporte incompleto.",e)}
  val shift=data.optJSONObject("shift") ?: org.json.JSONObject()
  data.put("sales",Store.sales(c)).put("moves",Store.moves(c)).put("products",Store.products(c))
  val startTs=shift.optLong("startTs",0L)
  val opening=shift.optDouble("cash",Double.NaN)
  if(data.has("shift"))check(opening.isFinite()&&opening>=0.0){"La caja inicial es inválida; no se puede generar el reporte."}
  val out=StringBuilder("SENE-VENTAS - REPORTE DE MOVIMIENTOS\n")
  out.append("Actualizado: ").append(stamp()).append("\nTrabajador: ").append(shift.optString("worker","-"))
   .append("\nNúmero del trabajador: ").append(shift.optString("phone","-"))
   .append("\nInicio de turno: ").append(shift.optString("started","-"))
   .append("\nFecha y hora de cierre: ").append(shift.optString("closed","Turno en curso"))
   .append("\nÚltima actualización de stock: ").append(Store.stockUpdatedAtText(c))
   .append("\nCaja inicial: ").append(Store.money(if(data.has("shift"))opening else 0.0)).append("\n\nVENTAS\n")
  val sales=data.optJSONArray("sales")?:throw IllegalStateException("No se pudo leer el historial de ventas.")
  val products=data.optJSONArray("products")?:throw IllegalStateException("No se pudo leer el inventario.")
  val stockByCode=HashMap<String,Int>()
  for(i in 0 until products.length()){
   val product=products.optJSONObject(i)?:throw IllegalStateException("Hay un producto dañado; no se generó el reporte.")
   stockByCode[product.optString("code")]=product.optInt("stock")
  }
  out.append("PRODUCTO".padEnd(24)).append("HORA DE VENTA".padEnd(21))
   .append("CANT.".padEnd(7)).append("STOCK".padEnd(8)).append("IMPORTE\n")
  var total=0.0
  var saleCount=0
  for(i in 0 until sales.length()){
   val x=sales.optJSONObject(i)?:throw IllegalStateException("Hay una venta dañada; no se generó el reporte.")
   val ts=x.optLong("ts",0L)
   if(ts>=startTs){
    val subtotal=x.optDouble("subtotal",Double.NaN);val qty=x.optInt("qty",0)
    check(subtotal.isFinite()&&subtotal>=0.0&&qty>0){"Hay una venta con cantidad o subtotal inválido; revisa el historial."}
    total+=subtotal;check(total.isFinite()){"El total vendido no es válido."}
    val productName=x.optString("name","Producto").take(23)
    val saleTime=x.optString("time").ifBlank{if(ts>0L)java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",java.util.Locale("es","MX")).format(java.util.Date(ts)) else "Sin hora"}.take(20)
    val stock=stockByCode[x.optString("code")]?.toString()?: "N/D"
    out.append(productName.padEnd(24)).append(saleTime.padEnd(21))
     .append(qty.toString().padEnd(7)).append(stock.padEnd(8))
     .append(Store.money(subtotal)).append("\n")
    saleCount++
   }
  }
  if(saleCount==0)out.append("Sin ventas registradas en este turno.\n")
  val moves=data.optJSONArray("moves")?:throw IllegalStateException("No se pudo leer el historial de movimientos.")
  var providerTotal=0.0
  for(i in 0 until moves.length()){
   val x=moves.optJSONObject(i)?:throw IllegalStateException("Hay un movimiento dañado; no se generó el reporte.")
   if(x.optString("type")=="PROVIDER_PAYMENT"&&x.optLong("ts",0L)>=startTs){
    val amount=x.optDouble("amount",Double.NaN)
    check(amount.isFinite()&&amount>=0.0){"Hay un pago a proveedor inválido; no se generó el reporte."}
    providerTotal+=amount;check(providerTotal.isFinite()){"El total de pagos a proveedores no es válido."}
   }
  }
  val balance=if(data.has("shift"))Store.cashBalance(c) else 0.0
  check(balance.isFinite()&&balance>=0.0){"El saldo de caja es inválido; revisa ventas y pagos antes de generar el reporte."}
  out.append("TOTAL VENDIDO: ").append(Store.money(total)).append("\nPAGOS A PROVEEDORES: ")
   .append(Store.money(providerTotal)).append("\nCAJA ACTUAL: ").append(Store.money(balance))
   .append("\n\nMOVIMIENTOS DE INVENTARIO Y GASTOS\n")
  for(i in 0 until moves.length()){
   val x=moves.optJSONObject(i)?:throw IllegalStateException("Hay un movimiento dañado; no se generó el reporte.")
   if(x.optString("type")=="CORRECTION_AUDIT"){
    val previous=x.optDouble("previousTotal",Double.NaN);val corrected=x.optDouble("newTotal",Double.NaN)
    val amount=x.optDouble("amount",0.0)
    check(previous.isFinite()&&previous>=0.0&&corrected.isFinite()&&corrected>=0.0&&amount.isFinite()&&amount>=0.0){"Hay una auditoría de corrección inválida; no se generó el reporte."}
    val ts=x.optLong("ts",0L)
    val date=if(ts>0L)java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",java.util.Locale("es","MX")).format(java.util.Date(ts)) else "Fecha no disponible"
    val evidence=x.optJSONObject("cashEvidence")?:org.json.JSONObject()
    val method=when(evidence.optString("method").lowercase()){"cash"->"Efectivo";"card"->"Tarjeta";"transfer"->"Transferencia";"other"->"Otro";else->"No indicado"}
    out.append(date).append(" | CORRECCIÓN DE VENTA | Folio: ").append(x.optString("saleId","-"))
     .append("\nMotivo: ").append(x.optString("reason","Sin motivo"))
    if(x.optString("detail").isNotBlank())out.append("\nDetalle: ").append(x.optString("detail"))
    out.append("\nTotal anterior: ").append(Store.money(previous)).append(" | Total corregido: ").append(Store.money(corrected))
    when(x.optString("direction")){"COBRO"->out.append("\nDiferencia cobrada: ").append(Store.money(amount));"DEVOLUCION"->out.append("\nDiferencia devuelta: ").append(Store.money(amount));else->out.append("\nSin movimiento adicional de dinero")}
    if(amount>0.0)out.append("\nConfirmación: ").append(if(x.optBoolean("cashConfirmed"))"Registrada" else "No registrada")
     .append(" | Medio: ").append(method).append(" | Referencia: ").append(evidence.optString("reference","-"))
    if(x.optString("cashReason").isNotBlank())out.append("\nMotivo de caja: ").append(x.optString("cashReason"))
    out.append("\nNota: el total corregido ya está incluido en VENTAS; este registro es evidencia y no debe sumarse nuevamente.\n\n")
   }else out.append(x.optString("time")).append(" | ").append(x.optString("text")).append("\n")
  }
  out.append("\nSTOCK ACTUAL\n")
  for(i in 0 until products.length()){
   val x=products.optJSONObject(i)?:throw IllegalStateException("Hay un producto dañado; no se generó el reporte.")
   val price=x.optDouble("price",Double.NaN);val stock=x.optInt("stock",Int.MIN_VALUE)
   check(x.optString("code").isNotBlank()&&price.isFinite()&&price>=0.0&&stock>=0){"Hay un producto con código, precio o existencia inválidos; no se generó el reporte."}
   out.append(x.optString("code")).append(" | ").append(x.optString("name")).append(" | ")
    .append(Store.money(price)).append(" | Existencia: ").append(stock).append("\n")
  }
  val dir=c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)?:c.filesDir
  if(!dir.exists()&&!dir.mkdirs())throw java.io.IOException("No se pudo crear la carpeta del reporte.")
  val file=java.io.File(dir,"SENE-VENTAS-REPORTE.txt")
  file.writeText(out.toString(),Charsets.UTF_8)
  return file
 }
}
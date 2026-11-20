package com.sene.ventas
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.provider.Settings
import android.content.*
import android.graphics.Typeface
import android.graphics.Bitmap
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
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
private const val DEFAULT_PIN="2910"
private const val PATRON_PREFS="sene_patron_security"
private fun currentPatronPin(c:Context)=c.getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE).getString("pin",DEFAULT_PIN)?:DEFAULT_PIN
private fun configuredWorkHours(c:Context)=c.getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE).getInt("work_hours",8).coerceIn(1,24)
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
private fun Activity.forceDefaultPinChange(onSaved:()->Unit){
 val next=input("Crea tu nuevo PIN privado",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
 val confirm=input("Confirma tu nuevo PIN",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
 val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8));addView(next);addView(confirm)}
 val dialog=AlertDialog.Builder(this)
  .setTitle("🚨🚨 ALERTA CRÍTICA DE SEGURIDAD 🚨🚨")
  .setMessage("ATENCIÓN: ESTÁS UTILIZANDO EL PIN DE FÁBRICA 2910. CUALQUIER PERSONA QUE LO CONOZCA PODRÍA ENTRAR A SOY PATRÓN Y ACCEDER A LA ADMINISTRACIÓN DEL NEGOCIO. DEBES REEMPLAZARLO AHORA POR UN PIN NUEVO, PRIVADO Y QUE SOLO TÚ, COMO DUEÑO, DEBES CONOCER. NO LO COMPARTAS NI LO ANOTES A LA VISTA.\n\nPor seguridad, no podrás continuar hasta crear y confirmar tu nuevo PIN.")
  .setView(box).setCancelable(false).setPositiveButton("CAMBIAR PIN AHORA",null).create()
 dialog.setOnShowListener{
  dialog.setCanceledOnTouchOutside(false)
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   val a=next.text.toString();val b=confirm.text.toString()
   if(a.length<4||a.length>12||a.any{!it.isDigit()}){next.error="Usa de 4 a 12 dígitos";return@setOnClickListener}
   if(a==DEFAULT_PIN){next.error="No puedes conservar el PIN predeterminado 2910";return@setOnClickListener}
   if(a!=b){confirm.error="Los PIN no coinciden";return@setOnClickListener}
   val saved=getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE).edit()
    .putString("pin",a).putBoolean("default_pin_warning_shown",true).commit()
   if(!saved){msg("No se pudo guardar el nuevo PIN. Inténtalo nuevamente.");return@setOnClickListener}
   dialog.dismiss()
   AlertDialog.Builder(this).setTitle("PIN actualizado")
    .setMessage("Tu PIN nuevo quedó guardado en este dispositivo. Solo tú debes conocerlo. El aviso del PIN predeterminado no volverá a aparecer.")
    .setPositiveButton("CONTINUAR"){_,_->onSaved()}.setCancelable(false).show()
  }
 }
 dialog.show()
}
private fun Activity.pin(ok:()->Unit){
 val prefs=getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE)
 val biometricEnabled=prefs.getBoolean("biometric_enabled",false)
 val e=input("PIN del patrón",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
 val dialog=AlertDialog.Builder(this).setTitle("Soy Patrón").setMessage(if(biometricEnabled)"Puedes entrar con tu huella o con tu PIN." else "Introduce tu PIN para continuar.").setView(e)
  .setPositiveButton("ENTRAR",null).setNegativeButton("CANCELAR",null)
  .setNeutralButton(if(biometricEnabled)"USAR HUELLA" else "CERRAR",null).setCancelable(false).create()
 dialog.setOnShowListener{
  dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{dialog.dismiss();finish()}
  dialog.setOnCancelListener{finish()}
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   if(e.text.toString()==currentPatronPin(this)){dialog.dismiss();if(currentPatronPin(this)==DEFAULT_PIN)forceDefaultPinChange{ok()}else ok()}else{e.text?.clear();e.error="PIN incorrecto"}
  }
  dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{
   if(!biometricEnabled){dialog.dismiss();finish();return@setOnClickListener}
   val host=this
   if(host !is FragmentActivity){msg("La autenticación biométrica no está disponible en esta pantalla");return@setOnClickListener}
   val manager=BiometricManager.from(host)
   if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)!=BiometricManager.BIOMETRIC_SUCCESS){msg("La huella no está disponible. Puedes entrar con tu PIN.");return@setOnClickListener}
   val prompt=BiometricPrompt(host,ContextCompat.getMainExecutor(host),object:BiometricPrompt.AuthenticationCallback(){
    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){dialog.dismiss();if(currentPatronPin(host)==DEFAULT_PIN)forceDefaultPinChange{ok()}else ok()}
    override fun onAuthenticationError(errorCode:Int,errString:CharSequence){msg("No se pudo autenticar. Puedes usar tu PIN.")}
    override fun onAuthenticationFailed(){msg("Huella no reconocida. Intenta de nuevo o usa tu PIN.")}
   })
   val info=BiometricPrompt.PromptInfo.Builder().setTitle("Entrar a Soy Patrón").setSubtitle("Confirma tu identidad con la huella").setNegativeButtonText("CANCELAR").build()
   prompt.authenticate(info)
  }
 }
 dialog.show()
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
 private fun nextNumber(activity:Activity,tree:Uri?=null,employeeId:String?=null,workerName:String=""):Long {
  var highest=0L
  val local=if(!employeeId.isNullOrBlank())WorkerArchive.localInventoryFolder(activity,employeeId,workerName) else java.io.File(activity.getExternalFilesDir(null)?:activity.filesDir,"INVENTARIO-SENE")
  local.list()?.forEach{name->val n=Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()?:0L;if(n>highest)highest=n}
  if(tree!=null)try{
   InventoryReader.listSnapshots(activity,tree).filter{snap->
    if(employeeId.isNullOrBlank()) !snap.folder.contains("/ TRABAJADORES /") && !snap.folder.contains("TRABAJADORES /")
    else snap.folder.contains(WorkerArchive.profileKey(employeeId,workerName),true)
   }.forEach{snap->val n=Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(snap.file)?.groupValues?.get(1)?.toLongOrNull()?:0L;if(n>highest)highest=n}
  }catch(_:Exception){}
  return highest+1L
 }
 fun saveLocal(activity:Activity,products:JSONArray,employeeId:String?=null,workerName:String=""):java.io.File {
  val folder=if(!employeeId.isNullOrBlank())WorkerArchive.localInventoryFolder(activity,employeeId,workerName) else java.io.File(activity.getExternalFilesDir(null)?:activity.filesDir,"INVENTARIO-SENE")
  if(!folder.exists()&&!folder.mkdirs())throw java.io.IOException("No se pudo crear la carpeta local de inventarios")
  val savedTree=activity.getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)?.let{Uri.parse(it)}
  val number=nextNumber(activity,savedTree,employeeId,workerName)
  val target=java.io.File(folder,"inventario-$number.txt")
  writeInventory(activity,target.outputStream(),products)
  if(!target.exists()||target.length()==0L)throw java.io.IOException("No se pudo verificar el inventario local")
  if(employeeId.isNullOrBlank())activity.getSharedPreferences("sene_ventas_local",0).edit().putBoolean("first_inventory_created",true).putString("inventory_source_file",target.name).putString("inventory_source_updated",stamp()).apply()
  return target
 }
 private fun writeInventory(activity:Activity,stream:java.io.OutputStream,products:JSONArray){
  val updated=stamp()
  val out=StringBuilder("INVENTARIO_SENE_V1\nActualizado: ").append(updated).append("\nCódigo | Producto | Precio | Existencia | Pasillo | Estante")
  for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;out.append("\n").append(p.optString("code")).append(" | ").append(p.optString("name")).append(" | ").append(p.optDouble("price")).append(" | ").append(p.optInt("stock")).append(" | ").append(p.optString("aisle")).append(" | ").append(p.optString("shelf"))}
  val encrypted=SeneSharedFiles.encode("INVENTARIO_SENE",ReportCrypto.ownerKey(activity),out.toString().toByteArray(Charsets.UTF_8),updated)
  stream.use{it.write(encrypted)}
 }
 fun save(activity:Activity,products:JSONArray,employeeId:String?=null,workerName:String=""):Boolean {
  val local=saveLocal(activity,products,employeeId,workerName)
  val saved=activity.getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)?:return false
  return try{
   val tree=Uri.parse(saved);val resolver=activity.contentResolver
   val folder=if(!employeeId.isNullOrBlank())WorkerArchive.profileSection(activity,tree,employeeId,workerName,"INVENTARIOS") else {
    val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
    val rootName=resolver.query(root,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
     if(cur.moveToFirst())cur.getString(0) else null
    }
    if(rootName.equals("INVENTARIO-SENE",true)) root else {
     val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(root))
     var found:Uri?=null
     resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
      val id=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val col=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      while(cur.moveToNext())if(id>=0&&col>=0&&cur.getString(col).equals("INVENTARIO-SENE",true))found=DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(id))
     }
     found?:DocumentsContract.createDocument(resolver,root,DocumentsContract.Document.MIME_TYPE_DIR,"INVENTARIO-SENE")?:throw java.io.IOException("No se pudo crear INVENTARIO-SENE")
    }
   }
   val number=Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(local.name)?.groupValues?.get(1)?:throw java.io.IOException("Número de inventario inválido")
   val name="inventario-$number.txt"
   val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(folder))
   var target:Uri?=null
   resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
    val id=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val col=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    while(cur.moveToNext())if(id>=0&&col>=0&&cur.getString(col)==name)target=DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(id))
   }
   val file=target?:DocumentsContract.createDocument(resolver,folder,"text/plain",name)?:throw java.io.IOException("No se pudo crear $name")
   resolver.openOutputStream(file,"w")?.use{out->local.inputStream().use{it.copyTo(out)}}?:throw java.io.IOException("No se pudo escribir $name")
   true
  }catch(_:Exception){false}
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
  val decoded=when {
   SeneSharedFiles.isSharedFile(raw) -> {
    val shared=SeneSharedFiles.decode(raw,ReportCrypto.ownerKey(activity))
    if(shared.category!="INVENTARIO_SENE"&&shared.category!="INVENTARIO_MAESTRO")throw java.io.IOException("El archivo compartido no es un inventario")
    shared.payload
   }
   InventoryCrypto.isEncrypted(raw) -> InventoryCrypto.decrypt(raw)
   else -> raw
  }
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
    products.put(JSONObject().put("code",parts[0]).put("name",parts[1]).put("price",price).put("stock",stock).put("aisle",parts.getOrElse(4){""}).put("shelf",parts.getOrElse(5){""}))
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
  return snapshots.maxWithOrNull(compareBy<InventorySnapshot>{Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(it.file)?.groupValues?.get(1)?.toLongOrNull()?:0L}.thenBy{snapshot->try{formatter.parse(snapshot.updated)?.time?:0L}catch(_:Exception){0L}})
   ?:throw java.io.IOException("No se encontró un inventario válido en INVENTARIO SENE")
 }
 fun latestMaster(activity:Activity,tree:Uri?):InventorySnapshot? {
  val localCandidates=mutableListOf<InventorySnapshot>()
  val local=java.io.File(activity.getExternalFilesDir(null)?:activity.filesDir,"INVENTARIO-SENE")
  local.listFiles()?.filter{it.isFile&&it.name.matches(Regex("(?i)^inventario-\\d+\\.txt$"))}?.forEach{file->
   try{
    val raw=file.readBytes()
    val decoded=when{
     SeneSharedFiles.isSharedFile(raw)->SeneSharedFiles.decode(raw,ReportCrypto.ownerKey(activity)).payload
     InventoryCrypto.isEncrypted(raw)->InventoryCrypto.decrypt(raw)
     else->raw
    }
    val content=String(decoded,Charsets.UTF_8)
    val products=JSONArray()
    content.lineSequence().map{it.trim()}.filter{it.isNotBlank()}.forEach{line->
     if(line.startsWith("Actualizado:",true)||line.startsWith("Código",true)||line.equals("INVENTARIO_SENE_V1",true))return@forEach
     val parts=line.split("|").map{it.trim()};if(parts.size<4)return@forEach
     val price=parseAmount(parts[2]);val stock=Regex("-?\\d+").find(parts[3])?.value?.toIntOrNull()
     if(parts[0].isNotBlank()&&parts[1].isNotBlank()&&price!=null&&stock!=null)products.put(JSONObject().put("code",parts[0]).put("name",parts[1]).put("price",price).put("stock",stock).put("aisle",parts.getOrElse(4){""}).put("shelf",parts.getOrElse(5){""}))
    }
    if(products.length()>0){val updated=content.lineSequence().firstOrNull{it.startsWith("Actualizado: ")}?.removePrefix("Actualizado: ")?: "Fecha no indicada";localCandidates.add(InventorySnapshot("INVENTARIO-SENE",file.name,updated,products))}
   }catch(_:Exception){}
  }
  if(tree!=null)try{
   val snapshots=listSnapshots(activity,tree)
   val master=snapshots.filter{it.folder.contains("INVENTARIO-MAESTRO",true)}
   if(master.isNotEmpty())return master.maxByOrNull{it.updated}
   val shared=snapshots.filter{!it.folder.contains("TRABAJADORES",true)}
   if(shared.isNotEmpty())return shared.maxByOrNull{it.updated}
  }catch(_:Exception){}
  return localCandidates.maxByOrNull{it.updated}
 }
 fun latestForWorker(activity:Activity,tree:Uri?,employeeId:String,workerName:String):InventorySnapshot? {
  val key=WorkerArchive.profileKey(employeeId,workerName)
  val candidates=mutableListOf<InventorySnapshot>()
  val local=WorkerArchive.localInventoryFolder(activity,employeeId,workerName)
  local.listFiles()?.filter{it.isFile&&it.name.matches(Regex("(?i)^inventario-\\d+\\.txt$"))}?.forEach{file->
   try{
    val content=file.readText(Charsets.UTF_8)
    val products=JSONArray()
    content.lineSequence().map{it.trim()}.filter{it.isNotBlank()}.forEach{line->
     if(line.startsWith("Actualizado:",true)||line.startsWith("Código",true)||line.equals("INVENTARIO_SENE_V1",true))return@forEach
     val parts=line.split("|").map{it.trim()};if(parts.size<4)return@forEach
     val price=parseAmount(parts[2]);val stock=Regex("-?\\d+").find(parts[3])?.value?.toIntOrNull()
     if(parts[0].isNotBlank()&&parts[1].isNotBlank()&&price!=null&&stock!=null)products.put(JSONObject().put("code",parts[0]).put("name",parts[1]).put("price",price).put("stock",stock).put("aisle",parts.getOrElse(4){""}).put("shelf",parts.getOrElse(5){""}))
    }
    if(products.length()>0){val updated=content.lineSequence().firstOrNull{it.startsWith("Actualizado: ")}?.removePrefix("Actualizado: ")?: "Fecha no indicada";candidates.add(InventorySnapshot("INVENTARIO-SENE / TRABAJADORES / $key / INVENTARIOS",file.name,updated,products))}
   }catch(_:Exception){}
  }
  if(tree!=null)try{candidates.addAll(listSnapshots(activity,tree).filter{it.folder.contains(key,true)})}catch(_:Exception){}
  return candidates.maxWithOrNull(compareBy<InventorySnapshot>{Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(it.file)?.groupValues?.get(1)?.toLongOrNull()?:0L}.thenBy{it.updated})
 }

}
private fun whatsapp(a:Activity,body:String,after:()->Unit={}){val u="https://wa.me/"+Store.phone(a)+"?text="+URLEncoder.encode(body,"UTF-8");try{a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(u)));after()}catch(e:Exception){a.msg("No se pudo abrir WhatsApp")}}

class InicioTurnoActivity:FragmentActivity(){
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
 override fun onResume(){super.onResume();if(::internetLabel.isInitialized)enforceLicenseGate(this){};if(pendingStartAfterWhatsApp){pendingStartAfterWhatsApp=false;startActivity(Intent(this,MainActivity::class.java));finish()}else if(refreshAfterPatron){refreshAfterPatron=false;draw()}}
 override fun onCreate(b:Bundle?){super.onCreate(b);Protection.requireActivation(this){continueAfterAuthorization()}}
 private fun continueAfterAuthorization(){enforceLicenseGate(this){if(Store.shift(this)!=null){startActivity(Intent(this,MainActivity::class.java));finish();return@enforceLicenseGate};draw();consultInternetTime()}}
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

 override fun onDestroy(){internetClockHandler.removeCallbacks(internetClockTick);deviceClockHandler.removeCallbacks(deviceClockTick);super.onDestroy()}
 private var pendingWorker=""
 private var pendingWorkerPhone=""
 private var pendingWorkerId=""
 private var pendingCash=""
 private fun startShiftWithLatestInventory(workerId:String,worker:String,phone:String,cash:String){
  // El trabajador nunca debe recibir el diálogo de clave del propietario.
  if(!ReportCrypto.hasKey(this)){
   AlertDialog.Builder(this).setTitle("CONFIGURACIÓN DEL PATRÓN PENDIENTE")
    .setMessage("El patrón debe abrir SOY PATRÓN y configurar la clave compartida antes de iniciar turnos.")
    .setPositiveButton("ENTENDIDO",null).show()
   return
  }
  continueStartShiftWithLatestInventory(workerId,worker,phone,cash)
 }
 private fun continueStartShiftWithLatestInventory(workerId:String,worker:String,phone:String,cash:String){
  pendingWorkerId=workerId
  val localProducts=Store.masterProducts(this)
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  // Cada perfil recupera su propio inventario más reciente; si aún no tiene uno,
  // el primer turno parte del inventario general existente.
  val profile=WorkerProfiles.all(this).firstOrNull{it.id==workerId}
  profile?.let{WorkerArchive.ensureProfileFolders(this,it.id,it.name)}
  val hasLocalProfileInventory=profile?.let{WorkerArchive.localInventoryFolder(this,it.id,it.name).listFiles()?.any{f->f.isFile&&f.name.matches(Regex("(?i)^inventario-\\d+\\.txt$"))}==true}==true
  if(localProducts.length()>0 || !saved.isNullOrBlank() || hasLocalProfileInventory){
   createShiftReport(workerId,worker,phone,cash,saved?.takeIf{it.isNotBlank()}?.let{Uri.parse(it)})
   return
  }
  AlertDialog.Builder(this).setTitle("PRIMER USO DE SENE-VENTAS")
   .setMessage("Es la primera vez usando SENE-VENTAS. Dirígete a la sección Soy Patrón para generar o cargar tu primer inventario.")
   .setPositiveButton("IR A SOY PATRÓN"){_,_->pin{startActivity(Intent(this,PatronActivity::class.java))}}
   .setNegativeButton("Cerrar",null).show()
 }
 private fun createShiftReport(workerId:String,worker:String,phone:String,cash:String,tree:Uri?){
  try{
   val localProducts=Store.masterProducts(this)
   val savedTree=tree?:getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)?.takeIf{it.isNotBlank()}?.let{Uri.parse(it)}
   // Un TXT cargado ya deja el inventario disponible en Store. Si no existe
   // permiso de carpeta, el respaldo se guarda en el espacio privado de SENE.
   val profileName=WorkerProfiles.all(this).firstOrNull{it.id==workerId}?.name?:worker
   val profileKey=WorkerArchive.profileKey(workerId,profileName)
   ArchiveRetention.cleanupIfDue(this,savedTree,ReportCrypto.ownerKey(this))
   // Todos los perfiles comienzan siempre desde el inventario maestro más reciente.
   // Las copias e historiales individuales permanecen dentro de su carpeta de perfil.
   val snapshot=try{InventoryReader.latestMaster(this,savedTree)}catch(_:Exception){null}
   WorkerArchive.ensureProfileFolders(this,workerId,profileName)
   val latest=snapshot?.folder?: "INVENTARIO SENE"
   val prefs=getSharedPreferences("sene_ventas_local",0)
   val newest=snapshot?.file?:prefs.getString("inventory_source_file","Inventario maestro local")!!
   val updated=snapshot?.updated?:prefs.getString("inventory_source_updated","Cargado desde inventario maestro local")!!
   val products=snapshot?.products?:localProducts
   if(products.length()==0)throw java.io.IOException("No se encontró un inventario maestro válido")
   Store.saveProducts(this,products,true)
   // Se crea una copia inicial para el perfil, sin convertirla en la fuente maestra.
   InventoryArchive.save(this,products,workerId,profileName)
   val now=System.currentTimeMillis()
   val profileRecord=WorkerProfiles.markFirstShift(this,workerId,now)?:WorkerProfiles.all(this).firstOrNull{it.id==workerId}
   val firstShiftTs=profileRecord?.firstShiftTs?.takeIf{it>0L}?:now
   val profileCreated=profileRecord?.createdAt?.takeIf{it>0L}?.let{SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(it))}?: "No disponible en perfil anterior"
   val firstShiftStamp=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(firstShiftTs))
   val employeeConfig=EmployeePayroll.config(this,workerId)
   val hours=employeeConfig?.dailyHours?:8
   val dayNames=listOf("","Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado")
   val workDays=employeeConfig?.workDaysOfWeek?.sortedBy{if(it==Calendar.SUNDAY)0 else it}?.joinToString(", "){dayNames.getOrElse(it){"-"}}?: "Pendiente de configurar"
   val payDayName=employeeConfig?.let{dayNames.getOrElse(it.payDay){"-"}}?: "Pendiente de configurar"
   val shift=JSONObject().put("shiftId",UUID.randomUUID().toString()).put("reportCount",0).put("employeeId",workerId).put("worker",worker).put("phone",phone).put("cash",parseAmount(cash)?:0.0).put("started",stamp()).put("startTs",now).put("workHours",hours).put("expectedEndTs",now+hours*3600000L).put("expectedEnd",SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(now+hours*3600000L))).put("lastReport",now).put("workDaysOfWeek",JSONArray().apply{employeeConfig?.workDaysOfWeek?.sorted()?.forEach{put(it)}}).put("weeklySalaryCents",employeeConfig?.weeklySalaryCents?:0L).put("payDay",employeeConfig?.payDay?:0)
   val report=StringBuilder("SENE-VENTAS - INICIO DE TURNO\nFecha y hora: ").append(stamp()).append("\nFECHA Y HORA DE CREACIÓN DEL PERFIL: ").append(profileCreated).append("\nFECHA Y HORA DEL PRIMER INICIO DE TURNO: ").append(firstShiftStamp).append("\nHORA DE ENTRADA: ").append(stamp()).append("\nHORA DE SALIDA: Por definir").append("\nTrabajador: ").append(worker).append("\nNúmero del trabajador: ").append(phone).append("\nHoras de jornada: ").append(hours).append("\nDías laborales: ").append(workDays).append("\nSueldo semanal: ").append(employeeConfig?.let{EmployeePayroll.money(it.weeklySalaryCents)}?: "Pendiente de configurar").append("\nDía de pago: ").append(payDayName).append("\nCaja inicial: ").append(Store.money(shift.optDouble("cash"))).append("\nInventario SENE: carpeta ").append(latest).append("\nArchivo de inventario: ").append(newest).append("\nÚltima actualización del inventario: ").append(updated).append("\n\nINVENTARIO_SENE_V1\nCódigo | Producto | Precio | Existencia | Pasillo | Estante")
   for(i in 0 until products.length()){val item=products.optJSONObject(i)?:continue;report.append("\n").append(item.optString("code")).append(" | ").append(item.optString("name")).append(" | ").append(Store.money(item.optDouble("price"))).append(" | ").append(item.optInt("stock")).append(" | ").append(item.optString("aisle")).append(" | ").append(item.optString("shelf"))}
   val body=report.toString()+REPORT_BACKUP_NOTE
   if(savedTree!=null) saveTextBackup(this,savedTree,"SENE-VENTAS-INICIO",body,workerId,worker)
   else saveTextBackupLocal(this,"SENE-VENTAS-INICIO",body,workerId,worker)
   Store.saveShift(this,shift)
   if(!EmployeePayroll.recordShiftStart(this,workerId,now))msg("Aviso: no se pudo guardar el registro de asistencia local.")
   // Abrir el chat directo del patrón con el reporte completo ya preparado para enviar.
   val startEventKey=shift.optString("shiftId")+":START"
   val encryptedStart=ReportCrypto.prepare(this,workerId,"INICIO_TURNO",body,startEventKey)
   pendingStartAfterWhatsApp=openWhatsAppText(this,encryptedStart)
   if(!pendingStartAfterWhatsApp)msg("El turno quedó registrado y el respaldo TXT está guardado en el teléfono, en SENE VENTAS / INVENTARIO SENE. Revisa el número de WhatsApp del patrón.")
  }catch(e:Exception){AlertDialog.Builder(this).setTitle("No se pudo iniciar el turno").setMessage("No se pudo guardar el respaldo en INVENTARIO SENE. Verifica que la carpeta esté disponible y tenga permisos de escritura.\n\nDetalle: "+(e.message?:"Error desconocido.")).setPositiveButton("IR A SOY PATRÓN"){_,_->pin{startActivity(Intent(this,PatronActivity::class.java))}}.setNegativeButton("Cerrar",null).show()}
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==START_INVENTORY_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();WorkerProfiles.all(this).forEach{WorkerArchive.ensureProfileFolders(this,it.id,it.name)};createShiftReport(pendingWorkerId,pendingWorker,pendingWorkerPhone,pendingCash,tree)}catch(e:Exception){msg("No se pudo guardar el acceso a Inventario SENE: "+(e.message?:"error"))}}}
 private fun chooseWorker(){
  val profiles=WorkerProfiles.all(this)
  if(profiles.isEmpty()){
   AlertDialog.Builder(this).setTitle("SIN PERFILES DISPONIBLES")
    .setMessage("Solicita a tu patrón que te dé de alta en el sistema y te entregue tus datos de acceso para poder iniciar turno.")
    .setPositiveButton("ENTENDIDO",null).show()
   return
  }
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
  val scroll=ScrollView(this)
  lateinit var dialog:AlertDialog
  profiles.forEach{profile->
   content.addView(button("👤  "+profile.name,false){dialog.dismiss();authenticateWorker(profile)})
  }
  scroll.addView(content)
  dialog=AlertDialog.Builder(this).setTitle("INICIAR TURNO").setView(scroll)
   .setNegativeButton("CERRAR",null).create()
  dialog.show()
 }
 private fun authenticateWorker(profile:WorkerProfiles.Profile){
  val pinCode=input("PIN personal de 4 dígitos",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
  val dialog=AlertDialog.Builder(this).setTitle("HOLA, "+profile.name.uppercase(Locale("es","MX"))).setMessage("Introduce tu PIN para iniciar turno.").setView(pinCode)
   .setPositiveButton("CONTINUAR",null).setNegativeButton("CANCELAR",null).create()
  dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{
   if(!WorkerProfiles.verify(profile,pinCode.text.toString())){pinCode.text?.clear();pinCode.error="PIN incorrecto";return@setOnClickListener}
   dialog.dismiss();enterWorker(profile)
  }}
  dialog.show()
 }
 private fun enterWorker(profile:WorkerProfiles.Profile){
  val cash=input("Cuenta el efectivo que hay actualmente en caja e introduce el importe $",true)
  val dialog=AlertDialog.Builder(this).setTitle("CAJA INICIAL · "+profile.name).setMessage("Cuenta físicamente el efectivo disponible antes de comenzar tu jornada.").setView(cash)
   .setPositiveButton("COMENZAR A TRABAJAR",null).setNegativeButton("CANCELAR",null).create()
  dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{
   val amount=parseAmount(cash.text.toString())
   if(amount==null||amount<0){cash.error="Introduce una cantidad válida";return@setOnClickListener}
   dialog.dismiss();startShiftWithLatestInventory(profile.id,profile.name,profile.phone,cash.text.toString())
  }}
  dialog.show()
 }
 private lateinit var deviceLabel:TextView
 private val deviceClockHandler=Handler(Looper.getMainLooper())
 private val deviceClockTick=object:Runnable{override fun run(){if(::deviceLabel.isInitialized)deviceLabel.text="Hora del celular: "+stamp();deviceClockHandler.postDelayed(this,1000L)}}
 private fun draw(){
  val v=page("SENE")
  if(v.childCount>3)(v.getChildAt(3) as? TextView)?.text="Todo en un solo lugar, todo bajo control."
  deviceLabel=txt("Hora del celular: "+stamp())
  v.addView(deviceLabel)
  internetLabel=txt("Hora de Internet: "+internet,15f)
  v.addView(internetLabel)
  v.addView(button("INICIAR TURNO",true){if(currentPatronPin(this)==DEFAULT_PIN){AlertDialog.Builder(this).setMessage("DIRÍGETE A SOY PATRÓN").setPositiveButton("ENTENDIDO",null).show()}else{chooseWorker()}})
  v.addView(button("SOY PATRÓN",false){refreshAfterPatron=true;val prefs=getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE);if(currentPatronPin(this)==DEFAULT_PIN&&!prefs.getBoolean("default_pin_warning_shown",false)){forceDefaultPinChange{pin{startActivity(Intent(this,PatronActivity::class.java).putExtra("authorized_from_home",true))}}}else{pin{startActivity(Intent(this,PatronActivity::class.java).putExtra("authorized_from_home",true))}}})
  Protection.addBlindButton(this,v)
  v.addView(button("MI TURNO LABORAL",false){showWorkHistory()})
  deviceClockHandler.removeCallbacks(deviceClockTick)
  deviceClockTick.run()
  display(v)
 }
 private fun showWorkHistory(){
  val moves=Store.moves(this);val lines=StringBuilder()
  for(i in moves.length()-1 downTo 0){val item=moves.optJSONObject(i)?:continue;val line=item.optString("text");if(line.isNotBlank())lines.append(line).append("\n\n")}
  val message=if(lines.isEmpty())"Todavía no hay movimientos registrados." else lines.toString().trim()
  AlertDialog.Builder(this).setTitle("MI TURNO LABORAL").setMessage(message).setPositiveButton("CERRAR",null).show()
 }
}

class MainActivity:FragmentActivity(){
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
 private val ticket=linkedMapOf<String,Int>();private var scanningTicketCorrection=false;private var correctionStage=0;private var correctionTicketId="";private var correctionAction="";private var correctionOriginalCode="";private lateinit var code:EditText;private lateinit var paid:EditText;private lateinit var ticketView:LinearLayout;private lateinit var totalView:TextView;private lateinit var sell:Button;private val h=Handler(Looper.getMainLooper())
 private var reportDialog:AlertDialog?=null
 private lateinit var countdownView:TextView
 private var pendingFinalReport=false
 private var pendingShareUri:Uri?=null
 private var pendingFinalShare=false
 private var confirmingReport=false
 @Volatile private var reportProcessing=false
 private var refreshAfterPatron=false
 private var overtimeDialog:AlertDialog?=null
 private val lock=object:Runnable{override fun run(){
  if(::sell.isInitialized){
   val active=Store.shift(this@MainActivity)
   if(active?.optBoolean("finishPending",false)==true){
    sell.isEnabled=false;sell.alpha=0.45f
    if(::countdownView.isInitialized)countdownView.text="CIERRE DE TURNO EN PROCESO"
   }else{
    checkScheduledWorkdayEnd()
    if(overtimeDialog?.isShowing!=true){
     updateReportCountdown()
     if(due())showReportAlert() else {sell.isEnabled=true;sell.alpha=1f}
    }else{
     sell.isEnabled=false;sell.alpha=0.45f
    }
   }
  }
  h.postDelayed(this,1000)
 }}
 private fun updateReportCountdown(){if(!::countdownView.isInitialized)return;val s=Store.shift(this)?:return;val remaining=(10800000L-(System.currentTimeMillis()-s.optLong("lastReport",System.currentTimeMillis()))).coerceAtLeast(0L);if(remaining==0L){countdownView.text="\u26A0 REPORTE PENDIENTE: ENV\u00CDA PARA CONTINUAR";countdownView.setTextColor(0xFFB3261E.toInt());sell.isEnabled=false;sell.alpha=0.45f}else{val total=remaining/1000;val hh=total/3600;val mm=(total%3600)/60;val ss=total%60;countdownView.text=String.format(Locale.US,"SIGUIENTE REPORTE EN: %02d:%02d:%02d",hh,mm,ss);countdownView.setTextColor(0xFF168B45.toInt())}}
 private fun due():Boolean{val s=Store.shift(this)?:return false;return System.currentTimeMillis()-s.optLong("lastReport",System.currentTimeMillis())>=10800000}
 override fun onCreate(b:Bundle?){super.onCreate(b);enforceLicenseGate(this){if(Store.shift(this)==null){startActivity(Intent(this,InicioTurnoActivity::class.java));finish();return@enforceLicenseGate};draw();h.post(lock);salesClockHandler.post(salesClockTick);refreshSalesInternetClock();if(intent.getBooleanExtra("admin_force_close",false))h.post{requestAdminForceClose()}}}
 override fun onResume(){super.onResume();if(::salesDeviceClock.isInitialized){enforceLicenseGate(this){};salesClockHandler.removeCallbacks(salesClockTick);salesClockHandler.post(salesClockTick)};if(::salesInternetClock.isInitialized)refreshSalesInternetClock();if(pendingShareUri!=null)h.post{confirmReportSent()}else if(refreshAfterPatron){refreshAfterPatron=false;draw()}else if(::sell.isInitialized){val active=Store.shift(this);if(active?.optBoolean("finishPending",false)==true){sell.isEnabled=false;sell.alpha=0.45f}else{h.post{checkScheduledWorkdayEnd();if(overtimeDialog?.isShowing!=true&&due())showReportAlert()}}}}
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
 private fun showReportAlert(){
  if(reportDialog?.isShowing==true)return
  val active=Store.shift(this)?:return
  // Un solo aviso por ciclo: no repetir mientras el trabajador no confirme el envío.
  if(active.optBoolean("reportReminderShown",false))return
  active.put("reportReminderShown",true)
  Store.saveShift(this,active)
  reportDialog=AlertDialog.Builder(this).setTitle("REPORTE CADA 3 HORAS").setMessage("Envía el documento de movimientos por WhatsApp para continuar trabajando.").setCancelable(false).setPositiveButton("ENVIAR"){_,_->sendReport(false)}.create().also{it.setCanceledOnTouchOutside(false);it.show();it.window?.setLayout(-1,-2)}
 }
 private var selectedPrinterAddress:String?=null
 private var printerSocket:BluetoothSocket?=null
 private val printerSppUuid=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
 private fun printReceiptIfConfigured(ticketId:String,total:Double,items:List<JSONObject>){
  val prefs=getSharedPreferences("sene_ventas_local",0)
  val address=prefs.getString("printer_address",null)?:return
  if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return
  Thread{
   var socket:BluetoothSocket?=printerSocket
   try{
    if(socket==null||!socket.isConnected){
     val adapter=BluetoothAdapter.getDefaultAdapter()?:return@Thread
     val connected=adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(printerSppUuid)
     adapter.cancelDiscovery();connected.connect();socket=connected;printerSocket=connected
    }
    val activeSocket=socket?:return@Thread
    val out=java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(0x1B,0x40))
    out.write("SENE VENTAS\nCOMPROBANTE DE VENTA\n".toByteArray(Charsets.UTF_8))
    out.write(("FECHA: "+stamp()+"\n").toByteArray(Charsets.UTF_8))
    for(item in items){
     val name=item.optString("name").take(26)
     val qty=item.optInt("qty",1)
     val amount=Store.money(item.optDouble("subtotal"))
     out.write(("$name x$qty  $amount\n").toByteArray(Charsets.UTF_8))
    }
    out.write(("\nTOTAL: "+Store.money(total)+"\n").toByteArray(Charsets.UTF_8))
    out.write("ESCANEA PARA CORREGIR ESTA VENTA\n".toByteArray(Charsets.UTF_8))
    val qr=ticketId.toByteArray(Charsets.UTF_8)
    val qrLen=qr.size+3
    out.write(byteArrayOf(0x1D,0x28,0x6B,0x04,0x00,0x31,0x41,0x32,0x00))
    out.write(byteArrayOf(0x1D,0x28,0x6B,0x03,0x00,0x31,0x43,0x06))
    out.write(byteArrayOf(0x1D,0x28,0x6B,0x03,0x00,0x31,0x45,0x31))
    out.write(byteArrayOf(0x1D,0x28,0x6B,(qrLen and 0xFF).toByte(),((qrLen shr 8) and 0xFF).toByte(),0x31,0x50,0x30))
    out.write(qr)
    out.write(byteArrayOf(0x1D,0x28,0x6B,0x03,0x00,0x31,0x51,0x30))
    out.write("\n\n\n".toByteArray(Charsets.UTF_8))
    out.write(byteArrayOf(0x1D,0x56,0x00))
    activeSocket.outputStream.write(out.toByteArray());activeSocket.outputStream.flush()
    runOnUiThread{msg("Ticket enviado a la impresora Bluetooth")}
   }catch(e:Exception){try{socket?.close()}catch(_:Exception){};printerSocket=null;runOnUiThread{msg("Venta guardada. No se pudo imprimir; revisa la conexión de la impresora.")}}
  }.start()
 }
 private fun showPrinterSetup(){
  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(12));setBackgroundColor(0xFFF4F6FB.toInt())}
  panel.addView(TextView(this).apply{text="IMPRESORAS BLUETOOTH COMPATIBLES";textSize=16f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF1D315B.toInt());setPadding(0,dp(4),0,dp(8))})
  fun legend(label:String,detail:String,color:Int){
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(0xFFFFFFFF.toInt(),12,0xFFDCE3F0.toInt())}
   card.addView(TextView(this).apply{text=label;textSize=13f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(color)})
   card.addView(TextView(this).apply{text=detail;textSize=12f;setTextColor(0xFF66738E.toInt());setPadding(0,dp(3),0,0)})
   val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(6);panel.addView(card,lp)
  }
  legend("COMPATIBLE","Térmicas ESC/POS Bluetooth clásico SPP; formatos de 58 mm y 80 mm.",0xFF168B45.toInt())
  legend("COMPATIBLE POR FORMATO","Papel térmico de 58 mm y 80 mm en equipos que acepten comandos ESC/POS.",0xFF168B45.toInt())
  legend("NO GARANTIZADA","Bluetooth BLE y protocolos propietarios no se conectan directamente en esta versión; requieren compatibilidad específica del fabricante.",0xFFB3261E.toInt())
  panel.addView(TextView(this).apply{text="Primero vincula la impresora desde los ajustes Bluetooth de Android. Después vuelve aquí y selecciona el dispositivo vinculado.";textSize=12f;setTextColor(0xFF65718A.toInt());setPadding(0,dp(8),0,dp(8))})
  val status=TextView(this).apply{text="Impresora: "+(getSharedPreferences("sene_ventas_local",0).getString("printer_name",null)?: "Sin configurar");textSize=13f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF263D70.toInt());setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(0xFFE9EFFB.toInt(),12)}
  panel.addView(status)
  val dialog=AlertDialog.Builder(this).setTitle("CONECTE SU IMPRESORA").setView(ScrollView(this).apply{addView(panel)}).setNegativeButton("CERRAR",null).create()
  panel.addView(button("ABRIR AJUSTES BLUETOOTH",false){try{startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}catch(_:Exception){msg("No se pudieron abrir los ajustes Bluetooth")}})
  panel.addView(button("BUSCAR IMPRESORAS VINCULADAS"){loadPairedPrinters(panel,status)})
  panel.addView(button("IMPRIMIR TICKET DE PRUEBA",false){printPrinterTest()})
  panel.addView(button("DESVINCULAR DE SENE",false){
   printerSocket?.close();printerSocket=null
   getSharedPreferences("sene_ventas_local",0).edit().remove("printer_address").remove("printer_name").apply()
   status.text="Impresora: Sin configurar";msg("Impresora quitada de SENE")
  })
  dialog.show()
 }
 private fun loadPairedPrinters(panel:LinearLayout,status:TextView){
  if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),8201);return
  }
  val adapter=BluetoothAdapter.getDefaultAdapter()
  if(adapter==null){msg("Este dispositivo no admite Bluetooth");return}
  if(!adapter.isEnabled){try{startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}catch(_:Exception){};msg("Activa Bluetooth y vuelve a buscar");return}
  try{
   val devices=adapter.bondedDevices?.toList()?.sortedBy{it.name?:it.address}?:emptyList()
   if(devices.isEmpty()){msg("No hay impresoras vinculadas. Vincula primero la impresora en los ajustes Bluetooth de Android.");return}
   val names=devices.map{(it.name?: "Dispositivo sin nombre")+"\\n"+it.address}.toTypedArray()
   AlertDialog.Builder(this).setTitle("DISPOSITIVOS VINCULADOS").setItems(names){_,which->
    val device=devices[which]
    connectPrinter(device,status)
   }.setNegativeButton("Cancelar",null).show()
  }catch(e:SecurityException){msg("Android no concedió permiso para consultar dispositivos Bluetooth")}
 }
 private fun connectPrinter(device:BluetoothDevice,status:TextView){
  Thread{
   var socket:BluetoothSocket?=null
   try{
    val adapter=BluetoothAdapter.getDefaultAdapter();adapter?.cancelDiscovery()
    val connected=device.createRfcommSocketToServiceRecord(printerSppUuid)
    connected.connect()
    printerSocket?.close();printerSocket=connected
    val name=try{device.name?:device.address}catch(_:Exception){device.address}
    getSharedPreferences("sene_ventas_local",0).edit().putString("printer_address",device.address).putString("printer_name",name).apply()
    runOnUiThread{status.text="IMPRESORA CONECTADA: "+name;msg("Impresora conectada. Ya puedes imprimir el ticket de prueba.")}
   }catch(e:Exception){
    try{socket?.close()}catch(_:Exception){}
    runOnUiThread{msg("No se pudo conectar. Verifica que sea Bluetooth SPP/ESC-POS y que no esté conectada a otro equipo.")}
   }
  }.start()
 }
 private fun printPrinterTest(){
  val activeSocket=printerSocket
  if(activeSocket==null||!activeSocket.isConnected){msg("Primero selecciona y conecta una impresora compatible");return}
  Thread{
   try{
    val bytes=byteArrayOf(0x1B,0x40)+
     "SENE VENTAS\\nPRUEBA DE IMPRESION\\nBluetooth SPP / ESC-POS\\n\\nConexion correcta\\n\\n\\n".toByteArray(Charsets.UTF_8)+
     byteArrayOf(0x1D,0x56,0x00)
    activeSocket.outputStream.write(bytes);activeSocket.outputStream.flush()
    runOnUiThread{msg("Ticket de prueba enviado a la impresora")}
   }catch(e:Exception){runOnUiThread{msg("Falló la impresión. Comprueba papel, conexión y compatibilidad ESC/POS.")}}
  }.start()
 }
 private fun onPrinterPermissionGranted(){
  if(Build.VERSION.SDK_INT<31||checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)showPrinterSetup()
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==8201){if(grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED)showPrinterSetup()else msg("Se necesita permiso Bluetooth para mostrar impresoras vinculadas")}
 }
 private fun beginTicketCorrection(){
  AlertDialog.Builder(this).setTitle("CORRECCIÓN DE VENTA")
   .setMessage("Escanea el código QR del ticket que deseas corregir. Solo los tickets emitidos con QR por esta versión podrán localizarse.")
   .setPositiveButton("ESCANEAR TICKET"){_,_->scanningTicketCorrection=true;openScanner()}
   .setNegativeButton("CANCELAR",null).show()
 }
 private fun handleTicketCorrectionScan(value:String){
  if(!value.startsWith("SENE-TICKET:")){msg("Ese código no corresponde a un ticket SENE");return}
  val sales=Store.sales(this);var found=false
  for(i in 0 until sales.length()){if(sales.optJSONObject(i)?.optString("ticketId")==value){found=true;break}}
  if(!found){msg("No se encontró el ticket. Verifica que sea un comprobante SENE vigente.");return}
  correctionTicketId=value
  AlertDialog.Builder(this).setTitle("TICKET LOCALIZADO")
   .setItems(arrayOf("CAMBIAR PRODUCTO","ELIMINAR PRODUCTO")){_,which->
    correctionAction=if(which==0)"REPLACE" else "DELETE";correctionStage=1
    msg("Escanea el producto original que aparece en el ticket");openScanner()
   }.setNegativeButton("CANCELAR",null).show()
 }
 private fun handleCorrectionProductScan(value:String){
  if(correctionStage==1){
   val sales=Store.sales(this);var available=false
   for(i in 0 until sales.length()){
    val row=sales.optJSONObject(i)?:continue
    if(row.optString("ticketId")==correctionTicketId&&row.optString("type","SALE")=="SALE"&&row.optString("code")==value){
     var already=0
     for(j in 0 until sales.length()){val prior=sales.optJSONObject(j)?:continue;if(prior.optString("ticketId")==correctionTicketId&&prior.optString("sourceCode")==value&&(prior.optString("type")=="SALE_DELETE"||prior.optString("type")=="SALE_REPLACE_OLD"))already+=prior.optInt("qty",1)}
     if(already<row.optInt("qty",1))available=true
    }
   }
   if(!available){correctionStage=0;msg("El producto no pertenece al ticket o ya fue corregido en su totalidad");return}
   correctionOriginalCode=value
   if(correctionAction=="DELETE")askCorrectionReason(null)
   else{correctionStage=2;msg("Ahora escanea el producto nuevo que entregarás al cliente");openScanner()}
   return
  }
  if(correctionStage==2)askCorrectionReason(value)
 }
 private fun askCorrectionReason(newCode:String?){
  val reason=input("Describe el motivo de la corrección")
  val all=Store.sales(this);var original:JSONObject?=null
  for(i in 0 until all.length()){val row=all.optJSONObject(i)?:continue;if(row.optString("ticketId")==correctionTicketId&&row.optString("type","SALE")=="SALE"&&row.optString("code")==correctionOriginalCode){original=row;break}}
  val oldPrice=original?.optDouble("price",0.0)?:0.0
  var newPrice=0.0;var newName=""
  if(newCode!=null){val products=Store.products(this);for(i in 0 until products.length()){val item=products.optJSONObject(i)?:continue;if(item.optString("code")==newCode){newPrice=item.optDouble("price");newName=item.optString("name");break}}}
  val summary=if(newCode==null)"PRODUCTO: "+(original?.optString("name")?:"-")+"\nVALOR A DEVOLVER: "+Store.money(oldPrice)+"\nEl producto regresará al inventario." else "PRODUCTO ORIGINAL: "+(original?.optString("name")?:"-")+"\nVALOR ORIGINAL: "+Store.money(oldPrice)+"\nPRODUCTO NUEVO: "+newName+"\nVALOR NUEVO: "+Store.money(newPrice)+"\n"+if(newPrice>oldPrice)"DIFERENCIA A COBRAR: "+Store.money(newPrice-oldPrice) else if(newPrice<oldPrice)"DIFERENCIA A DEVOLVER: "+Store.money(oldPrice-newPrice) else "SIN DIFERENCIA DE PRECIO"
  val damaged=CheckBox(this).apply{text="El producto está dañado (no regresa al inventario disponible)";textSize=13f;setTextColor(0xFFB3261E.toInt());isChecked=false;visibility=if(newCode==null)View.VISIBLE else View.GONE};val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8));addView(TextView(this@MainActivity).apply{text=summary;textSize=13f;setTextColor(0xFF263D70.toInt());setPadding(0,0,0,dp(10))});addView(damaged);addView(TextView(this@MainActivity).apply{text="MOTIVO DE LA CORRECCIÓN";textSize=12f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF53617A.toInt())});addView(reason)}
  val dialog=AlertDialog.Builder(this).setTitle("MOTIVO DE LA CORRECCIÓN").setView(content)
   .setPositiveButton("CONFIRMAR",null).setNegativeButton("CANCELAR"){_,_->correctionStage=0}.create()
  dialog.setOnShowListener{
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
    if(reason.text.isBlank()){reason.error="Describe el motivo"}else{val detail=reason.text.toString().trim();dialog.dismiss();applyTicketCorrection(newCode,detail,damaged.isChecked)}
   }
  }
  dialog.show()
 }
 private fun applyTicketCorrection(newCode:String?,reason:String,damaged:Boolean=false){
  val sales=Store.sales(this);var original:JSONObject?=null
  for(i in 0 until sales.length()){val row=sales.optJSONObject(i)?:continue;if(row.optString("ticketId")==correctionTicketId&&row.optString("type","SALE")=="SALE"&&row.optString("code")==correctionOriginalCode){original=row;break}}
  val sold=original?:run{msg("No se pudo recuperar el producto original");correctionStage=0;return}
  val oldPrice=sold.optDouble("price");val products=Store.products(this)
  var oldProduct:JSONObject?=null;var newProduct:JSONObject?=null
  for(i in 0 until products.length()){val item=products.optJSONObject(i)?:continue;if(item.optString("code")==correctionOriginalCode)oldProduct=item;if(newCode!=null&&item.optString("code")==newCode)newProduct=item}
  if(oldProduct==null){msg("El producto original ya no existe en el inventario");correctionStage=0;return}
  if(newCode!=null&&(newProduct==null||newProduct!!.optInt("stock")<1)){msg("El producto nuevo no existe o no tiene existencia");correctionStage=0;return}
  if(newCode==correctionOriginalCode){msg("Seleccionaste el mismo producto. No hay cambios que registrar.");correctionStage=0;return}
  if(newCode!=null || !damaged)oldProduct!!.put("stock",oldProduct!!.optInt("stock")+1)
  if(newCode==null && damaged){val prefs=getSharedPreferences("sene_ventas_local",0);val damagedList=try{JSONArray(prefs.getString("damaged_products","[]")?:"[]")}catch(_:Exception){JSONArray()};damagedList.put(JSONObject().put("code",correctionOriginalCode).put("name",sold.optString("name")).put("qty",1).put("price",oldPrice).put("time",stamp()).put("reason",reason).put("ticketId",correctionTicketId).put("worker",(Store.shift(this)?:JSONObject()).optString("worker")));prefs.edit().putString("damaged_products",damagedList.toString()).apply()}
  val now=System.currentTimeMillis();val time=stamp();val worker=(Store.shift(this)?:JSONObject()).optString("worker")
  val movements=ArrayList<JSONObject>()
  if(newProduct==null){
   movements.add(JSONObject().put("ts",now).put("time",time).put("ticketId",correctionTicketId).put("type","SALE_DELETE").put("sourceCode",correctionOriginalCode).put("code",correctionOriginalCode).put("name","DEVOLUCIÓN · "+sold.optString("name")).put("qty",1).put("price",oldPrice).put("subtotal",-oldPrice).put("reason",reason).put("worker",worker).put("employeeId",Store.shift(this)?.optString("employeeId")))
  }else{
   newProduct!!.put("stock",newProduct!!.optInt("stock")-1);val newPrice=newProduct!!.optDouble("price")
   movements.add(JSONObject().put("ts",now).put("time",time).put("ticketId",correctionTicketId).put("type","SALE_REPLACE_OLD").put("sourceCode",correctionOriginalCode).put("code",correctionOriginalCode).put("name","DEVOLUCIÓN · "+sold.optString("name")).put("qty",1).put("price",oldPrice).put("subtotal",-oldPrice).put("reason",reason).put("worker",worker))
   movements.add(JSONObject().put("ts",now).put("time",time).put("ticketId",correctionTicketId).put("type","SALE_REPLACE_NEW").put("sourceCode",correctionOriginalCode).put("code",newCode).put("name","CAMBIO POR · "+newProduct!!.optString("name")).put("qty",1).put("price",newPrice).put("subtotal",newPrice).put("reason",reason).put("worker",worker))
  }
  Store.saveProducts(this,products);Store.addSales(this,movements)
  val difference=if(newProduct==null)-oldPrice else newProduct!!.optDouble("price")-oldPrice
  correctionStage=0
  val message=if(newProduct==null&&damaged)"Producto registrado en PRODUCTOS DAÑADOS. No regresó al inventario disponible. Devolver al cliente: "+Store.money(oldPrice) else if(newProduct==null)"Producto devuelto al inventario. Devolver al cliente: "+Store.money(oldPrice) else if(difference>0)"Cobrar al cliente: "+Store.money(difference) else if(difference<0)"Devolver al cliente: "+Store.money(-difference) else "No hay diferencia de precio."
  AlertDialog.Builder(this).setTitle("CORRECCIÓN REGISTRADA").setMessage(message+"\n\nInventario, caja y reporte actualizados.").setPositiveButton("ACEPTAR",null).show()
 }
 private fun showTicketQr(ticketId:String,total:Double,items:List<JSONObject>){
  try{
   val matrix=MultiFormatWriter().encode(ticketId,BarcodeFormat.QR_CODE,560,560)
   val pixels=IntArray(560*560)
   for(y in 0 until 560)for(x in 0 until 560)pixels[y*560+x]=if(matrix.get(x,y))0xFF111111.toInt() else 0xFFFFFFFF.toInt()
   val bitmap=Bitmap.createBitmap(pixels,560,560,Bitmap.Config.ARGB_8888)
   val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(16),dp(8),dp(16),dp(8))}
   panel.addView(TextView(this).apply{text="TICKET REGISTRADO";textSize=16f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF1C315B.toInt());gravity=Gravity.CENTER})
   panel.addView(TextView(this).apply{text="TOTAL: "+Store.money(total);textSize=20f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF168B45.toInt());gravity=Gravity.CENTER;setPadding(0,dp(8),0,dp(6))})
   panel.addView(ImageView(this).apply{setImageBitmap(bitmap);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER})
   panel.addView(TextView(this).apply{text="Conserva este código QR. Se necesita para corregir esta venta.";textSize=12f;setTextColor(0xFF65718A.toInt());gravity=Gravity.CENTER;setPadding(0,dp(6),0,0)})
   AlertDialog.Builder(this).setTitle("COMPROBANTE SENE").setView(panel).setPositiveButton("CERRAR",null).setNeutralButton("IMPRIMIR TICKET"){_,_->
    if(getSharedPreferences("sene_ventas_local",0).getString("printer_address",null).isNullOrBlank())showPrinterSetup() else printReceiptIfConfigured(ticketId,total,items)
   }.show()
  }catch(e:Exception){msg("Venta guardada. No se pudo generar el QR en pantalla: "+(e.message?:"error"))}
 }
 private fun showWorkday(){val shift=Store.shift(this)?:run{msg("No hay una jornada activa");return};val start=shift.optLong("startTs",0L);val hours=shift.optInt("workHours",configuredWorkHours(this));val end=shift.optLong("expectedEndTs",start+hours*3600000L);val remaining=(end-System.currentTimeMillis()).coerceAtLeast(0L);val h=remaining/3600000L;val m=(remaining%3600000L)/60000L;val text="TRABAJADOR: "+shift.optString("worker","-")+ "\nHORA DE ENTRADA: "+shift.optString("started","-")+"\nJORNADA: "+hours+" horas\nHORA PROGRAMADA DE SALIDA: "+shift.optString("expectedEnd",SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(end)))+"\n"+if(remaining>0)"TIEMPO RESTANTE: "+String.format(Locale.US,"%02d h %02d min",h,m) else "JORNADA COMPLETADA. Ya puedes finalizar el turno.";AlertDialog.Builder(this).setTitle("MI JORNADA LABORAL").setMessage(text).setPositiveButton("ENTENDIDO",null).show()}
 private fun checkScheduledWorkdayEnd(){
  val shift=Store.shift(this)?:return
  val end=shift.optLong("expectedEndTs",shift.optLong("startTs",0L)+shift.optInt("workHours",configuredWorkHours(this))*3600000L)
  val now=System.currentTimeMillis()
  if(end<=0L||now<end||shift.optBoolean("finishPending",false))return
  val lastPrompt=shift.optLong("overtimePromptAt",0L)
  if(lastPrompt>0L&&now-lastPrompt<900000L)return
  showScheduledFinishPrompt(shift)
 }
 private fun showScheduledFinishPrompt(shift:JSONObject){
  if(overtimeDialog?.isShowing==true)return
  val dialog=AlertDialog.Builder(this)
   .setTitle("JORNADA LABORAL TERMINADA")
   .setMessage("Tu jornada laboral ha terminado. ¿Deseas finalizar el turno o tienes pendientes aún?")
   .setCancelable(false)
   .setPositiveButton("FINALIZAR TURNO",null)
   .setNegativeButton("TERMINAR PENDIENTES",null)
   .create()
  overtimeDialog=dialog
  dialog.setOnShowListener{
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
    val active=Store.shift(this)?:run{dialog.dismiss();return@setOnClickListener}
    val now=System.currentTimeMillis()
    val expected=active.optLong("expectedEndTs",active.optLong("startTs",0L)+active.optInt("workHours",configuredWorkHours(this))*3600000L)
    val worked=now-active.optLong("startTs",0L)
    val completed=active.optLong("startTs",0L)>0L&&worked>=expected-active.optLong("startTs",0L)
    active.put("closingType","SCHEDULED").put("closingBy","TRABAJADOR")
     .put("closingTs",now).put("closed",SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(now)))
     .put("completedWorkday",completed).put("finishPending",true)
    Store.saveShift(this,active)
    dialog.dismiss()
    reportDialog?.dismiss()
    sell.isEnabled=false;sell.alpha=0.45f
    if(::countdownView.isInitialized)countdownView.text="CIERRE DE TURNO EN PROCESO"
    sendReport(true)
   }
   dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{
    val active=Store.shift(this)
    active?.put("overtimePromptAt",System.currentTimeMillis())?.let{Store.saveShift(this,it)}
    dialog.dismiss()
   }
  }
  dialog.setOnDismissListener{if(overtimeDialog===dialog)overtimeDialog=null}
  dialog.show()
 }
 private fun requestFinishWorkday(){
  val shift=Store.shift(this)?:return
  val start=shift.optLong("startTs",0L)
  val end=shift.optLong("expectedEndTs",start+shift.optInt("workHours",configuredWorkHours(this))*3600000L)
  if(System.currentTimeMillis()>=end){showScheduledFinishPrompt(shift);return}
  val left=end-System.currentTimeMillis()
  val hh=left/3600000L;val mm=(left%3600000L)/60000L
  AlertDialog.Builder(this).setTitle("FINALIZAR TURNO")
   .setMessage("Aún no puedes finalizar la jornada programada. Te faltan "+String.format(Locale.US,"%02d h %02d min",hh,mm)+".")
   .setPositiveButton("ENTENDIDO",null).show()
 }
 private fun requestEarlyProfileChange(){
  val shift=Store.shift(this)?:return
  val reason=input("Especifica el motivo obligatorio",false).apply{inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES}
  val dialog=AlertDialog.Builder(this).setTitle("CAMBIAR DE PERFIL")
   .setMessage("Al continuar se cerrará tu turno actual, se generará tu reporte y se avisará al patrón por WhatsApp. Este cierre se reflejará en tu nómina.")
   .setView(reason).setPositiveButton("CONTINUAR",null).setNegativeButton("CANCELAR",null).create()
  dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{
   val why=reason.text.toString().trim()
   if(why.isBlank()){reason.error="El motivo es obligatorio";return@setOnClickListener}
   val now=System.currentTimeMillis()
   val expected=shift.optLong("expectedEndTs",shift.optLong("startTs",now)+shift.optInt("workHours",configuredWorkHours(this))*3600000L)
   shift.put("closingReason",why).put("closingType","EARLY_EXIT").put("closingBy","TRABAJADOR")
    .put("earlyExit",now<expected).put("closingTs",now).put("closed",SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(now)))
   Store.saveShift(this,shift)
   dialog.dismiss()
   sendReport(true)
  }}
  dialog.show()
 }
 private fun requestAdminForceClose(){
  val shift=Store.shift(this)?:run{msg("No hay un turno activo para cerrar.");return}
  val reason=input("Motivo obligatorio del cierre administrativo",true)
  val dialog=AlertDialog.Builder(this).setTitle("FORZAR CIERRE DE TURNO")
   .setMessage("Trabajador activo: "+shift.optString("worker")+"\nEl cierre quedará identificado como realizado por el patrón y se reflejará en la nómina.")
   .setView(reason).setPositiveButton("CERRAR TURNO",null).setNegativeButton("CANCELAR",null).create()
  dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{
   val why=reason.text.toString().trim()
   if(why.isBlank()){reason.error="El motivo es obligatorio";return@setOnClickListener}
   val now=System.currentTimeMillis()
   val expected=shift.optLong("expectedEndTs",shift.optLong("startTs",now)+shift.optInt("workHours",configuredWorkHours(this))*3600000L)
   shift.put("closingReason",why).put("closingType","ADMIN_FORCED").put("closingBy","PATRÓN")
    .put("earlyExit",now<expected).put("closingTs",now).put("closed",SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(now)))
   Store.saveShift(this,shift)
   dialog.dismiss()
   sendReport(true)
  }}
  dialog.show()
 }
 private fun showMyTurnMenu(){
  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
  val dialog=AlertDialog.Builder(this).setTitle("MI TURNO").setView(ScrollView(this).apply{addView(panel)}).setNegativeButton("CERRAR",null).create()
  fun option(title:String,description:String,action:()->Unit){
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(13));background=rounded(0xFFFFFFFF.toInt(),16,0xFFDCE5F2.toInt());isClickable=true;isFocusable=true;elevation=dp(1).toFloat()}
   card.addView(TextView(this).apply{text=title;textSize=15f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF1C315B.toInt())})
   card.addView(TextView(this).apply{text=description;textSize=12f;setTextColor(0xFF74809A.toInt());setPadding(0,dp(4),0,0)})
   panel.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
   card.setOnClickListener{dialog.dismiss();action()}
  }
  option("SOLICITA","Registrar un consumo o solicitar un préstamo"){startActivity(Intent(this,PayrollRequestActivity::class.java))}
  option("MI NÓMINA","Consultar sueldo, descuentos e historial"){startActivity(Intent(this,PayrollEmployeeActivity::class.java))}
  option("MI JORNADA LABORAL","Consultar entrada, salida estimada y tiempo restante"){showWorkday()}
  option("CAMBIAR DE PERFIL","Cerrar tu turno antes de tiempo, indicar el motivo y avisar al patrón"){requestEarlyProfileChange()}
  dialog.show()
 }
 private fun showToolsMenu(){
  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(10),dp(16),dp(12));setBackgroundColor(0xFFF4F6FB.toInt())}
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(panel)}
  val dialog=AlertDialog.Builder(this).setTitle("HERRAMIENTAS SENE").setView(scroll).setNegativeButton("CERRAR",null).create()
  val items=listOf(
   Triple("🖨  CONECTE SU IMPRESORA","Configurar Bluetooth e imprimir un ticket de prueba",10),
   Triple("CORREGIR VENTA","Escanear ticket para cambiar o eliminar un producto",11),
   Triple("VENTAS DEL TURNO","Resumen de ventas, piezas y caja",0),
   Triple("AGREGAR INVENTARIO","Registrar mercancía recibida del proveedor",1),
   Triple("GASTOS A PROVEEDORES","Consultar pagos y salidas de caja",2),
   Triple("VER STOCK","Consultar existencias, precios y alertas",3),
   Triple("PRODUCTOS DAÑADOS","Revisar devoluciones que no pueden volver a venderse",12),
   Triple("SOY PATRÓN / ADMINISTRADOR","Administrar inventario y configuración",4),
   Triple("GUARDAR REPORTE TXT","Exportar el reporte organizado",6)
  )
  for((label,description,action) in items){
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(13),dp(16),dp(13));background=rounded(0xFFFFFFFF.toInt(),16,0xFFDCE3F0.toInt());isClickable=true;isFocusable=true;elevation=dp(1).toFloat()}
   card.addView(TextView(this).apply{text=label;textSize=15f;setTextColor(0xFF1C315B.toInt());typeface=Typeface.create("sans-serif",Typeface.BOLD);letterSpacing=.025f})
   card.addView(TextView(this).apply{text=description;textSize=12f;setTextColor(0xFF74809A.toInt());setPadding(0,dp(5),0,0)})
   val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(9);panel.addView(card,lp)
   card.setOnClickListener{dialog.dismiss();when(action){
    0->showCurrentSalesSummary()
    1->startActivity(Intent(this,AgregarInventarioActivity::class.java))
    2->showProviderExpenses()
    3->stock()
    4->pin{refreshAfterPatron=true;startActivity(Intent(this,PatronActivity::class.java))}
    6->saveReportToPhone()
    10->showPrinterSetup()
    11->beginTicketCorrection()
    12->showDamagedProducts()
   }}
  }
  dialog.show()
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
  val loanExpenses=Store.moves(this).let{moves->(0 until moves.length()).sumOf{i->val m=moves.optJSONObject(i);if(m?.optString("type")=="LOAN_DISBURSEMENT"&&m.optLong("ts",0L)>=startTs)m.optDouble("amount",0.0)else 0.0}}
  val estimated=opening+shiftTotal-providerExpenses-loanExpenses
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
 private fun showDamagedProducts(){val prefs=getSharedPreferences("sene_ventas_local",0);val items=try{JSONArray(prefs.getString("damaged_products","[]")?:"[]")}catch(_:Exception){JSONArray()};val lines=ArrayList<String>();for(i in 0 until items.length()){val x=items.optJSONObject(i)?:continue;lines.add(x.optString("time")+"\n"+x.optString("name")+" · Código: "+x.optString("code")+"\nCantidad: "+x.optInt("qty",1)+" · Motivo: "+x.optString("reason"))};AlertDialog.Builder(this).setTitle("PRODUCTOS DAÑADOS").setMessage(if(lines.isEmpty())"No hay productos dañados registrados." else lines.asReversed().joinToString("\n\n")).setPositiveButton("CERRAR",null).show()}
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
  val linkButton=button("VINCULAR SENE VENTAS",false){startActivity(Intent(this,ActivationActivity::class.java))};v.addView(linkButton);hideLinkButtonWhenActive(this,linkButton)
  v.addView(button("ESCANEAR PRODUCTO"){openScanner()})
  code=input("Código de barras");v.addView(code)
  v.addView(button("AGREGAR AL TICKET",false){addCode(code.text.toString());code.setText("")})
  ticketView=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));background=rounded(0xFFFFFFFF.toInt(),14,0xFFE2E6F0.toInt())};v.addView(ticketView)
  paid=input("¿Con cuánto te pagan?",true);v.addView(paid)
  totalView=txt("TOTAL: $0.00\nCambio: $0.00 | Falta: $0.00",22f);v.addView(totalView)
  paid.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,d:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){refresh()};override fun afterTextChanged(s:android.text.Editable?) {}})
  sell=button("COBRAR",false){if(parseAmount(paid.text.toString())==null){paid.requestFocus();paid.background=rounded(0xFFFFF1F0.toInt(),14,0xFFE53935.toInt());paid.error="Introduce con cuánto te pagan";msg("Introduce con cuánto te pagan para continuar");return@button};sellTicket()};v.addView(sell)
  v.addView(button("CORRECCIÓN DE VENTA",false){beginTicketCorrection()})
  Protection.addBlindButton(this,v)
  countdownView=txt("",13f).apply{gravity=Gravity.CENTER;setTypeface(null,Typeface.BOLD);setPadding(dp(8),dp(9),dp(8),dp(9))};v.addView(countdownView)
  Store.shift(this)?.let{shift->
   val start=shift.optLong("startTs",0L);val end=shift.optLong("expectedEndTs",start+shift.optInt("workHours",configuredWorkHours(this))*3600000L)
   val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(12),dp(15),dp(12));background=rounded(0xFFF7F9FE.toInt(),15,0xFFDCE5F2.toInt())}
   info.addView(TextView(this).apply{text="COMENZASTE TU TURNO A";textSize=11f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF71809A.toInt())})
   info.addView(TextView(this).apply{text=shift.optString("started","—");textSize=17f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF24569A.toInt());setPadding(0,dp(3),0,dp(10))})
   info.addView(TextView(this).apply{text="DEBERÍAS SALIR A";textSize=11f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF71809A.toInt())})
   info.addView(TextView(this).apply{text=shift.optString("expectedEnd",SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date(end)));textSize=17f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF16804A.toInt());setPadding(0,dp(3),0,0)})
   v.addView(info)
  }
  v.addView(powerButton("FINALIZAR TURNO"){requestFinishWorkday()})
  v.addView(button("MI TURNO",false){showMyTurnMenu()})
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
    val current=ticket[c]?:0;if(current>=x.optInt("stock")){msg("No puedes agregar más unidades. Stock disponible: "+x.optInt("stock"));return};ticket[c]=current+1;refresh();return
   }
  }
  offerSupplierInventory(c,null)
 }
 private fun refresh(){if(!::ticketView.isInitialized)return;val p=Store.products(this);ticketView.removeAllViews();var sum=0.0;if(ticket.isEmpty())ticketView.addView(TextView(this).apply{text="Ticket vacío";textSize=16f;setTextColor(0xFF66708A.toInt());setPadding(dp(8),dp(8),dp(8),dp(8))});for((c,q) in ticket.toMap()){var product:JSONObject?=null;for(i in 0 until p.length()){val x=p.optJSONObject(i)?:continue;if(x.optString("code")==c){product=x;break}};val x=product?:continue;val sub=x.optDouble("price")*q;sum+=sub;val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),dp(6),dp(4),dp(6))};val name=TextView(this).apply{text=x.optString("name")+"\n"+Store.money(x.optDouble("price"))+" c/u";textSize=13f;setTextColor(0xFF263D70.toInt())};row.addView(name,LinearLayout.LayoutParams(0,-2,1f));val minus=Button(this).apply{text="−";textSize=18f;minWidth=dp(42);setOnClickListener{val n=(ticket[c]?:0)-1;if(n<=0)ticket.remove(c)else ticket[c]=n;refresh()}};row.addView(minus);row.addView(TextView(this).apply{text=q.toString();textSize=16f;gravity=Gravity.CENTER;setTextColor(0xFF17213D.toInt());setPadding(dp(8),0,dp(8),0)});val plus=Button(this).apply{text="+";textSize=18f;minWidth=dp(42);setOnClickListener{val current=ticket[c]?:0;val stock=x.optInt("stock");if(current>=stock)msg("No puedes agregar más unidades. Stock disponible: $stock")else{ticket[c]=current+1;refresh()}}};row.addView(plus);ticketView.addView(row);ticketView.addView(View(this).apply{setBackgroundColor(0xFFE7EAF1.toInt())},LinearLayout.LayoutParams(-1,dp(1)))};val entered=parseAmount(paid.text.toString());val diff=(entered?:0.0)-sum;totalView.text="TOTAL: "+Store.money(sum)+"\n"+if(diff>=0)"Cambio a dar: "+Store.money(diff) else "Falta: "+Store.money(-diff);if(::sell.isInitialized){val ready=entered!=null&&entered>=sum&&sum>0.0&&!due();sell.text="COBRAR";sell.background=if(ready)GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF39F36B.toInt(),0xFF00C853.toInt())).apply{cornerRadius=dp(14).toFloat()}else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFFE53935.toInt(),0xFFB71C1C.toInt())).apply{cornerRadius=dp(14).toFloat()};sell.setTextColor(0xFFFFFFFF.toInt());sell.isEnabled=!due();sell.alpha=if(due())0.45f else 1f};if(::paid.isInitialized&&entered!=null)paid.background=rounded(0xFFFFFFFF.toInt(),14,0xFFDCE2EF.toInt())}
 private fun sellTicket(){
  if(due()){showReportAlert();return}
  if(ticket.isEmpty()){msg("Ticket vacío. Escanea un producto.");return}
  val products=Store.products(this);val list=mutableListOf<Pair<JSONObject,Int>>();var total=0.0
  for((productCode,quantity) in ticket){
   var found:JSONObject?=null
   for(i in 0 until products.length()){val item=products.optJSONObject(i);if(item?.optString("code")==productCode){found=item;break}}
   if(found==null){msg("Producto no encontrado: "+productCode);return}
   if(found.optInt("stock")<quantity){msg("Stock insuficiente: "+found.optString("name")+". Quedan "+found.optInt("stock"));return}
   list.add(found to quantity);total+=found.optDouble("price")*quantity
  }
  val payment=parseAmount(paid.text.toString())?:0.0
  if(payment<total){msg("Falta dinero");return}
  val change=payment-total;val availableCash=Store.cashBalance(this)
  if(change>availableCash+0.0001){AlertDialog.Builder(this).setTitle("NO HAY CAMBIO SUFICIENTE").setMessage("No tienes efectivo suficiente para entregar el cambio.\n\nCambio que debes entregar: "+Store.money(change)+"\nEfectivo disponible en caja: "+Store.money(availableCash)+"\n\nInforma al cliente: «Disculpe, por el momento no tengo cambio suficiente».\n\nLa venta no se realizará.").setPositiveButton("ACEPTAR",null).show();return}
  val shift=Store.shift(this)?:JSONObject();val saleTime=stamp();val saleTs=System.currentTimeMillis()
  val ticketId="SENE-TICKET:"+UUID.randomUUID().toString();val saleItems=ArrayList<JSONObject>()
  for((item,quantity) in list){
   item.put("stock",item.optInt("stock")-quantity)
   saleItems.add(JSONObject().put("ts",saleTs).put("time",saleTime).put("ticketId",ticketId)
    .put("code",item.optString("code")).put("name",item.optString("name")).put("qty",quantity)
    .put("price",item.optDouble("price")).put("subtotal",item.optDouble("price")*quantity)
    .put("worker",shift.optString("worker")).put("employeeId",shift.optString("employeeId")).put("type","SALE"))
  }
  Store.saveProducts(this,products);Store.addSales(this,saleItems)
  val count=list.size
  ticket.clear();paid.setText("")
  showTicketQr(ticketId,total,saleItems)
  msg("Venta guardada: "+count+" productos | Total "+Store.money(total)+" | Cambio "+Store.money(change))
  draw()
 }
 private fun reportText(final:Boolean):String{
  val shift=Store.shift(this)?:JSONObject();val all=Store.sales(this)
  val out=StringBuilder("SENE VENTAS  •  "+if(final)"CIERRE DE TURNO" else "REPORTE DE TURNO")
  out.append("\n━━━━━━━━━━━━━━━━━━━━").append("\nETIQUETA: DATOS DEL TURNO")
   .append("\nTRABAJADOR: ").append(shift.optString("worker","-"))
   .append("\nFECHA Y HORA: ").append(stamp())
   .append("\nINICIO: ").append(shift.optString("started","-"))
   .append("\nCAJA INICIAL: ").append(Store.money(shift.optDouble("cash")))
   .append("\n\nETIQUETA: MOVIMIENTOS DE VENTA")
  var total=0.0
  for(i in 0 until all.length()){
   val sale=all.optJSONObject(i)?:continue
   if(sale.optLong("ts")>=shift.optLong("startTs",0L) && (sale.optString("employeeId")==shift.optString("employeeId") || sale.optString("employeeId").isBlank())){
    val amount=sale.optDouble("subtotal");total+=amount
    val kind=sale.optString("type","SALE")
    val label=when(kind){"SALE_DELETE"->"DEVOLUCIÓN";"SALE_REPLACE_OLD"->"PRODUCTO DEVUELTO";"SALE_REPLACE_NEW"->"PRODUCTO ENTREGADO";else->"VENTA"}
    out.append("\n[").append(label).append("] ").append(sale.optString("time"))
     .append("\n").append(sale.optString("name")).append(" x").append(sale.optInt("qty",1))
     .append("  |  ").append(Store.money(amount))
    if(sale.optString("reason").isNotBlank())out.append("\nMOTIVO: ").append(sale.optString("reason"))
   }
  }
  val expenses=Store.providerExpenses(this,shift.optLong("startTs",0L),shift.optString("employeeId").takeIf{it.isNotBlank()})
  out.append("\n\nETIQUETA: RESUMEN DE CAJA")
   .append("\nVENTAS NETAS: ").append(Store.money(total))
   .append("\nPAGOS A PROVEEDORES: ").append(Store.money(expenses))
   .append("\nEFECTIVO ESTIMADO: ").append(Store.money(shift.optDouble("cash")+total-expenses))
   .append("\n\nETIQUETA: EXISTENCIAS ACTUALES")
  val products=Store.products(this)
  for(i in 0 until products.length()){
   val item=products.optJSONObject(i)?:continue
   out.append("\n").append(item.optString("name")).append(" | COD: ").append(item.optString("code"))
    .append(" | PRECIO: ").append(Store.money(item.optDouble("price")))
    .append(" | STOCK: ").append(item.optInt("stock"))
  }
  return out.toString()
 }
 private fun sendReport(final:Boolean){
  if(Store.shift(this)==null)return
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(saved.isNullOrBlank()){
   pendingFinalReport=final
   AlertDialog.Builder(this).setTitle("Elegir carpeta para reportes")
    .setMessage("La primera vez, elige dónde guardar los reportes. Recomendamos crear una carpeta llamada “Inventario SENE” y seleccionarla. SENE-VENTAS organizará automáticamente los documentos en carpetas por fecha.")
    .setPositiveButton("ELEGIR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,TREE_REPORT_REQUEST)}
    .setNegativeButton("Cancelar"){_,_->if(final){Store.shift(this)?.let{active->active.put("finishPending",false);active.remove("closingType");active.remove("closingBy");active.remove("closingTs");active.remove("closed");active.remove("completedWorkday");Store.saveShift(this,active)};pendingFinalReport=false;h.post{if(::sell.isInitialized){sell.isEnabled=true;sell.alpha=1f};updateReportCountdown()}}}.show()
   return
  }
  completeReport(final,Uri.parse(saved))
 }
 private var pendingReportText:String?=null
 private var pendingReportEventKey:String=""
 private fun saveReportToPhone(){
  val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
   addCategory(Intent.CATEGORY_OPENABLE)
   type="text/plain"
   putExtra(Intent.EXTRA_TITLE,"SENE-VENTAS-REPORTE.txt")
  }
  try{startActivityForResult(intent,SAVE_REPORT_REQUEST)}catch(e:Exception){msg("No se pudo abrir el selector para guardar el reporte TXT")}
 }
 private fun completeReport(final:Boolean,tree:Uri){
  if(reportProcessing)return
  reportProcessing=true
  if(final){sell.isEnabled=false;sell.alpha=0.45f;if(::countdownView.isInitialized)countdownView.text="PREPARANDO REPORTE…"}
  Thread{
   val s=Store.shift(this@MainActivity)
   if(s==null){runOnUiThread{reportProcessing=false};return@Thread}
   try{
   if(final && s.optLong("closingTs",0L)==0L){s.put("closingTs",System.currentTimeMillis()).put("closed",stamp());Store.saveShift(this,s)}
   // Publicar el stock compartido como nuevo inventario maestro y conservar la copia individual.
   val currentProducts=Store.products(this)
   Store.publishMasterProducts(this,currentProducts)
   InventoryArchive.save(this,currentProducts)
   InventoryArchive.save(this,currentProducts,s.optString("employeeId").takeIf{it.isNotBlank()},s.optString("worker"))
   val file=ReportFile.update(this)
   val closureNote=when(s.optString("closingType")){
    "EARLY_EXIT"->"🗣 CIERRE ANTISIPADO 💢\nTRABAJADOR: "+s.optString("worker")+"\nMOTIVO: "+s.optString("closingReason")+"\nHORA DE CIERRE: "+s.optString("closed")+"\n"
    "ADMIN_FORCED"->"CIERRE ADMINISTRATIVO FORZADO\nTRABAJADOR: "+s.optString("worker")+"\nMOTIVO: "+s.optString("closingReason")+"\nHORA DE CIERRE: "+s.optString("closed")+"\nREALIZADO POR: PATRÓN\n"
    else->""
   }
   val employeeId=s.optString("employeeId")
   val payrollConfig=EmployeePayroll.config(this,employeeId)
   val payrollSummary=if(final && payrollConfig!=null){
    val period=EmployeePayroll.periodKey(System.currentTimeMillis(),payrollConfig.payDay)
    val attended=EmployeePayroll.attendance(this).filter{it.employeeId==employeeId&&it.date>=period&&it.date<=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())&&it.startedAt>0L}
    val workedDays=attended.map{it.date}.distinct().size
    val absences=EmployeePayroll.absences(this,employeeId)
    val entries=EmployeePayroll.entriesForCurrentPeriod(this,employeeId)
    val loans=entries.filter{it.type==EmployeePayroll.TYPE_LOAN}.sumOf{it.amountCents}
    val consumption=entries.filter{it.type==EmployeePayroll.TYPE_CONSUMPTION}.sumOf{it.amountCents}
    "\n\nDESGLOSE PARA NÓMINA SENE ADMIN\nPERÍODO DESDE: "+period+
     "\nDÍAS LABORABLES POR SEMANA: "+payrollConfig.workDays+
     "\nDÍAS TRABAJADOS REGISTRADOS: "+workedDays+
     "\nFALTAS REGISTRADAS HASTA EL CIERRE: "+absences+
     "\nSUELDO SEMANAL: "+EmployeePayroll.money(payrollConfig.weeklySalaryCents)+
     "\nDÍA DE PAGO: "+listOf("","Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado").getOrElse(payrollConfig.payDay){"-"}+
     "\nPRÉSTAMOS PENDIENTES: "+EmployeePayroll.money(loans)+
     "\nCONSUMOS PENDIENTES: "+EmployeePayroll.money(consumption)+
     "\nTIPO DE CIERRE: "+s.optString("closingType","CIERRE NORMAL")+
     "\nQUIÉN CERRÓ: "+s.optString("closingBy","TRABAJADOR")+
     "\nHORA DE ENTRADA: "+s.optString("started","No indicada")+
     "\nHORA DE SALIDA: "+s.optString("closed","No indicada")+
     "\nMOTIVO DE CIERRE: "+s.optString("closingReason","No aplica")
   }else ""
   val inventoryBlock=if(final){
    val inventory=StringBuilder("\n\nINVENTARIO AL FINALIZAR TURNO\nCódigo | Producto | Precio | Existencia | Pasillo | Estante")
    for(i in 0 until currentProducts.length()){
     val item=currentProducts.optJSONObject(i)?:continue
     inventory.append("\n").append(item.optString("code")).append(" | ").append(item.optString("name"))
      .append(" | ").append(Store.money(item.optDouble("price"))).append(" | ").append(item.optInt("stock"))
      .append(" | ").append(item.optString("aisle")).append(" | ").append(item.optString("shelf"))
    }
    inventory.toString()
   }else ""
   val damagedItems=try{JSONArray(getSharedPreferences("sene_ventas_local",0).getString("damaged_products","[]")?:"[]")}catch(_:Exception){JSONArray()}
   val damagedBlock=if(damagedItems.length()>0){val out=StringBuilder("\n\nPRODUCTOS_DANADOS_SENE_V1\nCODIGO | PRODUCTO | CANTIDAD | FECHA | MOTIVO | TRABAJADOR");for(i in 0 until damagedItems.length()){val item=damagedItems.optJSONObject(i)?:continue;out.append("\n").append(item.optString("code")).append(" | ").append(item.optString("name")).append(" | ").append(item.optInt("qty",1)).append(" | ").append(item.optString("time")).append(" | ").append(item.optString("reason").replace("|","/")).append(" | ").append(item.optString("worker",s.optString("worker")))};out.toString()}else ""
   val body=(if(closureNote.isNotBlank())closureNote+"\n" else "")+file.readText(Charsets.UTF_8)+payrollSummary+inventoryBlock+damagedBlock+REPORT_BACKUP_NOTE
   file.writeText(body,Charsets.UTF_8)
   val dailyUri=saveDailyReport(tree,file,s.optString("employeeId"),s.optString("worker"))
   pendingShareUri=dailyUri
   val stableShiftId=s.optString("shiftId").ifBlank { UUID.randomUUID().toString().also { s.put("shiftId",it); Store.saveShift(this,s) } }
   val eventKey=if(final) "$stableShiftId:CLOSE" else "$stableShiftId:REPORT:${s.optInt("reportCount",0)}"
   pendingReportEventKey=eventKey
   pendingReportText=ReportCrypto.prepare(this,s.optString("employeeId"),if(final)"CIERRE_TURNO" else "REPORTE_TURNO",body,eventKey)
   // Guardar primero el mensaje cifrado en almacenamiento privado local de la APK.
   val encryptedFolder=java.io.File(getExternalFilesDir(null)?:filesDir,"REPORTES-CIFRADOS")
   if(!encryptedFolder.exists()&&!encryptedFolder.mkdirs())throw java.io.IOException("No se pudo crear la carpeta local de reportes cifrados")
   val safeEvent=eventKey.replace(Regex("[^A-Za-z0-9._-]"),"_")
   val encryptedFile=java.io.File(encryptedFolder,"$safeEvent.txt")
   encryptedFile.writeText(pendingReportText?:throw java.io.IOException("No se generó el reporte cifrado"),Charsets.UTF_8)
   if(!encryptedFile.isFile||encryptedFile.length()==0L)throw java.io.IOException("No se pudo verificar el reporte cifrado guardado")
   pendingFinalShare=final
   runOnUiThread{
    reportProcessing=false
    if(!isFinishing&&!isDestroyed)launchPendingReportShare()
   }
  }catch(e:Exception){
   runOnUiThread{
    reportProcessing=false
    if(final)Store.shift(this@MainActivity)?.let{active->active.put("finishPending",false);Store.saveShift(this@MainActivity,active)}
    if(!isFinishing&&!isDestroyed)msg("No se pudo guardar el reporte. No se cerró el turno ni se reactivaron las ventas: "+(e.message?:"error"))
   }
  }
  }.start()
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
    if(final){shift?.let{active->active.optString("employeeId").takeIf{id->id.isNotBlank()}?.let{id->EmployeePayroll.recordShiftEnd(this,id,active.optLong("closingTs",System.currentTimeMillis()),active.optString("closingReason"),active.optString("closingBy"),active.optBoolean("completedWorkday",false))}};Store.saveShift(this,null);if(pendingReportEventKey.isNotBlank())ReportCrypto.markSent(this,pendingReportEventKey);pendingReportEventKey="";ticket.clear();startActivity(Intent(this,InicioTurnoActivity::class.java));finish()}
    else if(shift!=null){shift.put("lastReport",System.currentTimeMillis()).put("reportReminderShown",false).put("reportCount",shift.optInt("reportCount",0)+1);Store.saveShift(this,shift);if(pendingReportEventKey.isNotBlank())ReportCrypto.markSent(this,pendingReportEventKey);pendingReportEventKey="";reportDialog?.dismiss();sell.isEnabled=true;sell.alpha=1f;updateReportCountdown();msg("Envío confirmado. Puedes continuar trabajando.")}
   }
   .setNegativeButton("VOLVER A ENVIAR"){_,_->confirmingReport=false;launchPendingReportShare()}
   .show()
 }
 private fun saveDailyReport(tree:Uri,source:java.io.File,employeeId:String="",workerName:String=""):Uri{
  val resolver=contentResolver
  val base=if(employeeId.isNotBlank())WorkerArchive.profileSection(this,tree,employeeId,workerName,"REPORTES") else DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
  val date=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
  fun findChild(parent:Uri,name:String):Uri?{
   val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
   resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{cur->
    val idCol=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);val nameCol=cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    while(cur.moveToNext())if(nameCol>=0&&idCol>=0&&cur.getString(nameCol)==name)return DocumentsContract.buildDocumentUriUsingTree(tree,cur.getString(idCol))
   }
   return null
  }
  val folder=findChild(base,date)?:DocumentsContract.createDocument(resolver,base,DocumentsContract.Document.MIME_TYPE_DIR,date)?:throw java.io.IOException("No se pudo crear la carpeta del día")
  val time=SimpleDateFormat("HH-mm-ss",Locale.US).format(Date())
  val name="SENE-VENTAS-$date-$time.txt"
  val target=DocumentsContract.createDocument(resolver,folder,"text/plain",name)?:throw java.io.IOException("No se pudo crear el TXT")
  resolver.openOutputStream(target,"w")?.use{it.write(encryptedReportBackup(this,source.readBytes()))}?:throw java.io.IOException("No se pudo escribir el TXT")
  return target
 }
 private fun stock(){
  val products=Store.products(this)
  val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(8));setBackgroundColor(0xFFF4F6FB.toInt())}
  val scroll=ScrollView(this).apply{isFillViewport=true;addView(panel)}
  var low=0;var total=0
  for(i in 0 until products.length()){
   val item=products.optJSONObject(i)?:continue;total++
   val quantity=item.optInt("stock");if(quantity<=5)low++
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=rounded(0xFFFFFFFF.toInt(),15,0xFFDCE3F0.toInt())}
   card.addView(TextView(this).apply{text=item.optString("name");textSize=16f;setTextColor(0xFF1B2E53.toInt());typeface=Typeface.create("sans-serif",Typeface.BOLD)})
   card.addView(TextView(this).apply{text="CÓDIGO  "+item.optString("code")+"   •   PRECIO  "+Store.money(item.optDouble("price"));textSize=12f;setTextColor(0xFF73809A.toInt());setPadding(0,dp(5),0,dp(7))})
   card.addView(TextView(this).apply{text="EXISTENCIA:  "+quantity+" "+if(quantity==1)"pieza" else "piezas";textSize=14f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(if(quantity<=5)0xFFB3261E.toInt() else 0xFF168B45.toInt());background=rounded(if(quantity<=5)0xFFFFEEEE.toInt() else 0xFFEAF6EF.toInt(),10);setPadding(dp(10),dp(8),dp(10),dp(8))})
   val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);panel.addView(card,lp)
  }
  panel.addView(TextView(this).apply{text="PRODUCTOS: "+total+"     •     STOCK BAJO: "+low;textSize=13f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF314B7C.toInt());setPadding(dp(5),dp(5),dp(5),dp(10))},0)
  AlertDialog.Builder(this).setTitle("INVENTARIO · EXISTENCIAS").setView(scroll).setPositiveButton("CERRAR",null).show()
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==TREE_REPORT_REQUEST&&c!=RESULT_OK){if(pendingFinalReport){pendingFinalReport=false;Store.shift(this)?.let{active->active.put("finishPending",false);active.remove("closingType");active.remove("closingBy");active.remove("closingTs");active.remove("closed");active.remove("completedWorkday");Store.saveShift(this,active)};h.post{if(::sell.isInitialized){sell.isEnabled=true;sell.alpha=1f};updateReportCountdown()}};return};if(r==TREE_REPORT_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();WorkerProfiles.all(this).forEach{WorkerArchive.ensureProfileFolders(this,it.id,it.name)};completeReport(pendingFinalReport,tree)}catch(e:Exception){msg("No se pudo guardar el acceso a la carpeta: "+(e.message?:"error"))};return};if(r==SAVE_REPORT_REQUEST&&c==RESULT_OK){val uri=d?.data?:return;try{val file=ReportFile.update(this);contentResolver.openOutputStream(uri)?.use{out->file.inputStream().use{it.copyTo(out)}};msg("Reporte TXT guardado en la ubicación que elegiste")}catch(e:Exception){msg("No se pudo guardar el TXT: "+(e.message?:"error"))};return};if(r==SCAN_REQUEST&&c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty()){val scanned=codes.last();when{scanningTicketCorrection->{scanningTicketCorrection=false;handleTicketCorrectionScan(scanned)};correctionStage>0->handleCorrectionProductScan(scanned);else->reviewScannedCodes(codes)}};return};val x=IntentIntegrator.parseActivityResult(r,c,d);if(x?.contents!=null)addCode(x.contents)}
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
  val backup=StringBuilder("INVENTARIO_SENE_V1\\nActualizado: ").append(stamp()).append("\\nCódigo | Producto | Precio | Existencia | Pasillo | Estante")
  for(i in 0 until p.length()){
   val item=p.optJSONObject(i)?:continue
   backup.append("\\n").append(item.optString("code")).append(" | ").append(item.optString("name")).append(" | ").append(item.optDouble("price")).append(" | ").append(item.optInt("stock")).append(" | ").append(item.optString("aisle")).append(" | ").append(item.optString("shelf"))
  }
  var savedInSharedFolder=false
  val tree=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(!tree.isNullOrBlank())try{savedInSharedFolder=InventoryArchive.save(this,p)}catch(_:Exception){}
  try{
   if(!savedInSharedFolder){val active=Store.shift(this);saveTextBackupLocal(this,"SENE-INGRESO-PROVEEDOR",backup.toString(),active?.optString("employeeId").orEmpty(),active?.optString("worker").orEmpty())}
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

class PatronActivity:FragmentActivity(){
 private var pendingInventorySave=false
 override fun onCreate(b:Bundle?){super.onCreate(b);enforceLicenseGate(this){if(intent.getBooleanExtra("authorized_from_home",false)){ensureOwnerKeyThenDraw()}else pin{if(currentPatronPin(this)==DEFAULT_PIN)forceDefaultPinChange{ensureOwnerKeyThenDraw()}else ensureOwnerKeyThenDraw()}}}
 private fun ensureOwnerKeyThenDraw(){ReportKeySetup.ensureConfigured(this){draw()}}
 private fun adminTag(label:String,sub:String,accent:Int=0xFF365BA4.toInt(),action:()->Unit):View {
  val tag=LinearLayout(this).apply{
   orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
   setPadding(dp(15),dp(13),dp(15),dp(13))
   background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFFFFFFFF.toInt(),0xFFF7F9FE.toInt())).apply{cornerRadius=dp(16).toFloat();setStroke(dp(1),0xFFDCE4F2.toInt())}
   elevation=dp(2).toFloat();isClickable=true;isFocusable=true
   setOnClickListener{action()}
  }
  val marker=View(this).apply{background=GradientDrawable().apply{setColor(accent);cornerRadius=dp(4).toFloat()}}
  tag.addView(marker,LinearLayout.LayoutParams(dp(5),dp(38)))
  val texts=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(8),0)}
  texts.addView(TextView(this).apply{text=label;textSize=14f;letterSpacing=.025f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF243557.toInt())})
  texts.addView(TextView(this).apply{text=sub;textSize=11f;setTextColor(0xFF78849D.toInt());setPadding(0,dp(4),0,0)})
  tag.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
  tag.addView(TextView(this).apply{text="›";textSize=27f;setTextColor(accent);gravity=Gravity.CENTER})
  tag.layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5);bottomMargin=dp(5)}
  return tag
 }
 private fun draw(){
  val v=page("Administrador - Patrón")
  v.addView(txt("ADMINISTRACIÓN DEL NEGOCIO",12f).apply{background=rounded(0xFFE9EFFB.toInt(),12);setTextColor(0xFF365BA4.toInt())})
  v.addView(adminTag("MIS TRABAJADORES","Perfiles, jornadas y sueldos"){startActivity(Intent(this,PayrollAdminActivity::class.java).putExtra("manage_workers",true))})
  Store.shift(this)?.let{active->v.addView(adminTag("FORZAR CIERRE DE TURNO","Turno activo: "+active.optString("worker")+" · Cierre administrativo",0xFFB3261E.toInt()){
   startActivity(Intent(this,MainActivity::class.java).putExtra("admin_force_close",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
   finish()
  })}
  v.addView(adminTag(if(getSharedPreferences(PATRON_PREFS,0).getBoolean("biometric_enabled",false))"DESACTIVAR ACCESO CON HUELLA" else "ACTIVAR ACCESO CON HUELLA","Seguridad de acceso al administrador",0xFF25845A.toInt()){togglePatronBiometric()})
  v.addView(adminTag("CAMBIAR PIN DE SOY PATRÓN","Actualiza tu clave privada"){changePatronPin()})
  v.addView(adminTag("HACER PRIMER INVENTARIO","Registrar el inventario inicial",0xFF25845A.toInt()){openFirstInventory()})
  v.addView(adminTag("AGREGAR O MODIFICAR PRODUCTO","Editar productos y existencias"){product()})
  v.addView(adminTag("CAMBIAR NÚMERO DE WHATSAPP","Número para recibir reportes"){val e=input("Número con código de país");e.setText(Store.phone(this));AlertDialog.Builder(this).setTitle("WhatsApp para reportes").setView(e).setPositiveButton("Guardar"){_,_->Store.setPhone(this,e.text.toString().filter{it.isDigit()});msg("Número guardado")}.setNegativeButton("Cancelar",null).show()})
  v.addView(adminTag("CAMBIAR CLAVE DE CIFRADO","Clave compartida con SENE ADMIN"){ReportKeySetup.change(this){msg("Clave de cifrado actualizada. Conserva también la clave anterior para los reportes históricos.");draw()}})
  v.addView(adminTag("CONSULTAR NÓMINA","Revisar nóminas de los trabajadores"){startActivity(Intent(this,PayrollAdminActivity::class.java))})
  v.addView(adminTag("VER INVENTARIO ACTUAL","Consultar productos, precios y existencias"){inventory()})
  v.addView(adminTag("GUARDAR TXT DEL STOCK","Crear respaldo del inventario",0xFF25845A.toInt()){export()})
  v.addView(adminTag("CARGAR INVENTARIO DESDE TXT","Restaurar un inventario guardado"){chooseInventoryFolder()})
  v.addView(adminTag("VOLVER A VENTAS","Regresar a la pantalla principal",0xFF78849D.toInt()){finish()})
  display(v)
  v.post { showFirstSetupGuideIfNeeded() }
 }
 private fun showFirstSetupGuideIfNeeded(){
  val prefs=getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE)
  if(prefs.getBoolean("first_setup_guide_shown",false))return
  // La guía depende de que exista inventario, no de que ya se hayan creado trabajadores.
  // Si se dio de alta a alguien antes de cargar productos, todavía debe mostrarse.
  val alreadyConfigured=prefs.getBoolean("first_inventory_created",false) ||
   Store.products(this).length()>0
  if(alreadyConfigured){prefs.edit().putBoolean("first_setup_guide_shown",true).apply();return}
  AlertDialog.Builder(this)
   .setTitle("¡COMENCEMOS A ORGANIZAR TU NEGOCIO!")
   .setMessage("Lo primero que debes hacer es crear todo tu inventario. Realiza estos pasos en orden:\n\n"+
    "1. CREA TU INVENTARIO\nEscanea cada producto que tengas y agrega su descripción: nombre, precio, cantidad disponible y ubicación en el pasillo y estante que corresponda.\n\n"+
    "2. DA DE ALTA A TUS TRABAJADORES\nCrea el perfil de cada trabajador con su nombre, número de celular, días que trabajará, horas por turno, sueldo y día de pago.\n\n"+
    "Así tendrás tu negocio organizado antes de comenzar a trabajar con SENE VENTAS.")
   .setCancelable(false)
   .setPositiveButton("ENTENDIDO"){_,_->prefs.edit().putBoolean("first_setup_guide_shown",true).apply()}
   .show()
 }
 private fun togglePatronBiometric(){
  val prefs=getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE)
  val enabled=prefs.getBoolean("biometric_enabled",false)
  if(enabled){prefs.edit().putBoolean("biometric_enabled",false).apply();msg("Acceso con huella desactivado");draw();return}
  val status=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
  if(status!=BiometricManager.BIOMETRIC_SUCCESS){msg("Este dispositivo no tiene una huella configurada o no está disponible. Configúrala en Ajustes de Android e inténtalo de nuevo.");return}
  prefs.edit().putBoolean("biometric_enabled",true).apply()
  msg("Acceso con huella activado. También podrás entrar con tu PIN.")
  draw()
 }
 private fun changePatronPin(){
  val next=input("Nuevo PIN (mínimo 4 dígitos)",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER}
  val confirm=input("Confirma el nuevo PIN",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER}
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8));addView(next);addView(confirm)}
  AlertDialog.Builder(this).setTitle("Cambiar PIN de Soy Patrón").setMessage("El PIN inicial es 2910. El nuevo PIN quedará guardado solo en este dispositivo.").setView(box)
   .setPositiveButton("GUARDAR",null).setNegativeButton("Cancelar",null).create().also{dialog->
    dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
     val a=next.text.toString();val b=confirm.text.toString()
     if(a.length<4||a.length>12||a.any{!it.isDigit()}){next.error="Usa de 4 a 12 dígitos";return@setOnClickListener}
     if(a!=b){confirm.error="Los PIN no coinciden";return@setOnClickListener}
     getSharedPreferences(PATRON_PREFS,Context.MODE_PRIVATE).edit().putString("pin",a).apply()
     dialog.dismiss();msg("PIN de Soy Patrón actualizado")
    }}
    dialog.show()
   }
 }
 private var pendingFirstInventory=false
 private var firstInventoryCode:EditText?=null
 private val firstInventoryItems=JSONArray()
 private fun openFirstInventory(){
  val saved=getSharedPreferences("sene_ventas_local",0).getString("report_tree_uri",null)
  if(saved.isNullOrBlank()){
   pendingFirstInventory=true
   AlertDialog.Builder(this).setTitle("Crear carpeta INVENTARIO SENE").setMessage("Selecciona directamente la carpeta INVENTARIO-SENE. Si todavía no existe, créala, ábrela y pulsa Usar esta carpeta. No crees otra carpeta INVENTARIO-SENE dentro de ella.")
    .setPositiveButton("CREAR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,INVENTORY_TREE_REQUEST)}.setNegativeButton("Cancelar",null).show()
  }else firstInventoryScreen()
 }
 private fun firstInventoryScreen(){
  while(firstInventoryItems.length()>0)firstInventoryItems.remove(firstInventoryItems.length()-1)
  val root=page("Primer inventario");root.addView(txt("Escanea cada producto y captura su descripción, cantidad inicial y precio. Pulsa AGREGAR PRODUCTO para incluirlo en la lista."))
  val code=input("Código de barras / código del producto");firstInventoryCode=code
  val name=input("Descripción del producto");val qty=input("Cantidad inicial",true);val price=input("Precio de venta $",true)
  val aisle=input("Pasillo (opcional)");val shelf=input("Número de estante (opcional)",true)
  root.addView(code);root.addView(button("ESCANEAR PRODUCTO"){openScanner()});root.addView(name);root.addView(qty);root.addView(price);root.addView(aisle);root.addView(shelf)
  val list=LinearLayout(this).apply{orientation=1};val count=txt("Productos agregados: 0",15f);root.addView(count);root.addView(list)
  root.addView(button("AGREGAR PRODUCTO"){
   val q=qty.text.toString().toIntOrNull();val p=parseAmount(price.text.toString())
   if(code.text.isBlank()||name.text.isBlank()||q==null||q<0||p==null||p<0){msg("Captura código, descripción, cantidad y precio válidos");return@button}
   for(i in 0 until firstInventoryItems.length())if(firstInventoryItems.optJSONObject(i)?.optString("code")==code.text.toString().trim()){msg("Ese código ya está en la lista");return@button}
   firstInventoryItems.put(JSONObject().put("code",code.text.toString().trim()).put("name",name.text.toString().trim()).put("stock",q).put("price",p).put("aisle",aisle.text.toString().trim()).put("shelf",shelf.text.toString().trim()))
   val location=listOf(aisle.text.toString().trim().takeIf{it.isNotBlank()}?.let{"Pasillo $it"},shelf.text.toString().trim().takeIf{it.isNotBlank()}?.let{"Estante $it"}).filterNotNull().joinToString(" · ").ifBlank{"Ubicación no indicada"}
   list.addView(txt(code.text.toString()+" | "+name.text.toString()+"\nCantidad: "+q+"   Precio: "+Store.money(p)+"\n"+location,14f));count.text="Productos agregados: "+firstInventoryItems.length()
   code.setText("");name.setText("");qty.setText("");price.setText("");aisle.setText("");shelf.setText("");code.requestFocus()
  })
  root.addView(button("GUARDAR INVENTARIO"){
   if(firstInventoryItems.length()==0){msg("Agrega por lo menos un producto antes de guardar");return@button}
   AlertDialog.Builder(this).setTitle("Guardar inventario").setMessage("Se guardarán "+firstInventoryItems.length()+" productos en INVENTARIO SENE con la fecha y hora actuales. ¿Continuar?")
    .setPositiveButton("GUARDAR"){_,_->try{if(InventoryArchive.save(this,firstInventoryItems)){Store.saveProducts(this,firstInventoryItems);getSharedPreferences("sene_ventas_local",0).edit().putBoolean("first_inventory_created",true).apply();msg("Inventario guardado correctamente en INVENTARIO SENE");draw()}else msg("No se pudo guardar. Verifica el acceso a INVENTARIO SENE.")}catch(e:Exception){msg("Error al guardar: "+(e.message?:"desconocido"))}}
    .setNegativeButton("Revisar",null).show()
  })
  root.addView(button("VOLVER",false){draw()});display(root)
 }
 private fun saveInventorySnapshot(){try{if(InventoryArchive.save(this,Store.products(this))){msg("Inventario guardado en INVENTARIO SENE");}else{pendingInventorySave=true;AlertDialog.Builder(this).setTitle("Crear carpeta INVENTARIO SENE").setMessage("Selecciona directamente la carpeta INVENTARIO-SENE. Si todavía no existe, créala, ábrela y pulsa Usar esta carpeta. No crees otra carpeta INVENTARIO-SENE dentro de ella.") .setPositiveButton("CREAR / SELECCIONAR CARPETA"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,INVENTORY_TREE_REQUEST)}.setNegativeButton("Después",null).show()}}catch(e:Exception){msg("No se pudo guardar el inventario en INVENTARIO SENE: "+(e.message?:"error"))}}
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
   val aisle=input("Pasillo (opcional)");val shelf=input("Número de estante (opcional)",true)
   val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8));addView(txt("Código escaneado: "+codeValue));addView(name);addView(price);addView(stock);addView(aisle);addView(shelf)}
   AlertDialog.Builder(this).setTitle("Producto nuevo").setView(box).setPositiveButton("GUARDAR"){_,_->
    val p=parseAmount(price.text.toString());val q=stock.text.toString().toIntOrNull()
    if(name.text.isBlank()||p==null||p<0||q==null||q<0){msg("Completa descripción, precio y existencia válidos");return@setPositiveButton}
    products.put(JSONObject().put("code",codeValue).put("name",name.text.toString().trim()).put("price",p).put("stock",q).put("aisle",aisle.text.toString().trim()).put("shelf",shelf.text.toString().trim()))
    Store.saveProducts(this,products);saveInventorySnapshot();msg("Producto nuevo agregado")
   }.setNegativeButton("Cancelar",null).show()
   return
  }
  val details=found!!
  AlertDialog.Builder(this).setTitle(details.optString("name"))
   .setMessage("Código: "+codeValue+"\nPrecio: "+Store.money(details.optDouble("price"))+"\nExistencia: "+details.optInt("stock"))
   .setItems(arrayOf("AGREGAR MÁS","QUITAR PRODUCTO","MODIFICAR PRECIO","MODIFICAR PASILLO / ESTANTE")){_,which->
    when(which){
     0->adjustStock(codeValue,details,true)
     1->adjustStock(codeValue,details,false)
     2->changeProductPrice(codeValue,details)
     3->changeProductLocation(codeValue,details)
    }
   }.setNegativeButton("Cancelar",null).show()
 }
 private fun changeProductLocation(codeValue:String,item:JSONObject){
  val aisle=input("Pasillo (opcional)").apply{setText(item.optString("aisle"))}
  val shelf=input("Número de estante (opcional)",true).apply{setText(item.optString("shelf"))}
  val box=LinearLayout(this).apply{orientation=1;setPadding(dp(18),dp(8),dp(18),dp(8));addView(aisle);addView(shelf)}
  AlertDialog.Builder(this).setTitle("Ubicación del producto").setView(box).setPositiveButton("GUARDAR"){_,_->
   val products=Store.products(this)
   for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;if(p.optString("code")==codeValue){p.put("aisle",aisle.text.toString().trim()).put("shelf",shelf.text.toString().trim());break}}
   Store.saveProducts(this,products);saveInventorySnapshot();msg("Ubicación actualizada")
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
  val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
  if(products.length()==0)list.addView(TextView(this).apply{text="Sin productos registrados";textSize=14f;gravity=Gravity.CENTER;setTextColor(0xFF77839B.toInt());setPadding(0,dp(24),0,dp(24))})
  for(i in 0 until products.length()){
   val item=products.optJSONObject(i)?:continue
   val card=LinearLayout(this).apply{
    orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12))
    background=GradientDrawable().apply{setColor(0xFFF8FAFE.toInt());cornerRadius=dp(14).toFloat();setStroke(dp(1),0xFFE0E7F2.toInt())}
   }
   card.addView(TextView(this).apply{text=item.optString("name","Producto");textSize=15f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF243557.toInt())})
   val code=TextView(this).apply{text="CÓDIGO  •  "+item.optString("code","—");textSize=11f;setTextColor(0xFF77839B.toInt());setPadding(0,dp(5),0,dp(8))}
   card.addView(code)
   val location=listOf(item.optString("aisle").takeIf{it.isNotBlank()}?.let{"PASILLO  •  $it"},item.optString("shelf").takeIf{it.isNotBlank()}?.let{"ESTANTE  •  $it"}).filterNotNull().joinToString("     ").ifBlank{"UBICACIÓN  •  No indicada"}
   card.addView(TextView(this).apply{text=location;textSize=11f;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(0xFF526B9C.toInt());setPadding(0,0,0,dp(8))})
   val details=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
   fun chip(label:String,value:String,weight:Float){
    val chip=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=GradientDrawable().apply{setColor(0xFFEAF0FB.toInt());cornerRadius=dp(10).toFloat()}}
    chip.addView(TextView(this).apply{text=label;textSize=9f;letterSpacing=.04f;setTextColor(0xFF77839B.toInt())})
    chip.addView(TextView(this).apply{text=value;textSize=13f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF294A85.toInt());setPadding(0,dp(3),0,0)})
    details.addView(chip,LinearLayout.LayoutParams(0,-2,weight).apply{marginEnd=dp(7)})
   }
   chip("PRECIO",Store.money(item.optDouble("price")),1f)
   chip("EXISTENCIA",item.optInt("stock").toString(),1f)
   card.addView(details)
   list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
  }
  val scroll=ScrollView(this).apply{addView(list);isFillViewport=true}
  AlertDialog.Builder(this).setTitle("INVENTARIO ACTUAL").setView(scroll).setPositiveButton("CERRAR",null).show()
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
   val snapshots=InventoryReader.listSnapshots(this,tree).sortedWith(compareByDescending<InventorySnapshot>{Regex("(?i)^inventario-(\\d+)\\.txt$").matchEntire(it.file)?.groupValues?.get(1)?.toLongOrNull()?:0L}.thenByDescending{snapshot->try{SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).parse(snapshot.updated)?.time?:0L}catch(_:Exception){0L}})
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
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==SCAN_REQUEST&&c==RESULT_OK){val codes=d?.getStringArrayListExtra(SCAN_CODES);if(!codes.isNullOrEmpty()){if(waitingProductScan){waitingProductScan=false;showProductActions(codes.last())}else firstInventoryCode?.setText(codes.last())};return};if(r==INVENTORY_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();WorkerProfiles.all(this).forEach{WorkerArchive.ensureProfileFolders(this,it.id,it.name)};if(pendingFirstInventory){pendingFirstInventory=false;firstInventoryScreen()}else if(pendingInventorySave){pendingInventorySave=false;if(InventoryArchive.save(this,Store.products(this)))msg("Primer inventario guardado en INVENTARIO SENE")}else msg("Carpeta INVENTARIO SENE guardada")}catch(e:Exception){msg("No se pudo guardar el acceso a INVENTARIO SENE: "+(e.message?:"error"))};return};if(r==IMPORT_TREE_REQUEST&&c==RESULT_OK){val tree=d?.data?:return;try{contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getSharedPreferences("sene_ventas_local",0).edit().putString("report_tree_uri",tree.toString()).apply();WorkerProfiles.all(this).forEach{WorkerArchive.ensureProfileFolders(this,it.id,it.name)};loadLatestInventory(tree)}catch(e:Exception){msg("No se pudo abrir la carpeta: "+(e.message?:"error"))};return};if(r==IMPORT_FILE_REQUEST&&c==RESULT_OK){val uri=d?.data?:return;try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){};confirmSelectedInventory(uri)}}
 private fun export(){try{if(InventoryArchive.save(this,Store.products(this)))msg("TXT de inventario guardado en INVENTARIO SENE")else{pendingInventorySave=true;AlertDialog.Builder(this).setTitle("Crear carpeta INVENTARIO SENE").setMessage("Selecciona directamente la carpeta INVENTARIO-SENE. Si todavía no existe, créala, ábrela y pulsa Usar esta carpeta. No crees otra carpeta INVENTARIO-SENE dentro de ella.") .setPositiveButton("CREAR / SELECCIONAR"){_,_->val pick=Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply{addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)};startActivityForResult(pick,INVENTORY_TREE_REQUEST)}.setNegativeButton("Cancelar",null).show()}}catch(e:Exception){msg("No se pudo guardar el TXT: "+(e.message?:"error"))}}
}

private const val SCAN_REQUEST=7412
private const val SAVE_REPORT_REQUEST=7413
private const val TREE_REPORT_REQUEST=7414
private const val IMPORT_TREE_REQUEST=7415
private const val IMPORT_FILE_REQUEST=7418
private const val START_INVENTORY_TREE_REQUEST=7416
private const val INVENTORY_TREE_REQUEST=7417
private const val SCAN_CODES="scan_codes"
private fun parseLicenseExpiry(raw:String):Long {
 val patterns=listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","yyyy-MM-dd'T'HH:mm:ss'Z'")
 for(pattern in patterns)try{
  val parser=SimpleDateFormat(pattern,Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
  val parsed=parser.parse(raw)
  if(parsed!=null)return parsed.time
 }catch(_:Exception){}
 return 0L
}
private fun enforceLicenseGate(activity:Activity,onAllowed:()->Unit){
 Thread{
  val status=try{ActivationApi.subscriptionStatus(activity)}catch(_:Exception){null}
  val serverActive=status?.optBoolean("active")==true
  val serverExpiry=status?.optString("expires_at").orEmpty()
  val serverExpiryMillis=parseLicenseExpiry(serverExpiry)
  val serverExpired=status?.optBoolean("expired")==true||(!serverActive&&serverExpiryMillis>0L&&serverExpiryMillis<=System.currentTimeMillis())
  val cachedExpiry=ActivationApi.savedLicenseExpiry(activity)
  val cachedExpiryMillis=parseLicenseExpiry(cachedExpiry)
  val cachedExpired=cachedExpiryMillis>0L&&cachedExpiryMillis<=System.currentTimeMillis()
  val expired=serverExpired||(!serverActive&&cachedExpired)
  val business=status?.optString("business_name").orEmpty().ifBlank{ActivationApi.savedLicenseBusiness(activity)}
  val expiry=serverExpiry.ifBlank{cachedExpiry}
  activity.runOnUiThread{
   if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
   if(expired){
    activity.startActivity(Intent(activity,LicenseExpiredActivity::class.java)
     .putExtra("business_name",business)
     .putExtra("expires_at",expiry))
    activity.finish()
   }else onAllowed()
  }
 }.start()
}
private fun hideLinkButtonWhenActive(activity:Activity,button:Button){
 Thread{
  val active=try{ActivationApi.subscriptionStatus(activity).optBoolean("active",false)}catch(_:Exception){false}
  activity.runOnUiThread{if(!activity.isFinishing&&!activity.isDestroyed&&active)button.visibility=View.GONE}
 }.start()
}
private fun Activity.openScanner(){
 val host=this
 Thread{
  val status=try{ActivationApi.subscriptionStatus(host)}catch(_:Exception){org.json.JSONObject().put("ok",false).put("message","No se pudo conectar con SERVER-SENE. Revisa tu conexión.")}
  host.runOnUiThread{
   if(host.isFinishing || host.isDestroyed)return@runOnUiThread
   if(status.optBoolean("active")){
    host.startActivityForResult(Intent(host,ScanActivity::class.java),SCAN_REQUEST)
   }else{
    val message=status.optString("message","No hay una suscripción vigente.")
    AlertDialog.Builder(host).setTitle("ACCEDE A SENE VENTAS")
     .setMessage(message+"\n\nPara utilizar el escáner necesitas una suscripción vigente y este dispositivo autorizado. Elige cómo deseas continuar:")
     .setPositiveButton("YA SOY CLIENTE SENE · INICIAR SESIÓN"){_,_->host.startActivity(Intent(host,AccountLoginActivity::class.java))}
     .setNeutralButton("VINCULAR SENE VENTAS"){_,_->host.startActivity(Intent(host,ActivationActivity::class.java))}
     .setNegativeButton("CANCELAR",null).show()
   }
  }
 }.start()
}
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
  resolver.openOutputStream(uri,"w")?.use{it.write(encryptedReportBackup(activity,file.readBytes()))}?:throw java.io.IOException("No se pudo escribir el reporte")
  val done=android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)}
  resolver.update(uri,done,null,null)
  return uri
 }catch(e:Exception){resolver.delete(uri,null,null);throw e}
}

private fun encryptedReportBackup(activity:Activity,body:ByteArray):ByteArray =
 SeneSharedFiles.encode("REPORTES",ReportCrypto.ownerKey(activity),body,stamp())
private const val REPORT_BACKUP_NOTE="\n\nGuarde este reporte en una carpeta segura. Será su respaldo de inventario hasta este momento."
private fun saveTextBackupLocal(activity:Activity,prefix:String,body:String,employeeId:String="",workerName:String=""):java.io.File {
 val folder=if(employeeId.isNotBlank())WorkerArchive.localReportsFolder(activity,employeeId,workerName) else java.io.File(activity.getExternalFilesDir(null)?:activity.filesDir,"SENE VENTAS/INVENTARIO SENE")
 if(!folder.exists()&&!folder.mkdirs())throw java.io.IOException("No se pudo crear la carpeta privada de respaldo SENE VENTAS")
 val date=SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS",Locale.US).format(Date())
 val target=java.io.File(folder,"$prefix-$date.txt")
 target.writeBytes(encryptedReportBackup(activity,body.toByteArray(Charsets.UTF_8)))
 if(!target.exists()||target.length()==0L)throw java.io.IOException("No se pudo verificar el TXT de respaldo")
 return target
}
private fun saveTextBackup(activity:Activity,tree:Uri,prefix:String,body:String,employeeId:String="",workerName:String=""):Uri {
 val resolver=activity.contentResolver
 val root=if(employeeId.isNotBlank())WorkerArchive.profileSection(activity,tree,employeeId,workerName,"REPORTES") else DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
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
 resolver.openOutputStream(target,"w")?.use{it.write(encryptedReportBackup(activity,body.toByteArray(Charsets.UTF_8)))}?:throw java.io.IOException("No se pudo guardar el TXT de respaldo")
 return target
}
private fun openWhatsAppText(activity:Activity,body:String):Boolean {
 var target=Store.phone(activity).filter{it.isDigit()}
 if(target.isBlank()){activity.msg("Configura primero el número de WhatsApp del patrón en Soy Patrón");return false}
 if(target.length==10)target="52"+target
 if(target.startsWith("521")&&target.length==13)target="52"+target.substring(3)
 if(target.length<12){activity.msg("El número de WhatsApp del patrón no parece válido. Incluye lada y código de país.");return false}
 if(body.startsWith("SENE-ADMIN-REPORT|1") && body.length>55000){activity.msg("El reporte cifrado supera el límite seguro para enviarlo como texto de WhatsApp. No se enviará incompleto.");return false}
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
  val out=StringBuilder("SENE VENTAS  •  REPORTE DE MOVIMIENTOS\n")
  out.append("━━━━━━━━━━━━━━━━━━━━━━━━\nETIQUETA: DATOS DEL TURNO\n")
   .append("ACTUALIZADO: ").append(stamp()).append("\nTRABAJADOR: ").append(shift.optString("worker","-"))
   .append("\nTELÉFONO: ").append(shift.optString("phone","-"))
   .append("\nHORAS DE JORNADA: ").append(shift.optInt("workHours",0)).append(" horas")
   .append("\nHORA PROGRAMADA DE SALIDA: ").append(shift.optString("expectedEnd","-"))
   .append("\nDÍAS LABORALES: ").append(shift.optJSONArray("workDaysOfWeek")?.let{a->val names=listOf("","Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado");(0 until a.length()).mapNotNull{names.getOrNull(a.optInt(it))}.joinToString(", ")}?.ifBlank{"Sin configurar"}?:"Pendiente de configurar")
   .append("\nSUELDO SEMANAL: ").append(if(shift.optLong("weeklySalaryCents",0L)>0)EmployeePayroll.money(shift.optLong("weeklySalaryCents")) else "Pendiente de configurar")
   .append("\nDÍA DE PAGO: ").append(listOf("","Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado").getOrNull(shift.optInt("payDay"))?:"Pendiente de configurar")
   .append("\nINICIO: ").append(shift.optString("started","-"))
   .append("\nCIERRE: ").append(shift.optString("closed","Turno en curso"))
   .append("\nCAJA INICIAL: ").append(Store.money(shift.optDouble("cash")))
   .append("\nÚLTIMA ACTUALIZACIÓN DE STOCK: ").append(Store.stockUpdatedAtText(c))
   .append("\n\nETIQUETA: VENTAS Y CORRECCIONES\n")
  val sales=data.optJSONArray("sales") ?: org.json.JSONArray();var total=0.0
  for(i in 0 until sales.length()){
   val x=sales.optJSONObject(i)?:continue
   if(x.optLong("ts")>=shift.optLong("startTs",0) && (x.optString("employeeId")==shift.optString("employeeId") || x.optString("employeeId").isBlank())){
    total+=x.optDouble("subtotal")
    val kind=when(x.optString("type","SALE")){"SALE_DELETE"->"DEVOLUCIÓN";"SALE_REPLACE_OLD"->"PRODUCTO DEVUELTO";"SALE_REPLACE_NEW"->"PRODUCTO ENTREGADO";else->"VENTA"}
    out.append("\n[ ").append(kind).append(" ] ").append(x.optString("time"))
     .append("\nPRODUCTO: ").append(x.optString("name"))
     .append(" | CANTIDAD: ").append(x.optInt("qty",1))
     .append("\nIMPORTE: ").append(Store.money(x.optDouble("subtotal")))
    if(x.optString("reason").isNotBlank())out.append("\nMOTIVO: ").append(x.optString("reason"))
   }
  }
  val moves=data.optJSONArray("moves") ?: org.json.JSONArray()
  var providerTotal=0.0;var loanTotal=0.0
  for(i in 0 until moves.length()){val x=moves.optJSONObject(i)?:continue;if(x.optLong("ts",0L)>=shift.optLong("startTs",0L) && (x.optString("employeeId")==shift.optString("employeeId") || (x.optString("employeeId").isBlank() && shift.optString("employeeId").isBlank()))){if(x.optString("type")=="PROVIDER_PAYMENT")providerTotal+=x.optDouble("amount",0.0);if(x.optString("type")=="LOAN_DISBURSEMENT")loanTotal+=x.optDouble("amount",0.0)}}
  out.append("\n\nETIQUETA: RESUMEN DE CAJA")
   .append("\nVENTAS NETAS (INCLUYE DEVOLUCIONES): ").append(Store.money(total))
   .append("\nPAGOS A PROVEEDORES: ").append(Store.money(providerTotal))
   .append("\nPRÉSTAMOS ENTREGADOS: ").append(Store.money(loanTotal))
   .append("\nEFECTIVO ESTIMADO: ").append(Store.money(shift.optDouble("cash")+total-providerTotal-loanTotal))
   .append("\n\nETIQUETA: GASTOS Y MOVIMIENTOS")
  for(i in 0 until moves.length()){val x=moves.optJSONObject(i)?:continue;if(x.optLong("ts",0L)>=shift.optLong("startTs",0L) && (x.optString("employeeId")==shift.optString("employeeId") || (x.optString("employeeId").isBlank() && shift.optString("employeeId").isBlank())))out.append("\n").append(x.optString("time")).append(" | ").append(x.optString("text"))}
  // Incluir solicitudes de préstamo y consumo pendientes en cada reporte para que SENE ADMIN las organice sin separarlas.
  val employeeId=shift.optString("employeeId")
  if(employeeId.isNotBlank()){
   val payrollEntries=EmployeePayroll.entriesForCurrentPeriod(c,employeeId)
   out.append("\n\nETIQUETA: SOLICITUDES DE NÓMINA PENDIENTES")
   if(payrollEntries.isEmpty())out.append("\nSin solicitudes pendientes en este período.")
   for(entry in payrollEntries){
    val kind=if(entry.type==EmployeePayroll.TYPE_LOAN)"PRÉSTAMO" else "CONSUMO"
    out.append("\n").append(kind).append(" | ").append(EmployeePayroll.money(entry.amountCents))
     .append("\nFECHA: ").append(SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("es","MX")).format(Date(entry.timestamp)))
     .append("\nMOTIVO: ").append(entry.reason.ifBlank{"No indicado"})
     .append("\nDESCRIPCIÓN: ").append(entry.description.ifBlank{"No indicada"})
     .append("\nESTADO: ").append(if(entry.settled)"LIQUIDADO" else "PENDIENTE")
   }
  }

  out.append("\n\nETIQUETA: EXISTENCIAS ACTUALES")
  val products=data.optJSONArray("products") ?: org.json.JSONArray()
  for(i in 0 until products.length()){val x=products.optJSONObject(i)?:continue;out.append("\nPRODUCTO: ").append(x.optString("name")).append("\nCÓDIGO: ").append(x.optString("code")).append(" | PRECIO: ").append(Store.money(x.optDouble("price"))).append(" | STOCK: ").append(x.optInt("stock")).append("\nPASILLO: ").append(x.optString("aisle").ifBlank{"No indicado"}).append(" | ESTANTE: ").append(x.optString("shelf").ifBlank{"No indicado"})}
  val workerId=shift.optString("employeeId")
  val dir=if(workerId.isNotBlank())WorkerArchive.localReportsFolder(c,workerId,shift.optString("worker")) else (c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: c.filesDir)
  if(!dir.exists()&&!dir.mkdirs())throw java.io.IOException("No se pudo crear la carpeta local del reporte")
  val file=java.io.File(dir,if(workerId.isBlank())"SENE-VENTAS-REPORTE.txt" else "SENE-VENTAS-REPORTE-$workerId.txt")
  file.writeText(out.toString(),Charsets.UTF_8)
  return file
 }
}

// Pantalla inicial de instalación: solo ofrece los dos recorridos de acceso.
class WelcomeActivity:Activity(){
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun action(label:String,primary:Boolean,click:()->Unit)=Button(this).apply{
  text=label;isAllCaps=false;textSize=16f;typeface=Typeface.create("sans-serif",Typeface.BOLD)
  minHeight=dp(62);setPadding(dp(18),dp(14),dp(18),dp(14))
  setTextColor(if(primary)-1 else 0xFF24447C.toInt())
  background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
   if(primary)intArrayOf(0xFF315BA5.toInt(),0xFF173C7B.toInt()) else intArrayOf(-1,-1)
  ).apply{cornerRadius=dp(15).toFloat();if(!primary)setStroke(dp(1),0xFFD5DEEF.toInt())}
  layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(9);bottomMargin=dp(9)}
  setOnClickListener{click()}
 }
 private var checking=false
 private var keySetupReady=false
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState);showWelcome()
  if(ReportCrypto.hasKey(this))keySetupReady=true
  else ReportKeySetup.ensureConfigured(this){keySetupReady=true;checkExistingSession()}
 }
 override fun onResume(){super.onResume();if(keySetupReady&&!isFinishing)checkExistingSession()}
 private fun checkExistingSession(){
  if(checking)return
  checking=true
  Thread{
   val active=try{ActivationApi.subscriptionStatus(this).optBoolean("active",false)}catch(_:Exception){false}
   runOnUiThread{
    checking=false
    if(isFinishing||isDestroyed)return@runOnUiThread
    if(active){
     AccountLoginActivity.ensureReportWhatsApp(this) {
      if (!isFinishing && !isDestroyed) {
       startActivity(Intent(this,InicioTurnoActivity::class.java))
       finish()
      }
     }
    }
   }
  }.start()
 }
 private fun showWelcome(){
  val root=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
   setPadding(dp(22),dp(28),dp(22),dp(30));setBackgroundColor(0xFFF3F6FC.toInt())
  }
  val card=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
   setPadding(dp(20),dp(26),dp(20),dp(26))
   background=GradientDrawable().apply{setColor(-1);cornerRadius=dp(22).toFloat();setStroke(dp(1),0xFFE0E6F1.toInt())}
   elevation=dp(3).toFloat()
  }
  val logo=ImageView(this).apply{setImageResource(R.drawable.logo_sene_pv);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER}
  card.addView(logo,LinearLayout.LayoutParams(dp(118),dp(118)).apply{bottomMargin=dp(14)})
  card.addView(TextView(this).apply{text="SENE / VENTAS";textSize=25f;gravity=Gravity.CENTER;typeface=Typeface.create("sans-serif-black",Typeface.BOLD);setTextColor(0xFF173C7B.toInt())})
  card.addView(TextView(this).apply{text="GESTIÓN INTELIGENTE";textSize=11f;letterSpacing=.16f;gravity=Gravity.CENTER;setTextColor(0xFF71809B.toInt());setPadding(0,dp(5),0,dp(24))})
  card.addView(TextView(this).apply{text="Te recomendamos guardar tus claves y datos de acceso en una USB.";textSize=13f;gravity=Gravity.CENTER;setTextColor(0xFF526579.toInt());setPadding(dp(8),0,dp(8),dp(12))})
  card.addView(action("YA SOY CLIENTE SENE · INICIAR SESIÓN",true){startActivity(Intent(this,AccountLoginActivity::class.java))})
  card.addView(action("VINCULAR SENE VENTAS",false){startActivity(Intent(this,ActivationActivity::class.java))})
  root.addView(card,LinearLayout.LayoutParams(-1,-2))
  setContentView(root)
 }
}

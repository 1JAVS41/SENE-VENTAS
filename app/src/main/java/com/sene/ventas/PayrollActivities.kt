package com.sene.ventas

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.*
import androidx.fragment.app.FragmentActivity
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun FragmentActivity.payText(value:String,size:Float=16f,bold:Boolean=false)=TextView(this).apply{
 text=value;textSize=size;setTextColor(if(bold)0xFF17213D.toInt() else 0xFF66708A.toInt())
 typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
 setPadding(18,15,18,15);setLineSpacing(2f,1f)
 background=android.graphics.drawable.GradientDrawable().apply{setColor(-1);cornerRadius=20f;setStroke(1,0xFFE2E8F3.toInt())}
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=5;p.bottomMargin=5;layoutParams=p
}
private fun FragmentActivity.payInput(hintText:String,number:Boolean=false)=EditText(this).apply{
 hint=hintText;textSize=16f;setSingleLine(true);setPadding(18,15,18,15)
 setTextColor(0xFF17213D.toInt());setHintTextColor(0xFF929BB0.toInt())
 background=android.graphics.drawable.GradientDrawable().apply{setColor(-1);cornerRadius=16f;setStroke(1,0xFFDCE2EF.toInt())}
 if(number)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=5;p.bottomMargin=7;layoutParams=p
}
private fun FragmentActivity.payButton(label:String,action:()->Unit)=LinearLayout(this).apply{
 orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(18,16,18,16)
 background=android.graphics.drawable.GradientDrawable().apply{setColor(0xFFFFFFFF.toInt());cornerRadius=20f;setStroke(1,0xFFE0E7F2.toInt())}
 elevation=3f;isClickable=true;isFocusable=true
 addView(TextView(this@payButton).apply{text=label;textSize=15f;typeface=Typeface.create("sans-serif",Typeface.BOLD);setTextColor(0xFF1C315B.toInt())},LinearLayout.LayoutParams(0,-2,1f))
 addView(TextView(this@payButton).apply{text="›";textSize=27f;setTextColor(0xFF365BA4.toInt())})
 val p=LinearLayout.LayoutParams(-1,-2);p.topMargin=6;p.bottomMargin=6;layoutParams=p
 setOnClickListener{action()}
}
private fun FragmentActivity.payPage(title:String)=LinearLayout(this).apply{
 orientation=1;setPadding(18,18,18,28)
 background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(0xFFF5F7FC.toInt(),0xFFEAF0FA.toInt()))
 val header=LinearLayout(this@payPage).apply{
  orientation=1;setPadding(20,20,20,20)
  background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF111A35.toInt(),0xFF2C477F.toInt())).apply{cornerRadius=24f}
  addView(TextView(this@payPage).apply{text="SENE / VENTAS";textSize=12f;letterSpacing=.12f;setTextColor(0xFFB9C9F8.toInt());typeface=Typeface.DEFAULT_BOLD})
  addView(TextView(this@payPage).apply{text=title;textSize=23f;setTextColor(-1);typeface=Typeface.create("sans-serif",Typeface.BOLD);setPadding(0,7,0,0)})
 }
 addView(header,LinearLayout.LayoutParams(-1,-2))
}
private fun FragmentActivity.payShow(root:LinearLayout){val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false;setPadding(0,0,0,24);addView(root)};setContentView(scroll)}
private fun moneyCents(value:Double)=kotlin.math.round(value*100.0).toLong()
private fun stampPayroll()=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date())

class PayrollRequestActivity:FragmentActivity(){
 private var scanCode="";private var codeField:EditText?=null
 private lateinit var workerId:String;private lateinit var workerName:String
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  val shift=Store.shift(this);workerId=shift?.optString("employeeId").orEmpty();workerName=shift?.optString("worker").orEmpty()
  if(workerId.isBlank()||workerName.isBlank()){Toast.makeText(this,"Inicia turno con tu perfil para solicitar consumos o préstamos.",Toast.LENGTH_LONG).show();finish();return}
  draw()
 }
 private fun draw(){
  val root=payPage("SOLICITA");val cap=EmployeePayroll.capacity(this,workerId)
  root.addView(payText("TRABAJADOR: $workerName",16f,true))
  root.addView(payText("LÍMITE CONJUNTO (50%)\nSueldo ajustado por faltas: "+EmployeePayroll.money(cap.adjustedSalaryCents)+"\nDisponible: "+EmployeePayroll.money(cap.availableCents),18f,true))
  root.addView(payButton("CONSUMO"){consumptionForm()});root.addView(payButton("PRÉSTAMO"){loanForm()})
  root.addView(payButton("MI NÓMINA"){startActivity(Intent(this,PayrollEmployeeActivity::class.java).putExtra("employee_id",workerId))})
  root.addView(payText("Los consumos y préstamos se descuentan de la nómina. Los préstamos también reducen la caja al entregarse.",13f));payShow(root)
 }
 private fun consumptionForm(){
  val box=LinearLayout(this).apply{orientation=1;setPadding(20,8,20,8)}
  val code=payInput("Código del producto");codeField=code;val qty=payInput("Cantidad",true)
  box.addView(code);box.addView(payButton("ESCANEAR CÓDIGO"){IntentIntegrator(this).setPrompt("Escanea el producto").setBeepEnabled(true).initiateScan()});box.addView(qty)
  val d=AlertDialog.Builder(this).setTitle("SOLICITAR CONSUMO").setMessage("Se descontará de tu nómina en el próximo pago.").setView(box).setPositiveButton("REVISAR",null).setNegativeButton("CANCELAR",null).create()
  d.setOnShowListener{d.getButton(-1).setOnClickListener{
   val q=qty.text.toString().toIntOrNull();val products=Store.products(this);var found:JSONObject?=null
   for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;if(p.optString("code")==code.text.toString().trim()){found=p;break}}
   if(found==null){code.error="Producto no encontrado";return@setOnClickListener}
   if(q==null||q<=0||q>found.optInt("stock")){qty.error="Cantidad no disponible";return@setOnClickListener}
   val cents=moneyCents(found.optDouble("price")*q);val cap=EmployeePayroll.capacity(this,workerId)
   if(cents>cap.availableCents){Toast.makeText(this,"Disponible: "+EmployeePayroll.money(cap.availableCents),Toast.LENGTH_LONG).show();return@setOnClickListener}
   AlertDialog.Builder(this).setTitle("CONFIRMAR CONSUMO")
    .setMessage(found.optString("name")+"\nCantidad: $q\nImporte: "+EmployeePayroll.money(cents)+"\n\nSe descontará de tu nómina en el próximo pago.")
    .setPositiveButton("CONFIRMAR"){_,_->
     val entry=EmployeePayroll.addRequest(this,EmployeePayroll.TYPE_CONSUMPTION,cents,description=found.optString("name")+" x$q",employeeId=workerId)
     if(entry==null){Toast.makeText(this,"No se pudo registrar. Revisa el límite.",Toast.LENGTH_LONG).show();return@setPositiveButton}
     val amount=found.optDouble("price")*q;found.put("stock",found.optInt("stock")-q);Store.saveProducts(this,products)
     Store.addMove(this,JSONObject().put("type","EMPLOYEE_CONSUMPTION").put("ts",System.currentTimeMillis()).put("time",stampPayroll()).put("amount",amount).put("worker",workerName).put("employeeId",workerId).put("text","CONSUMO DE EMPLEADO: $workerName | "+found.optString("name")+" x$q | "+Store.money(amount)+" | Descuento de nómina"))
     val sent=SeneOrderSender.send(this,workerId,workerName,"CONSUMO",found.optString("name")+" x$q",cents,entry.id)
     Toast.makeText(this,if(sent)"Consumo registrado. WhatsApp se abrió con la orden preparada; pulsa ENVIAR para entregarla al patrón." else "Consumo guardado. No se pudo abrir WhatsApp para avisar al patrón.",Toast.LENGTH_LONG).show();draw()
    }.setNegativeButton("VOLVER",null).show()
   d.dismiss()
  }};d.show()
 }
 private fun loanForm(){
  val box=LinearLayout(this).apply{orientation=1;setPadding(20,8,20,8)}
  val reason=payInput("Motivo del préstamo");val amount=payInput("Cantidad $",true);box.addView(reason);box.addView(amount)
  val d=AlertDialog.Builder(this).setTitle("SOLICITAR PRÉSTAMO").setMessage("El préstamo se descuenta de tu nómina y el efectivo sale de caja al entregarse.").setView(box).setPositiveButton("REVISAR",null).setNegativeButton("CANCELAR",null).create()
  d.setOnShowListener{d.getButton(-1).setOnClickListener{
   val value=amount.text.toString().replace(",",".").toDoubleOrNull()
   if(reason.text.isBlank()){reason.error="Indica el motivo";return@setOnClickListener}
   if(value==null||value<=0){amount.error="Cantidad inválida";return@setOnClickListener}
   val cents=moneyCents(value);val cap=EmployeePayroll.capacity(this,workerId)
   if(cents>cap.availableCents){Toast.makeText(this,"Solo tienes "+EmployeePayroll.money(cap.availableCents)+" disponibles",Toast.LENGTH_LONG).show();return@setOnClickListener}
   AlertDialog.Builder(this).setTitle("CONFIRMAR PRÉSTAMO").setMessage("Motivo: "+reason.text+"\nImporte: "+Store.money(value)+"\nDisponible después: "+EmployeePayroll.money(cap.availableCents-cents))
    .setPositiveButton("CONFIRMAR Y REGISTRAR"){_,_->
     val entry=EmployeePayroll.addRequest(this,EmployeePayroll.TYPE_LOAN,cents,reason.text.toString(),employeeId=workerId)
     if(entry==null){Toast.makeText(this,"No se pudo registrar el préstamo",Toast.LENGTH_LONG).show();return@setPositiveButton}
     Store.addMove(this,JSONObject().put("type","LOAN_DISBURSEMENT").put("ts",System.currentTimeMillis()).put("time",stampPayroll()).put("amount",value).put("worker",workerName).put("employeeId",workerId).put("text","PRÉSTAMO ENTREGADO A $workerName | "+reason.text+" | "+Store.money(value)+" | Descuento de nómina"))
     val sent=SeneOrderSender.send(this,workerId,workerName,"PRESTAMO",reason.text.toString(),cents,entry.id)
     Toast.makeText(this,if(sent)"Préstamo registrado. WhatsApp se abrió con la orden preparada; pulsa ENVIAR para entregarla al patrón." else "Préstamo guardado. No se pudo abrir WhatsApp para avisar al patrón.",Toast.LENGTH_LONG).show();draw()
    }.setNegativeButton("CANCELAR",null).show()
   d.dismiss()
  }};d.show()
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);val r=IntentIntegrator.parseActivityResult(requestCode,resultCode,data);if(r?.contents!=null){scanCode=r.contents;codeField?.setText(scanCode)}}
}

class PayrollEmployeeActivity:FragmentActivity(){
 private var employeeId=""
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);val active=Store.shift(this)?.optString("employeeId").orEmpty();employeeId=intent.getStringExtra("employee_id")?.takeIf{it.isNotBlank()}?:active
  if(employeeId.isBlank()){chooseEmployee();return}
  val profile=WorkerProfiles.all(this).firstOrNull{it.id==employeeId};if(profile==null){finish();return}
  authenticate(profile)
 }
 private fun chooseEmployee(){
  val profiles=WorkerProfiles.all(this);if(profiles.isEmpty()){Toast.makeText(this,"No hay trabajadores registrados.",Toast.LENGTH_LONG).show();finish();return}
  AlertDialog.Builder(this).setTitle("SELECCIONA TU PERFIL").setItems(profiles.map{it.name}.toTypedArray()){_,i->employeeId=profiles[i].id;authenticate(profiles[i])}.setOnCancelListener{finish()}.show()
 }
 private fun authenticate(p:WorkerProfiles.Profile){
  val pin=payInput("PIN personal de 4 dígitos",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
  val d=AlertDialog.Builder(this).setTitle("MI NÓMINA · "+p.name).setMessage("Introduce tu PIN personal para consultar únicamente tus datos.").setView(pin).setPositiveButton("ENTRAR",null).setNegativeButton("CANCELAR"){_,_->finish()}.create()
  d.setOnShowListener{d.getButton(-1).setOnClickListener{if(!WorkerProfiles.verify(p,pin.text.toString())){pin.error="PIN incorrecto";pin.text?.clear()}else{d.dismiss();draw()}}};d.show()
 }
 private fun draw(){
  val cfg=EmployeePayroll.config(this,employeeId);val root=payPage("MI NÓMINA");val name=WorkerProfiles.all(this).firstOrNull{it.id==employeeId}?.name.orEmpty()
  root.addView(payText("TRABAJADOR: $name",16f,true))
  if(cfg==null){root.addView(payText("El patrón aún no configuró tu sueldo y días laborables."));payShow(root);return}
  val entries=EmployeePayroll.entriesForCurrentPeriod(this,employeeId)
  val calculation=EmployeePayroll.calculateCurrentPeriod(this,employeeId)
  val abs=calculation.absences
  val absenceDeduction=calculation.absenceDeductionCents
  val earlyMinutes=calculation.earlyExitMinutes
  val earlyDeduction=calculation.earlyExitDeductionCents
  val consumption=calculation.consumptionCents
  val loans=calculation.loanCents
  val net=calculation.netCents
  val work=cfg.workDaysOfWeek.sortedBy{if(it==Calendar.SUNDAY)0 else it}.joinToString(", "){dayName(it)}
  val rest=(Calendar.SUNDAY..Calendar.SATURDAY).filter{it !in cfg.workDaysOfWeek}.joinToString(", "){dayName(it)}
  root.addView(payText("SUELDO SEMANAL\n"+EmployeePayroll.money(cfg.weeklySalaryCents),22f,true))
  root.addView(payText("DÍAS LABORABLES: $work\nDESCANSOS: "+rest.ifBlank{"Ninguno"}+"\nDía de pago: "+dayName(cfg.payDay)))
  root.addView(payText("Sueldo base del período: "+EmployeePayroll.money(calculation.grossCents)+"\nFaltas: $abs\nDescuento por faltas: "+EmployeePayroll.money(absenceDeduction)+"\nSalidas anticipadas: $earlyMinutes minutos\nDescuento por salidas anticipadas: "+EmployeePayroll.money(earlyDeduction)+"\nConsumos pendientes: "+EmployeePayroll.money(consumption)+"\nPréstamos pendientes: "+EmployeePayroll.money(loans)))
  root.addView(payText("TOTAL A RECIBIR\n"+EmployeePayroll.money(net),24f,true))
  root.addView(payText("HISTORIAL DEL PERIODO",17f,true))
  root.addView(payText("HISTORIAL COMPLETO",17f,true))
  EmployeePayroll.entries(this,employeeId).sortedByDescending{it.timestamp}.forEach{e->root.addView(payText(SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("es","MX")).format(Date(e.timestamp))+" · "+e.periodKey+"\n"+(if(e.type==EmployeePayroll.TYPE_LOAN)"PRÉSTAMO" else "CONSUMO")+" · "+e.description+" · "+EmployeePayroll.money(e.amountCents)+"\n"+if(e.settled)"LIQUIDADO" else "PENDIENTE"))}
  root.addView(payText("ASISTENCIA DE LA SEMANA",17f,true))
  EmployeePayroll.weekAttendance(this,employeeId).forEach{day->
   val symbol=when(day.status){"WORKED","EARLY_EXIT"->"🟢";"NOT_WORKED"->"❌";"WORKING"->"🔁";else->"—"}
   val status=when(day.status){"NO_RECORD"->"Aún no tenía registro";"EARLY_EXIT"->"Cierre anticipado";"WORKING"->"En curso";"WORKED"->"Jornada completada";"NOT_WORKED"->"Sin asistencia";else->""}
   val times=listOf(day.started.takeIf{it.isNotBlank()}?.let{"Entrada: $it"},day.closed.takeIf{it.isNotBlank()}?.let{"Salida: $it"}).filterNotNull().joinToString(" · ")
   val detail=listOf(status,times).filter{it.isNotBlank()}.joinToString(" · ")
   root.addView(payText(day.day+" - ["+symbol+"]"+if(detail.isBlank()) "" else " · "+detail,13f,true))
  }
  payShow(root)
 }
 private fun dayName(d:Int)=listOf("","Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado").getOrElse(d){"-"}
}

class PayrollAdminActivity:FragmentActivity(){
 private fun showWorkersScreen(root:LinearLayout){
  val scroll=ScrollView(this).apply{
   isFillViewport=true
   clipToPadding=false
   setPadding(0,0,0,(resources.displayMetrics.density*40).toInt())
   addView(root)
  }
  window.decorView.setPadding(0,0,0,0)
  setContentView(scroll)
 }
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);draw()}
 private fun draw(){
  val manage=intent.getBooleanExtra("manage_workers",false)
  val profiles=WorkerProfiles.all(this)
  val root=payPage(if(manage)"MIS TRABAJADORES" else "CONSULTAR NÓMINA")
  if(profiles.isEmpty()&&!manage){
   root.addView(payText("Todavía no hay trabajadores registrados."))
   payShow(root)
   return
  }
  if(manage){
   root.addView(payText("Selecciona un empleado para configurar sus horas, días laborales, sueldo semanal y día de pago.",14f))
   root.addView(payButton("＋  AGREGAR TRABAJADOR"){addWorkerProfile()})
   if(profiles.isEmpty()){
    root.addView(payText("Aún no hay perfiles. Registra aquí al primer trabajador y entrégale su nombre y PIN de acceso."))
    root.addView(payButton("VOLVER A SOY PATRÓN"){finish()})
    showWorkersScreen(root)
    root.post { addWorkerProfile() }
    return
   }
   root.addView(payText("EMPLEADOS REGISTRADOS",17f,true))
   profiles.forEach{p->
    root.addView(payButton("👤  "+p.name){
     intent.putExtra("employee_id",p.id)
     drawEmployee(p.id)
    })
   }
   root.addView(payButton("VOLVER A SOY PATRÓN"){finish()})
   showWorkersScreen(root)
   return
  }
  val selectedId=intent.getStringExtra("employee_id")?.takeIf{id->profiles.any{it.id==id}}?:profiles.first().id
  val profile=profiles.first{it.id==selectedId};val old=EmployeePayroll.config(this,selectedId)
  val spinner=Spinner(this).apply{adapter=ArrayAdapter(this@PayrollAdminActivity,android.R.layout.simple_spinner_dropdown_item,profiles.map{it.name});setSelection(profiles.indexOf(profile))}
  root.addView(payText("SELECCIONA TRABAJADOR",16f,true));root.addView(spinner)
  root.addView(payButton("CARGAR PERFIL SELECCIONADO"){startActivity(Intent(this,PayrollAdminActivity::class.java).putExtra("employee_id",profiles[spinner.selectedItemPosition].id));finish()})
  addEmployeeConfiguration(root,profile,old,selectedId,false)
  payShow(root)
 }
 private fun drawEmployee(employeeId:String){
  val profile=WorkerProfiles.all(this).firstOrNull{it.id==employeeId}?:run{draw();return}
  val old=EmployeePayroll.config(this,employeeId)
  val root=payPage("MIS TRABAJADORES")
  root.addView(payButton("← LISTA DE EMPLEADOS"){draw()})
  root.addView(payText("EMPLEADO SELECCIONADO",15f,true))
  root.addView(payText("👤  "+profile.name,20f,true))
  addEmployeeConfiguration(root,profile,old,employeeId,true)
  showWorkersScreen(root)
 }
 private fun addWorkerProfile(){
  val name=payInput("Nombre completo del trabajador")
  val phone=payInput("Número de celular (obligatorio)",true).apply{inputType=android.text.InputType.TYPE_CLASS_PHONE}
  val pin=payInput("PIN de acceso · 4 dígitos",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
  val confirm=payInput("Confirma el PIN",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD}
  val hours=payInput("Horas por jornada (1 a 24)",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER}
  val salary=payInput("Sueldo semanal $",true)
  val days=listOf(Calendar.SUNDAY,Calendar.MONDAY,Calendar.TUESDAY,Calendar.WEDNESDAY,Calendar.THURSDAY,Calendar.FRIDAY,Calendar.SATURDAY)
  val labels=listOf("Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado")
  val checks=days.mapIndexed{i,d->d to CheckBox(this).apply{text=labels[i];textSize=15f;setTextColor(0xFF172B50.toInt());isChecked=false;setPadding(8,8,8,8)}}
  val pay=Spinner(this).apply{adapter=ArrayAdapter(this@PayrollAdminActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Selecciona día de pago")+labels);setSelection(0)}
  val fields=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;setPadding(18,10,18,18)
   addView(payText("DATOS DEL TRABAJADOR",15f,true));addView(name);addView(phone);addView(pin);addView(confirm)
   addView(payText("CONDICIONES DE TRABAJO",15f,true));addView(hours);addView(salary)
   addView(payText("DÍAS QUE DEBE TRABAJAR",14f,true));checks.forEach{addView(it.second)}
   addView(payText("DÍA DE PAGO",14f,true));addView(pay)
  }
  val scroll=ScrollView(this).apply{
   isFillViewport=true;isVerticalScrollBarEnabled=true;overScrollMode=android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
   addView(fields,android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
  }
  lateinit var dialog:android.app.Dialog
  val shell=LinearLayout(this).apply{
   orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(12));setBackgroundColor(0xFFEEF3FA.toInt())
   addView(payText("Completa todos los datos obligatorios. Desliza esta sección para ver el formulario completo. El PIN es privado y nunca se incluye en los reportes.",13f))
   addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
   val actions=LinearLayout(this@PayrollAdminActivity).apply{
    orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
    setPadding(0,dp(12),0,dp(4))
   }
   val cancel=Button(this@PayrollAdminActivity).apply{
    text="CANCELAR";isAllCaps=false;textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(0xFF244B84.toInt())
    background=android.graphics.drawable.GradientDrawable().apply{setColor(-1);cornerRadius=16f;setStroke(1,0xFFD5DEED.toInt())}
   }
   val save=Button(this@PayrollAdminActivity).apply{
    text="GUARDAR PERFIL";isAllCaps=false;textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(-1)
    background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xFF4569B5.toInt(),0xFF263F7D.toInt())).apply{cornerRadius=16f}
   }
   actions.addView(cancel,LinearLayout.LayoutParams(0,dp(54),1f).apply{rightMargin=dp(6)})
   actions.addView(save,LinearLayout.LayoutParams(0,dp(54),1.35f).apply{leftMargin=dp(6)})
   addView(actions,LinearLayout.LayoutParams(-1,-2))
   cancel.setOnClickListener{dialog.dismiss()}
   save.setOnClickListener{
    val cleanName=name.text.toString().trim()
    val cleanPhone=phone.text.toString().filter{it.isDigit()}
    val code=pin.text.toString()
    val h=hours.text.toString().toIntOrNull()
    val s=salary.text.toString().trim().replace(",",".").toDoubleOrNull()
    val chosen=checks.filter{it.second.isChecked}.map{it.first}.toSet()
    if(cleanName.isBlank()){name.error="Escribe el nombre completo";scroll.smoothScrollTo(0,name.top);return@setOnClickListener}
    if(cleanPhone.length !in 10..15){phone.error="Escribe un celular válido de 10 a 15 dígitos";scroll.smoothScrollTo(0,phone.top);return@setOnClickListener}
    if(code.length!=4||code.any{!it.isDigit()}){pin.error="El PIN debe tener exactamente 4 dígitos";scroll.smoothScrollTo(0,pin.top);return@setOnClickListener}
    if(code!=confirm.text.toString()){confirm.error="Los PIN no coinciden";scroll.smoothScrollTo(0,confirm.top);return@setOnClickListener}
    if(h==null||h !in 1..24){hours.error="Elige las horas que debe trabajar";scroll.smoothScrollTo(0,hours.top);return@setOnClickListener}
    if(s==null||s<=0){salary.error="Captura el sueldo semanal";scroll.smoothScrollTo(0,salary.top);return@setOnClickListener}
    if(chosen.isEmpty()){Toast.makeText(this@PayrollAdminActivity,"Selecciona al menos un día de trabajo.",Toast.LENGTH_LONG).show();return@setOnClickListener}
    if(pay.selectedItemPosition<=0){Toast.makeText(this@PayrollAdminActivity,"Selecciona el día de pago.",Toast.LENGTH_LONG).show();scroll.smoothScrollTo(0,pay.top);return@setOnClickListener}
    val profile=WorkerProfiles.create(this@PayrollAdminActivity,cleanName,cleanPhone,code)
    if(profile==null){name.error="Ese nombre ya existe o no se pudo guardar";scroll.smoothScrollTo(0,name.top);return@setOnClickListener}
    val payDay=Calendar.SUNDAY+pay.selectedItemPosition-1
    val cfg=EmployeePayroll.Config(profile.name,h,moneyCents(s),chosen.size,(Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull{it !in chosen}?:Calendar.SUNDAY,payDay,chosen,profile.id,System.currentTimeMillis())
    if(!EmployeePayroll.saveConfig(this@PayrollAdminActivity,cfg)){
     WorkerProfiles.delete(this@PayrollAdminActivity,profile.id)
     Toast.makeText(this@PayrollAdminActivity,"No se pudo guardar la configuración completa. El perfil no quedó registrado; inténtalo de nuevo.",Toast.LENGTH_LONG).show()
     return@setOnClickListener
    }
    dialog.dismiss()
    AlertDialog.Builder(this@PayrollAdminActivity).setTitle("PERFIL COMPLETO")
     .setMessage("TRABAJADOR: "+profile.name+"\nCELULAR: "+profile.phone+"\nDÍAS: "+chosen.sortedBy{if(it==Calendar.SUNDAY)0 else it}.joinToString(", "){labels[days.indexOf(it)]}+"\nHORAS POR JORNADA: "+h+"\nSUELDO SEMANAL: "+EmployeePayroll.money(moneyCents(s))+"\nDÍA DE PAGO: "+labels[payDay-Calendar.SUNDAY]+"\nPIN DE ACCESO: Configurado (no se muestra por seguridad)"+"\n\nEntrega el PIN únicamente al trabajador. No se enviará en el reporte.")
     .setPositiveButton("ENTENDIDO"){_,_->draw()}.setCancelable(false).show()
   }
  }
  // Dialog personalizado con altura acotada: el ScrollView ocupa el espacio flexible
  // y la barra de acciones queda fuera del scroll, siempre visible.
  dialog=android.app.Dialog(this)
  dialog.setContentView(shell,android.view.ViewGroup.LayoutParams(-1,-1))
  dialog.setCancelable(true)
  dialog.setCanceledOnTouchOutside(true)
  dialog.show()
  dialog.window?.apply{
   setBackgroundDrawableResource(android.R.color.transparent)
   setLayout((resources.displayMetrics.widthPixels*0.96f).toInt(),(resources.displayMetrics.heightPixels*0.84f).toInt())
   setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
  }
 }
 private fun addEmployeeConfiguration(root:LinearLayout,profile:WorkerProfiles.Profile,old:EmployeePayroll.Config?,selectedId:String,manage:Boolean){
  val name=payInput("Nombre del empleado").apply{setText(profile.name);isEnabled=false}
  val phone=payInput("Número de celular (obligatorio)",true).apply{inputType=android.text.InputType.TYPE_CLASS_PHONE;setText(profile.phone)}
  val hours=payInput("Horas diarias (1 a 24)",true).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;old?.let{setText(it.dailyHours.toString())}}
  val salary=payInput("Sueldo semanal $",true).apply{old?.let{setText(String.format(Locale.US,"%.2f",it.weeklySalaryCents/100.0))}}
  val days=listOf(Calendar.SUNDAY,Calendar.MONDAY,Calendar.TUESDAY,Calendar.WEDNESDAY,Calendar.THURSDAY,Calendar.FRIDAY,Calendar.SATURDAY)
  val labels=listOf("Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado")
  val selected=old?.workDaysOfWeek?:emptySet()
  val checks=days.mapIndexed{i,d->d to CheckBox(this).apply{text=labels[i];textSize=15f;setTextColor(0xFF172B50.toInt());isChecked=d in selected}}
  val pay=Spinner(this).apply{
   adapter=ArrayAdapter(this@PayrollAdminActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Selecciona día de pago")+labels)
   setSelection(old?.let{it.payDay-Calendar.SUNDAY+1}?:0)
  }
  root.addView(payText("DATOS DEL TRABAJADOR",17f,true));root.addView(name);root.addView(phone)
  root.addView(payText("CONDICIONES DE TRABAJO",17f,true));root.addView(hours);root.addView(salary)
  root.addView(payText("SELECCIONA LOS DÍAS QUE TRABAJA",16f,true));checks.forEach{root.addView(it.second)}
  root.addView(payText("Los días no seleccionados se consideran descanso."));root.addView(payText("DÍA DE PAGO"));root.addView(pay)
  root.addView(payButton("GUARDAR TODOS LOS DATOS"){
   val cleanPhone=phone.text.toString().filter{it.isDigit()}
   val h=hours.text.toString().toIntOrNull()
   val s=salary.text.toString().replace(",",".").toDoubleOrNull()
   val chosen=checks.filter{it.second.isChecked}.map{it.first}.toSet()
   if(cleanPhone.length !in 10..15){phone.error="Escribe un celular válido de 10 a 15 dígitos";return@payButton}
   if(h==null||h !in 1..24){hours.error="Elige las horas de jornada";return@payButton}
   if(s==null||s<=0){salary.error="Captura el sueldo semanal";return@payButton}
   if(chosen.isEmpty()){Toast.makeText(this,"Selecciona al menos un día de trabajo.",Toast.LENGTH_LONG).show();return@payButton}
   if(pay.selectedItemPosition<=0){Toast.makeText(this,"Selecciona el día de pago.",Toast.LENGTH_LONG).show();return@payButton}
   val cfg=EmployeePayroll.Config(profile.name,h,moneyCents(s),chosen.size,(Calendar.SUNDAY..Calendar.SATURDAY).firstOrNull{it !in chosen}?:Calendar.SUNDAY,Calendar.SUNDAY+pay.selectedItemPosition-1,chosen,profile.id,old?.createdAt?:System.currentTimeMillis())
   if(!WorkerProfiles.updatePhone(this,selectedId,cleanPhone)){Toast.makeText(this,"No se pudo guardar el celular.",Toast.LENGTH_LONG).show();return@payButton}
   if(EmployeePayroll.saveConfig(this,cfg)){Toast.makeText(this,"Todos los datos de "+profile.name+" quedaron guardados.",Toast.LENGTH_LONG).show();if(manage)drawEmployee(selectedId)else draw()}else Toast.makeText(this,"No se pudo guardar la configuración.",Toast.LENGTH_LONG).show()
  })
  val cfg=EmployeePayroll.config(this,selectedId)
  if(cfg!=null){
   val calculation=EmployeePayroll.calculateCurrentPeriod(this,selectedId)
   val abs=calculation.absences;val absenceDeduction=calculation.absenceDeductionCents
   val earlyMinutes=calculation.earlyExitMinutes;val earlyDeduction=calculation.earlyExitDeductionCents
   val cons=calculation.consumptionCents;val loans=calculation.loanCents
   val cap=EmployeePayroll.capacity(this,selectedId);val net=calculation.netCents
   root.addView(payText("RESUMEN DEL PERIODO · "+profile.name,17f,true))
   root.addView(payText("Sueldo base del período: "+EmployeePayroll.money(calculation.grossCents)+"\nFaltas: $abs · Descuento: "+EmployeePayroll.money(absenceDeduction)+"\nSalidas anticipadas: $earlyMinutes minutos · Descuento: "+EmployeePayroll.money(earlyDeduction)+"\nConsumos: "+EmployeePayroll.money(cons)+"\nPréstamos: "+EmployeePayroll.money(loans)+"\nTotal descuentos: "+EmployeePayroll.money(absenceDeduction+earlyDeduction+cons+loans)+"\nNeto estimado: "+EmployeePayroll.money(net),17f,true))
   root.addView(payText("LÍMITE DEL 50%\nComprometido: "+EmployeePayroll.money(cap.committedCents)+"\nDisponible: "+EmployeePayroll.money(cap.availableCents)))
   root.addView(payText("HISTORIAL COMPLETO",17f,true))
   EmployeePayroll.entries(this,selectedId).sortedByDescending{it.timestamp}.forEach{e->root.addView(payText(SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("es","MX")).format(Date(e.timestamp))+" · "+e.periodKey+"\n"+(if(e.type==EmployeePayroll.TYPE_LOAN)"PRÉSTAMO" else "CONSUMO")+" · "+e.description+" · "+EmployeePayroll.money(e.amountCents)+"\n"+if(e.settled)"LIQUIDADO" else "PENDIENTE"))}
   root.addView(payButton("CONFIRMAR PAGO Y CERRAR PERIODO"){AlertDialog.Builder(this).setTitle("CONFIRMAR PAGO").setMessage("¿Ya se pagó a "+profile.name+"? Se marcarán como liquidados los consumos y préstamos de este periodo.").setPositiveButton("SÍ, YA SE PAGÓ"){_,_->val period=EmployeePayroll.periodKey(System.currentTimeMillis(),cfg.payDay);if(EmployeePayroll.markSettled(this,period,selectedId))Toast.makeText(this,"Periodo liquidado.",Toast.LENGTH_LONG).show()}.setNegativeButton("CANCELAR",null).show()})
  }
 }
}

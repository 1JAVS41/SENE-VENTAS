package com.sene.ventas

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.fragment.app.FragmentActivity
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

private fun FragmentActivity.payText(value:String,size:Float=16f,bold:Boolean=false):TextView =
    TextView(this).apply {
        text=value;textSize=size;setTextColor(0xFF172B50.toInt())
        typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        setPadding(16,13,16,13)
        background=android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt());cornerRadius=18f;setStroke(1,0xFFDCE5F2.toInt())
        }
    }
private fun FragmentActivity.payInput(hintText:String,number:Boolean=false):EditText =
    EditText(this).apply {
        hint=hintText;textSize=16f;setSingleLine(true)
        setPadding(16,13,16,13)
        background=android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt());cornerRadius=16f;setStroke(1,0xFFCAD7EA.toInt())
        }
        if(number)inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
private fun FragmentActivity.payButton(label:String,action:()->Unit)=Button(this).apply {
    text=label;isAllCaps=false;textSize=15f;typeface=Typeface.DEFAULT_BOLD
    setTextColor(-1);background=android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFF17447F.toInt(),0xFF09264F.toInt())
    ).apply{cornerRadius=18f}
    setPadding(18,12,18,12);setOnClickListener{action()}
}
private fun FragmentActivity.payPage(title:String):LinearLayout {
    val root=LinearLayout(this).apply{orientation=1;setPadding(18,18,18,24);setBackgroundColor(0xFFF2F6FC.toInt())}
    root.addView(payText("SENE VENTAS",21f,true))
    root.addView(payText(title,23f,true))
    return root
}
private fun FragmentActivity.payShow(root:LinearLayout) {
    val scroll=ScrollView(this);scroll.addView(root);setContentView(scroll)
}
private fun moneyCents(value:Double)=kotlin.math.round(value*100.0).toLong()

class PayrollRequestActivity:FragmentActivity() {
    private var scanCode=""
    private lateinit var root:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);draw()}
    private fun draw(){
        root=payPage("SOLICITA")
        val cfg=EmployeePayroll.config(this)
        val worker=Store.shift(this)?.optString("worker","")?.takeIf{it.isNotBlank()}?:cfg?.employee.orEmpty()
        val cap=EmployeePayroll.capacity(this,0)
        root.addView(payText("TRABAJADOR: "+(worker.ifBlank{"Sin configurar"}),15f,true))
        root.addView(payText("LÍMITE CONJUNTO (50%)\nDisponible para solicitar: "+EmployeePayroll.money(cap.availableCents),18f,true))
        root.addView(payButton("CONSUMO"){consumptionForm(worker)})
        root.addView(payButton("PRÉSTAMO"){loanForm(worker)})
        root.addView(payButton("MI NÓMINA"){startActivity(Intent(this,PayrollEmployeeActivity::class.java))})
        root.addView(payText("El límite combina consumos y préstamos y se calcula sobre el sueldo semanal ajustado por faltas.",13f))
        payShow(root)
    }
    private fun consumptionForm(worker:String){
        val box=LinearLayout(this).apply{orientation=1;setPadding(20,8,20,8)}
        val code=payInput("Código del producto");val qty=payInput("Cantidad",true)
        box.addView(code);box.addView(payButton("ESCANEAR CÓDIGO"){IntentIntegrator(this).setPrompt("Escanea el producto").setBeepEnabled(true).initiateScan()});box.addView(qty)
        AlertDialog.Builder(this).setTitle("SOLICITAR CONSUMO").setMessage("Se descontará de tu nómina en el próximo pago.")
            .setView(box).setPositiveButton("REVISAR",null).setNegativeButton("CANCELAR",null).create().also{dialog->
                dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{
                    val q=qty.text.toString().toIntOrNull()
                    val products=Store.products(this);var found:JSONObject?=null
                    for(i in 0 until products.length()){val p=products.optJSONObject(i)?:continue;if(p.optString("code")==code.text.toString().trim()){found=p;break}}
                    if(found==null){code.error="Producto no encontrado";return@setOnClickListener}
                    if(q==null||q<=0||q>found.optInt("stock")){qty.error="Cantidad no disponible";return@setOnClickListener}
                    val amount=found.optDouble("price")*q
                    val cap=EmployeePayroll.capacity(this,0)
                    val cents=moneyCents(amount)
                    if(cents>cap.availableCents){msgPayroll("Solicitud supera el disponible de "+EmployeePayroll.money(cap.availableCents));return@setOnClickListener}
                    AlertDialog.Builder(this).setTitle("CONFIRMAR CONSUMO")
                        .setMessage(found.optString("name")+"\nCantidad: $q\nImporte: "+Store.money(amount)+"\n\nSe descontará de tu nómina en el próximo pago.\nDisponible después: "+EmployeePayroll.money(cap.availableCents-cents))
                        .setPositiveButton("CONFIRMAR"){_,_->try{
                            val entry=EmployeePayroll.addRequest(this,EmployeePayroll.TYPE_CONSUMPTION,cents,description=found.optString("name")+" x$q",absences=0)
                            if(entry==null){msgPayroll("No se pudo registrar: revisa el límite disponible");return@setPositiveButton}
                            found.put("stock",found.optInt("stock")-q);Store.saveProducts(this,products)
                            Store.addMove(this,JSONObject().put("type","EMPLOYEE_CONSUMPTION").put("ts",System.currentTimeMillis()).put("time",stampPayroll()).put("amount",amount).put("worker",worker).put("text","CONSUMO DE EMPLEADO: $worker | "+found.optString("name")+" x$q | "+Store.money(amount)+" | Descuento de nómina"))
                            msgPayroll("Consumo registrado. Inventario actualizado; no se agregó a caja.")
                            draw()
                        }catch(e:Exception){msgPayroll("No se pudo guardar el consumo: "+(e.message?: "error"))}}
                        .setNegativeButton("VOLVER",null).show()
                    dialog.dismiss()
                }}
                dialog.show()
            }
    }
    private fun loanForm(worker:String){
        val box=LinearLayout(this).apply{orientation=1;setPadding(20,8,20,8)}
        val reason=payInput("Motivo del préstamo");val amount=payInput("Cantidad $",true)
        box.addView(reason);box.addView(amount)
        AlertDialog.Builder(this).setTitle("SOLICITAR PRÉSTAMO")
            .setMessage("El préstamo se descuenta de tu nómina. Al entregarlo, el efectivo sale de caja.")
            .setView(box).setPositiveButton("REVISAR",null).setNegativeButton("CANCELAR",null).create().also{dialog->
                dialog.setOnShowListener{dialog.getButton(-1).setOnClickListener{
                    val value=amount.text.toString().replace(",","." ).toDoubleOrNull()
                    if(reason.text.isBlank()){reason.error="Indica el motivo";return@setOnClickListener}
                    if(value==null||value<=0){amount.error="Cantidad inválida";return@setOnClickListener}
                    val cents=moneyCents(value);val cap=EmployeePayroll.capacity(this,0)
                    if(cents>cap.availableCents){msgPayroll("Solo tienes "+EmployeePayroll.money(cap.availableCents)+" disponibles");return@setOnClickListener}
                    AlertDialog.Builder(this).setTitle("CONFIRMAR PRÉSTAMO")
                        .setMessage("Motivo: "+reason.text+"\nImporte: "+Store.money(value)+"\nDisponible después: "+EmployeePayroll.money(cap.availableCents-cents)+"\n\nEl efectivo se descontará de caja al entregar el préstamo.")
                        .setPositiveButton("CONFIRMAR Y REGISTRAR"){_,_->val entry=EmployeePayroll.addRequest(this,EmployeePayroll.TYPE_LOAN,cents,reason.text.toString())
                            if(entry==null){msgPayroll("No se pudo registrar el préstamo");return@setPositiveButton}
                            Store.addMove(this,JSONObject().put("type","LOAN_DISBURSEMENT").put("ts",System.currentTimeMillis()).put("time",stampPayroll()).put("amount",value).put("worker",worker).put("text","PRÉSTAMO ENTREGADO A $worker | "+reason.text+" | "+Store.money(value)+" | Descuento de nómina"))
                            msgPayroll("Préstamo registrado. Caja reducida en "+Store.money(value));draw()
                        }.setNegativeButton("CANCELAR",null).show()
                    dialog.dismiss()
                }}
                dialog.show()
            }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        val result=IntentIntegrator.parseActivityResult(requestCode,resultCode,data)
        if(result?.contents!=null){scanCode=result.contents;msgPayroll("Código escaneado: $scanCode")}
    }
    private fun msgPayroll(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}

class PayrollEmployeeActivity:FragmentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);draw()}
    private fun draw(){
        val cfg=EmployeePayroll.config(this);val worker=Store.shift(this)?.optString("worker","")?.takeIf{it.isNotBlank()}?:cfg?.employee.orEmpty()
        val entries=EmployeePayroll.entriesForCurrentPeriod(this)
        val consumption=entries.filter{it.type==EmployeePayroll.TYPE_CONSUMPTION}.sumOf{it.amountCents}
        val loans=entries.filter{it.type==EmployeePayroll.TYPE_LOAN}.sumOf{it.amountCents}
        val root=payPage("MI NÓMINA")
        root.addView(payText("Trabajador: "+worker,16f,true))
        if(cfg==null){root.addView(payText("El patrón aún no configuró el sueldo del empleado."));payShow(root);return}
        val gross=cfg.weeklySalaryCents
        val deductions=consumption+loans
        root.addView(payText("Sueldo semanal\n"+EmployeePayroll.money(gross),23f,true))
        root.addView(payText("Días laborables: "+cfg.workDays+" de 7\nDía de descanso: "+dayName(cfg.restDay)+"\nDía de pago: "+dayName(cfg.payDay)))
        root.addView(payText("Faltas registradas: 0\nDescuento por faltas: $0.00\nConsumos pendientes: "+EmployeePayroll.money(consumption)+"\nPréstamos pendientes: "+EmployeePayroll.money(loans)))
        root.addView(payText("TOTAL A RECIBIR\n"+EmployeePayroll.money((gross-deductions).coerceAtLeast(0L)),24f,true))
        root.addView(payText("Historial del periodo",17f,true))
        entries.asReversed().forEach{e->root.addView(payText(SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("es","MX")).format(Date(e.timestamp))+"\n"+(if(e.type==EmployeePayroll.TYPE_LOAN)"PRÉSTAMO" else "CONSUMO")+" · "+e.description+" · "+EmployeePayroll.money(e.amountCents)))}
        payShow(root)
    }
    private fun dayName(day:Int)=listOf("","Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado").getOrElse(day){"-"}
}

class PayrollAdminActivity:FragmentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);draw()}
    private fun draw(){
        val old=EmployeePayroll.config(this)
        val root=payPage("CONSULTAR NÓMINA")
        val name=payInput("Nombre del empleado").apply{setText(old?.employee.orEmpty())}
        val hours=payInput("Horas diarias (1 a 24)",true).apply{setText((old?.dailyHours?:8).toString())}
        val salary=payInput("Sueldo semanal $",true).apply{if(old!=null)setText(String.format(Locale.US,"%.2f",old.weeklySalaryCents/100.0))}
        val days=payInput("Días laborables por semana (1 a 7)",true).apply{setText((old?.workDays?:6).toString())}
        val rest=Spinner(this).apply{adapter=ArrayAdapter(this@PayrollAdminActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado"));setSelection((old?.restDay?:Calendar.SUNDAY)-Calendar.SUNDAY)}
        val pay=Spinner(this).apply{adapter=ArrayAdapter(this@PayrollAdminActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Domingo","Lunes","Martes","Miércoles","Jueves","Viernes","Sábado"));setSelection((old?.payDay?:Calendar.SATURDAY)-Calendar.SUNDAY)}
        root.addView(payText("CONFIGURACIÓN DEL EMPLEADO",17f,true));root.addView(name);root.addView(hours);root.addView(salary);root.addView(days)
        root.addView(payText("Día de descanso"));root.addView(rest);root.addView(payText("Día de pago"));root.addView(pay)
        root.addView(payButton("GUARDAR CONFIGURACIÓN"){
            val h=hours.text.toString().toIntOrNull();val s=salary.text.toString().replace(",","." ).toDoubleOrNull();val d=days.text.toString().toIntOrNull()
            if(name.text.isBlank()||h==null||h !in 1..24||s==null||s<=0||d==null||d !in 1..7){Toast.makeText(this,"Revisa los datos. Horas 1–24, días 1–7 y sueldo válido.",Toast.LENGTH_LONG).show();return@payButton}
            val cfg=EmployeePayroll.Config(name.text.toString(),h,moneyCents(s),d,Calendar.SUNDAY+rest.selectedItemPosition,Calendar.SUNDAY+pay.selectedItemPosition)
            if(EmployeePayroll.saveConfig(this,cfg)){Toast.makeText(this,"Configuración guardada",Toast.LENGTH_LONG).show()}else Toast.makeText(this,"No se pudo guardar",Toast.LENGTH_LONG).show()
        })
        val cfg=EmployeePayroll.config(this)
        if(cfg!=null){
            val cap=EmployeePayroll.capacity(this,0);val rows=EmployeePayroll.entriesForCurrentPeriod(this)
            val cons=rows.filter{it.type==EmployeePayroll.TYPE_CONSUMPTION}.sumOf{it.amountCents}
            val loans=rows.filter{it.type==EmployeePayroll.TYPE_LOAN}.sumOf{it.amountCents}
            val net=(cfg.weeklySalaryCents-cons-loans).coerceAtLeast(0L)
            root.addView(payText("RESUMEN DEL PERIODO",17f,true))
            root.addView(payText("Sueldo semanal: "+EmployeePayroll.money(cfg.weeklySalaryCents)+"\nFaltas: 0 · Descuento: $0.00\nConsumos: "+EmployeePayroll.money(cons)+"\nPréstamos: "+EmployeePayroll.money(loans)+"\nTotal descuentos: "+EmployeePayroll.money(cons+loans)+"\nNeto estimado: "+EmployeePayroll.money(net),17f,true))
            root.addView(payText("Límite 50% ajustado: "+EmployeePayroll.money(cap.limitCents)+"\nComprometido: "+EmployeePayroll.money(cap.committedCents)+"\nDisponible: "+EmployeePayroll.money(cap.availableCents)))
            root.addView(payButton("VER HISTORIAL COMPLETO"){rows.forEach{Toast.makeText(this,it.description+" · "+EmployeePayroll.money(it.amountCents),Toast.LENGTH_SHORT).show()}})
        }
        payShow(root)
    }
}
private fun stampPayroll()=SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale("es","MX")).format(Date())

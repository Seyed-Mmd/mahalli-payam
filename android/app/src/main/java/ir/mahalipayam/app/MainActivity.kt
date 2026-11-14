package ir.mahalipayam.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.NetworkInterface
import java.net.Inet4Address
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var code: EditText
    private lateinit var pin: EditText
    private lateinit var pinConfirm: EditText
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(26),dp(22),dp(24)); layoutDirection=View.LAYOUT_DIRECTION_RTL; setBackgroundColor(0xfff5f7f6.toInt()) }
        val scroll=ScrollView(this); scroll.addView(root)
        root.addView(TextView(this).apply { text="پیام محلی"; textSize=30f; setTextColor(0xff17231f.toInt()); gravity=Gravity.RIGHT; typeface=android.graphics.Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply { text="پنل وب گوشی، فقط روی شبکهٔ محلی"; textSize=15f; setTextColor(0xff70817a.toInt()); setPadding(0,dp(4),0,dp(20)) })
        status=TextView(this).apply { textSize=15f; setTextColor(0xff285849.toInt()); setPadding(dp(14),dp(14),dp(14),dp(14)); setBackgroundColor(0xffe4f3ec.toInt()) }; root.addView(status)
        root.addView(section("۱. دسترسی‌ها"))
        root.addView(TextView(this).apply { text="پیامک، مخاطبین، عکس و ویدیو را یک‌بار اجازه بده. دسترسی همهٔ فایل‌ها را هم از تنظیمات اندروید فعال کن."; textSize=14f; setTextColor(0xff596961.toInt()); setPadding(0,0,0,dp(10)) })
        root.addView(button("درخواست مجوزهای گوشی") { requestPermissionsForFeatures() })
        root.addView(button("فعال‌کردن دسترسی همهٔ فایل‌ها") { openAllFilesSettings() })
        root.addView(button("تنظیمات ماندگاری باتری") { openBatterySettings() })
        root.addView(section("۲. جفت‌سازی حساب"))
        code=edit("کد یک‌بارمصرف سایت",false); root.addView(code)
        root.addView(button("ثبت کد اتصال") { claimPairCode() })
        root.addView(section("۳. رمز پنل محلی"))
        pin=edit("رمز ۸ رقمی پنل",true); root.addView(pin)
        pinConfirm=edit("تکرار رمز",true); root.addView(pinConfirm)
        root.addView(button("ذخیرهٔ رمز روی گوشی") { savePin() })
        root.addView(section("۴. اتصال لپ‌تاپ"))
        root.addView(TextView(this).apply { text="هات‌اسپات گوشی را روشن کن و لپ‌تاپ را به آن وصل کن. اینترنت موبایل لازم نیست. سرویس با اعلان دائمی می‌تواند هنگام قفل بودن صفحه کار کند؛ برنامه را از تنظیمات باتری محدود نکن و اعلان را خاموش نکن."; textSize=14f; setTextColor(0xff596961.toInt()); setPadding(0,0,0,dp(10)) })
        root.addView(button("شروع پنل محلی") { startLocal() })
        root.addView(button("توقف پنل") { stopService(Intent(this,LocalServerService::class.java)); status.text="پنل محلی متوقف شد." })
        root.addView(TextView(this).apply { text="داده‌های گوشی به هاست فرستاده نمی‌شوند. هاست فقط حساب و جفت‌سازی را مدیریت می‌کند."; textSize=12f; setTextColor(0xff70817a.toInt()); setPadding(0,dp(20),0,0) })
        setContentView(scroll); updateStatus()
    }
    private fun section(t:String)=TextView(this).apply { text=t; textSize=18f; setTextColor(0xff17231f.toInt()); typeface=android.graphics.Typeface.DEFAULT_BOLD; setPadding(0,dp(22),0,dp(8)) }
    private fun edit(hintText:String,numeric:Boolean)=EditText(this).apply { hint=hintText; textSize=15f; setSingleLine(true); setPadding(dp(12),dp(12),dp(12),dp(12)); inputType=if(numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
    private fun button(text:String,onClick:()->Unit)=Button(this).apply { this.text=text; isAllCaps=false; setOnClickListener { onClick() } }
    private fun requestPermissionsForFeatures() {
        val perms=mutableListOf(Manifest.permission.READ_SMS,Manifest.permission.READ_CONTACTS)
        if(Build.VERSION.SDK_INT>=33){perms.add(Manifest.permission.READ_MEDIA_IMAGES);perms.add(Manifest.permission.READ_MEDIA_VIDEO)} else if(Build.VERSION.SDK_INT>=23) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if(Build.VERSION.SDK_INT>=34) perms.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        if(Build.VERSION.SDK_INT>=33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed=perms.distinct().filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(needed.isNotEmpty()) requestPermissions(needed.toTypedArray(),11) else {Toast.makeText(this,"مجوزهای درخواستی قبلاً داده شده‌اند.",Toast.LENGTH_SHORT).show();updateStatus()}
    }
    private fun openAllFilesSettings() { if(Build.VERSION.SDK_INT>=30){ try{startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")))}catch(_:Exception){startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))} } else Toast.makeText(this,"این نسخهٔ اندروید از قبل دسترسی ذخیره‌سازی را دارد.",Toast.LENGTH_SHORT).show() }
    private fun openBatterySettings() { if(Build.VERSION.SDK_INT>=23){val pm=getSystemService(android.os.PowerManager::class.java);if(!pm.isIgnoringBatteryOptimizations(packageName)){try{startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:$packageName")))}catch(_:Exception){startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}}else Toast.makeText(this,"برنامه از محدودیت باتری مستثناست.",Toast.LENGTH_SHORT).show()} }
    private fun savePin() {
        val a=pin.text.toString(); val b=pinConfirm.text.toString()
        if(a.length<8 || a!=b){Toast.makeText(this,"رمز باید دست‌کم ۸ رقم باشد و با تکرارش برابر باشد.",Toast.LENGTH_LONG).show();return}
        Thread { val salt=ByteArray(16).also{java.security.SecureRandom().nextBytes(it)}; val spec=PBEKeySpec(a.toCharArray(),salt,120000,256); val hash=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val packed=Base64.encodeToString(salt+hash,Base64.NO_WRAP); SecretStore.put(this,"local_pin",packed)
            runOnUiThread{Toast.makeText(this,"رمز محلی ذخیره شد.",Toast.LENGTH_SHORT).show();updateStatus()} }.start()
    }
    private fun deviceId():String { val p=getSharedPreferences("device",MODE_PRIVATE); var id=p.getString("id",null); if(id==null){id=UUID.randomUUID().toString();p.edit().putString("id",id).apply()};return id!! }
    private fun claimPairCode() {
        val base=BuildConfig.API_BASE_URL.trim(); val c=code.text.toString().trim().uppercase()
        if(base.isBlank()){Toast.makeText(this,"در این APK آدرس api.php تنظیم نشده است؛ workflow را با API_BASE_URL اجرا کن.",Toast.LENGTH_LONG).show();return}
        if(c.length<8){code.error="کוד را از سایت بگیر";return}
        status.text="کد در حال بررسی است…"
        Thread { try {
            val conn=URL(base).openConnection() as HttpURLConnection; conn.requestMethod="POST"; conn.connectTimeout=10000; conn.readTimeout=10000; conn.doOutput=true; conn.setRequestProperty("Content-Type","application/json; charset=utf-8")
            val body=JSONObject().put("action","claim").put("code",c).put("device_id",deviceId()).toString(); conn.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
            val stream=if(conn.responseCode in 200..299) conn.inputStream else conn.errorStream; val text=stream.bufferedReader().use{it.readText()}; val json=JSONObject(text)
            if(conn.responseCode !in 200..299) throw Exception(json.optString("error","کد معتبر نیست"))
            SecretStore.put(this,"device_token",json.getString("device_token"));getSharedPreferences("device",MODE_PRIVATE).edit().putLong("paired_at",System.currentTimeMillis()).apply();stopService(Intent(this,LocalServerService::class.java))
            runOnUiThread{status.text="حساب با گوشی جفت شد. حالا رمز پنل را ذخیره و پنل را روشن کن.";Toast.makeText(this,"اتصال حساب تأیید شد.",Toast.LENGTH_LONG).show()}
        }catch(e:Exception){runOnUiThread{status.text="اتصال به سایت برقرار نشد یا کد معتبر نیست. اینترنت را بررسی کن.";Toast.makeText(this,e.message?:"خطا در اتصال",Toast.LENGTH_LONG).show()}} }.start()
    }
    private fun startLocal() {
        val token=SecretStore.get(this,"device_token")
        if(token.isNullOrBlank()){Toast.makeText(this,"اول کد سایت را ثبت و گوشی را جفت کن.",Toast.LENGTH_LONG).show();return}
        if(SecretStore.get(this,"local_pin").isNullOrBlank()){Toast.makeText(this,"اول رمز محلی را تعیین کن.",Toast.LENGTH_LONG).show();return}
        val missing= mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED) missing.add("پیامک")
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED) missing.add("مخاطبین")
        val allFiles=Build.VERSION.SDK_INT<30 || Environment.isExternalStorageManager()
        if(!allFiles) missing.add("همهٔ فایل‌ها")
        if(missing.isNotEmpty()){Toast.makeText(this,"مجوز لازم را فعال کن: ${missing.joinToString()}",Toast.LENGTH_LONG).show();return}
        val addresses=localAddresses()
        if(addresses.isEmpty()){Toast.makeText(this,"شبکه پیدا نشد. هات‌اسپات گوشی را روشن کن، لپ‌تاپ را وصل کن و دوباره بزن.",Toast.LENGTH_LONG).show();status.text="بدون اتصال محلی؛ هات‌اسپات را روشن کن.";return}
        status.text="در حال بررسی وضعیت حساب…"
        Thread { val allowed=verifyPairing(); runOnUiThread { if(allowed) launchLocal(addresses.first()) else { status.text="حساب تأیید نشد یا مهلت کار آفلاین تمام شده است. اینترنت را وصل و دوباره تلاش کن."; Toast.makeText(this,"وضعیت حساب از سایت تأیید نشد.",Toast.LENGTH_LONG).show() } } }.start()
    }
    private fun launchLocal(address:String){val i=Intent(this,LocalServerService::class.java).putExtra("address",address);if(Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i);status.text="پنل روشن شد\\nدر لپ‌تاپ باز کن: http://$address:8765\\nلپ‌تاپ باید به هات‌اسپات همین گوشی وصل باشد."}
    private fun verifyPairing():Boolean {
        val token=SecretStore.get(this,"device_token")?:return false
        val paired=getSharedPreferences("device",MODE_PRIVATE).getLong("paired_at",0L)
        val base=BuildConfig.API_BASE_URL.trim()
        return try {
            if(base.isBlank()) throw java.io.IOException("no api url")
            val conn=URL(base).openConnection() as HttpURLConnection;conn.requestMethod="POST";conn.connectTimeout=7000;conn.readTimeout=7000;conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.outputStream.use{it.write(JSONObject().put("action","status").put("device_token",token).toString().toByteArray(Charsets.UTF_8))}
            when(conn.responseCode){in 200..299->true;401,403->{SecretStore.remove(this,"device_token");false};else->paired>0}
        }catch(_:Exception){paired>0}
    }
    private fun localAddresses():List<String> = try { val out=mutableListOf<String>(); val es=NetworkInterface.getNetworkInterfaces(); while(es.hasMoreElements()){val n=es.nextElement();if(!n.isUp||n.isLoopback)continue;val name=n.name.lowercase();if(!(name.startsWith("wlan")||name.startsWith("swlan")||name.startsWith("wifi")||name.startsWith("ap")||name.startsWith("softap")))continue;val asx=n.inetAddresses;while(asx.hasMoreElements()){val a=asx.nextElement();if(a is Inet4Address && a.isSiteLocalAddress && !a.isLoopbackAddress)out.add(a.hostAddress?:"")}};out.distinct() }catch(_:Exception){emptyList()}
    private fun updateStatus(){
        val pinOk=!SecretStore.get(this,"local_pin").isNullOrBlank(); val paired=!SecretStore.get(this,"device_token").isNullOrBlank(); val ip=localAddresses().firstOrNull()
        status.text=when { !paired -> "حساب گوشی جفت نشده است. کد را از سایت بگیر و اینجا وارد کن."; !pinOk -> "رمز پنل محلی هنوز تعیین نشده است."; ip!=null -> "شبکهٔ محلی آماده است: http://$ip:8765"; else -> "حساب و رمز آماده‌اند. برای استفاده، هات‌اسپات گوشی را روشن کن." }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);updateStatus(); if(requestCode==11) Toast.makeText(this,"اگر مجوزی رد شد، آن را از تنظیمات برنامه فعال کن.",Toast.LENGTH_SHORT).show()}
}


package ir.mahalipayam.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class LocalServerService : Service() {
    private val running=AtomicBoolean(false)
    private var server:ServerSocket?=null
    private val pool=Executors.newFixedThreadPool(6)
    private val authScheduler=Executors.newSingleThreadScheduledExecutor()
    private val sessions=ConcurrentHashMap<String,Long>()
    private val failures=ConcurrentHashMap<String,Pair<Int,Long>>()
    private val idleMillis=5*60*1000L
    @Volatile private var accessRevoked=false
    @Volatile private var bindAddress=""
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        bindAddress=intent?.getStringExtra("address")?:""
        makeChannel()
        val notification=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,"local_panel").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("پنل محلی روشن است").setContentText("لپ‌تاپ را به هات‌اسپات گوشی وصل کن.").setOngoing(true).build() else Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("پنل محلی روشن است").setOngoing(true).build()
        startForeground(42,notification)
        if(running.compareAndSet(false,true)) { Thread({serve()},"local-panel-server").start(); scheduleAuthCheck() }
        return START_NOT_STICKY
    }
    private fun serve(){try{if(bindAddress.isBlank())throw IllegalStateException("No local network address");server=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(bindAddress,8765))};while(running.get()){val s=server?.accept()?:break;pool.execute{handle(s)}}}catch(_:Exception){stopSelf()}}
    private fun scheduleAuthCheck(){if(!running.get())return;val delay=8L+SecureRandom().nextInt(13).toLong();authScheduler.schedule({if(!running.get())return@schedule;try{val token=SecretStore.get(this,"device_token");val endpoint=BuildConfig.API_BASE_URL.trim();if(!token.isNullOrBlank()&&endpoint.isNotBlank()){val conn=URL(endpoint).openConnection() as HttpURLConnection;conn.requestMethod="POST";conn.connectTimeout=6000;conn.readTimeout=6000;conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.outputStream.use{it.write(JSONObject().put("action","status").put("device_token",token).toString().toByteArray(Charsets.UTF_8))};if(conn.responseCode==401||conn.responseCode==403){accessRevoked=true;SecretStore.remove(this,"device_token");conn.disconnect();return@schedule};conn.disconnect()}}catch(_:Exception){};scheduleAuthCheck()},delay,java.util.concurrent.TimeUnit.MINUTES)}
    private data class Req(val method:String,val path:String,val query:Map<String,String>,val headers:Map<String,String>,val body:ByteArray)
    private fun handle(socket:Socket){socket.use{s->try{s.soTimeout=8000;val req=parse(s.getInputStream().buffered())?:return
        if(req.method !in setOf("GET","POST")){respond(s,405,"text/plain; charset=utf-8","فقط درخواست خواندنی پشتیبانی می‌شود.");return}
        if(accessRevoked&&req.path.startsWith("/api/")){respond(s,403,"application/json; charset=utf-8","{\"error\":\"access_revoked\"}");return}
        if(req.path=="/"&&req.method=="GET"){val page=assets.open("local.html").use{it.readBytes().toString(Charsets.UTF_8)};respond(s,200,"text/html; charset=utf-8",page,listOf("Cache-Control: public, max-age=86400"));return}
        if(req.path=="/api/local/login"&&req.method=="POST"){login(s,req);return}
        if(req.path=="/api/local/logout"&&req.method=="POST"){val id=cookie(req,"LSESSION");if(id!=null)sessions.remove(id);respond(s,200,"application/json","{}",listOf("Set-Cookie: LSESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"));return}
        val sid=cookie(req,"LSESSION");if(!authorized(sid)){respond(s,401,"application/json","{\"error\":\"locked\"}");return};if(sid!=null)sessions[sid]=System.currentTimeMillis()
        when(req.path){
            "/api/sms"->respond(s,200,"application/json; charset=utf-8",sms(req.query).toString())
            "/api/contacts"->respond(s,200,"application/json; charset=utf-8",contacts().toString())
            "/api/files"->respond(s,200,"application/json; charset=utf-8",files(req.query["path"]?:"",req.query["offset"]?.toIntOrNull()?.coerceAtLeast(0)?:0).toString())
            "/api/file"->streamFile(s,req.query["path"]?:"",req.headers["range"])
            "/api/media"->respond(s,200,"application/json; charset=utf-8",media(req.query["offset"]?.toIntOrNull()?.coerceAtLeast(0)?:0).toString())
            "/api/media/file"->streamMedia(s,req.query,req.headers["range"])
            else->respond(s,404,"application/json","{\"error\":\"not_found\"}")
        }
    }catch(_:Exception){}}}
    private fun parse(input:BufferedInputStream):Req?{val head=ByteArrayOutputStream();var state=0;while(head.size()<12000){val b=input.read();if(b<0)return null;head.write(b);state=when{state==0&&b==13->1;state==1&&b==10->2;state==2&&b==13->3;state==3&&b==10->4;else->if(b==13)1 else 0};if(state==4)break};if(state!=4)return null
        val lines=head.toString("UTF-8").split("\r\n");val first=lines.firstOrNull()?.split(' ')?:return null;if(first.size<2)return null;val uri=Uri.parse(first[1]);val headers=lines.drop(1).mapNotNull{val i=it.indexOf(':');if(i>0)it.substring(0,i).trim().lowercase() to it.substring(i+1).trim() else null}.toMap();val len=(headers["content-length"]?:"0").toIntOrNull()?.coerceIn(0,65536)?:0;val body=ByteArray(len);var offset=0;while(offset<len){val got=input.read(body,offset,len-offset);if(got<0)break;offset+=got};val query=mutableMapOf<String,String>();uri.encodedQuery?.split('&')?.forEach{p->val a=p.split('=',limit=2);query[decode(a[0])]=if(a.size>1)decode(a[1]) else ""};return Req(first[0],uri.path?:"/",query,headers,body)}
    private fun decode(s:String)=try{URLDecoder.decode(s,"UTF-8")}catch(_:Exception){s}
    private fun cookie(r:Req,name:String):String?=r.headers["cookie"]?.split(';')?.map{it.trim()}?.firstOrNull{it.startsWith("$name=")}?.substringAfter('=')
    private fun authorized(id:String?):Boolean{if(id==null)return false;val last=sessions[id]?:return false;if(System.currentTimeMillis()-last>idleMillis){sessions.remove(id);return false};return true}
    private fun login(s:Socket,r:Req){val remote=s.inetAddress.hostAddress?:"local";val f=failures[remote];if(f!=null&&f.first>=8&&System.currentTimeMillis()-f.second<60000){respond(s,429,"application/json","{\"error\":\"too_many_attempts\"}");return};val input=try{JSONObject(String(r.body,Charsets.UTF_8))}catch(_:Exception){JSONObject()};val given=input.optString("pin","");val stored=SecretStore.get(this,"local_pin")?:"";val data=try{Base64.decode(stored,Base64.NO_WRAP)}catch(_:Exception){byteArrayOf()};var okay=false
        if(data.size==48&&given.isNotEmpty()){val salt=data.copyOfRange(0,16);val expected=data.copyOfRange(16,48);val spec=PBEKeySpec(given.toCharArray(),salt,120000,256);val actual=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded;okay=java.security.MessageDigest.isEqual(expected,actual)}
        if(!okay){failures[remote]=Pair((f?.first?:0)+1,System.currentTimeMillis());respond(s,401,"application/json","{\"error\":\"wrong_pin\"}");return};failures.remove(remote);val id=ByteArray(32).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)};sessions[id]=System.currentTimeMillis();respond(s,200,"application/json","{\"ok\":true,\"idle_seconds\":300}",listOf("Set-Cookie: LSESSION=$id; Path=/; HttpOnly; SameSite=Strict; Max-Age=300"))}
    private fun sms(q:Map<String,String>):JSONArray{val out=JSONArray();val search=q["q"]?.trim()?.take(100).orEmpty();val offset=q["offset"]?.toIntOrNull()?.coerceAtLeast(0)?:0;var cur:Cursor?=null;try{cur=contentResolver.query(Telephony.Sms.CONTENT_URI,arrayOf(Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms.TYPE),if(search.isBlank())null else "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?",if(search.isBlank())null else arrayOf("%$search%","%$search%"),"${Telephony.Sms.DATE} DESC LIMIT 100 OFFSET $offset");cur?.use{c->val contacts=contactMap();val ai=c.getColumnIndex(Telephony.Sms.ADDRESS);val bi=c.getColumnIndex(Telephony.Sms.BODY);val di=c.getColumnIndex(Telephony.Sms.DATE);val ti=c.getColumnIndex(Telephony.Sms.TYPE);while(c.moveToNext()){val number=if(ai>=0)c.getString(ai).orEmpty()else"";out.put(JSONObject().put("number",number).put("name",contacts[normalize(number)]?:"").put("body",if(bi>=0)c.getString(bi).orEmpty()else"").put("date",if(di>=0)c.getLong(di)else 0L).put("type",if(ti>=0)c.getInt(ti)else 0))}}}catch(_:Exception){};return out}
    private fun normalize(s:String)=s.filter{it.isDigit()}.takeLast(10)
    private fun contactMap():Map<String,String>{val map=HashMap<String,String>();var c:Cursor?=null;try{c=contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),null,null,null);c?.use{while(it.moveToNext()){val n=it.getString(0).orEmpty();map[normalize(n)]=it.getString(1).orEmpty()}}}catch(_:Exception){};return map}
    private fun contacts():JSONArray{val out=JSONArray();try{contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER),null,null,"${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC")?.use{c->val seen=HashSet<String>();while(c.moveToNext()){val name=c.getString(0).orEmpty();val number=c.getString(1).orEmpty();val key="$name|$number";if(seen.add(key))out.put(JSONObject().put("name",name).put("number",number))}}}catch(_:Exception){};return out}
    private fun rootDir()=Environment.getExternalStorageDirectory().canonicalFile
    private fun safeFile(path:String):File?=try{val root=rootDir();val f=File(root,path).canonicalFile;if(f.path==root.path||f.path.startsWith(root.path+File.separator))f else null}catch(_:Exception){null}
    private fun files(path:String,offset:Int):JSONArray{val out=JSONArray();val dir=safeFile(path)?:return out;if(!dir.isDirectory||!dir.canRead())return out;try{dir.listFiles()?.sortedWith(compareByDescending<File>{it.isDirectory}.thenBy{it.name.lowercase()})?.drop(offset)?.take(200)?.forEach{f->if(f.canRead()&&!f.name.startsWith(".")){val rel=f.relativeTo(rootDir()).path;out.put(JSONObject().put("name",f.name).put("path",rel).put("directory",f.isDirectory).put("size",if(f.isFile)f.length()else 0).put("modified",f.lastModified()).put("mime",if(f.isFile)android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())?:"application/octet-stream" else ""))}}}catch(_:Exception){};return out}
    private fun media(offset:Int):JSONArray{val out=JSONArray();if(Build.VERSION.SDK_INT>=33&&!Environment.isExternalStorageManager()){if(checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&checkSelfPermission(android.Manifest.permission.READ_MEDIA_VIDEO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return out};try{val base=MediaStore.Files.getContentUri("external");val projection=arrayOf(MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_MODIFIED,MediaStore.Files.FileColumns.MEDIA_TYPE);val selection="${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?";val args=arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString());contentResolver.query(base,projection,selection,args,"${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT 200 OFFSET $offset")?.use{c->while(c.moveToNext()){val id=c.getLong(0);val mediaType=c.getInt(5);val type=if(mediaType==MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)"video" else "image";out.put(JSONObject().put("id",id).put("name",c.getString(1).orEmpty()).put("mime",c.getString(2).orEmpty()).put("size",c.getLong(3)).put("date",c.getLong(4)*1000).put("type",type))}}}catch(_:Exception){};return out}
    private fun mediaUri(id:Long,type:String):Uri=ContentUris.withAppendedId(if(type=="video")MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id)
    private fun streamMedia(s:Socket,q:Map<String,String>,range:String?){val id=q["id"]?.toLongOrNull()?:return respond(s,400,"text/plain","bad id");val type=q["type"]?:"image";try{contentResolver.openAssetFileDescriptor(mediaUri(id,type),"r")?.use{afd->val length=if(afd.length>=0)afd.length else mediaSize(id);stream(s,afd.createInputStream(),length,contentResolver.getType(mediaUri(id,type))?:"application/octet-stream",range)}}catch(_:Exception){respond(s,404,"text/plain","not found")}}
    private fun mediaSize(id:Long):Long{try{contentResolver.query(MediaStore.Files.getContentUri("external"),arrayOf(MediaStore.Files.FileColumns.SIZE),"${MediaStore.Files.FileColumns._ID}=?",arrayOf(id.toString()),null)?.use{if(it.moveToFirst())return it.getLong(0)}}catch(_:Exception){};return -1L}
    private fun streamFile(s:Socket,path:String,range:String?){val file=safeFile(path);if(file==null||!file.isFile||!file.canRead()){respond(s,404,"text/plain","not found");return};try{stream(s,file.inputStream(),file.length(),android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())?:"application/octet-stream",range)}catch(_:Exception){respond(s,404,"text/plain","not found")}}
    private fun stream(s:Socket,input:java.io.InputStream,length:Long,mime:String,range:String?){input.use{source->var start=0L;var end=length-1;var status=200;val headers=mutableListOf("Accept-Ranges: bytes");if(range!=null&&length>0){val m=Regex("bytes=(\\d*)-(\\d*)").matchEntire(range.trim());if(m!=null){start=m.groupValues[1].toLongOrNull()?:0L;end=m.groupValues[2].toLongOrNull()?:end;if(start<length&&end>=start){end=end.coerceAtMost(length-1);var skipped=0L;while(skipped<start){val n=source.skip(start-skipped);if(n<=0)break;skipped+=n};status=206;headers.add("Content-Range: bytes $start-$end/$length")}}};val count=(end-start+1).coerceAtLeast(0);writeHeaders(s,status,mime,count,headers);val out=s.getOutputStream();val buffer=ByteArray(16*1024);var left=count;while(left>0){val n=source.read(buffer,0,minOf(buffer.size.toLong(),left).toInt());if(n<0)break;out.write(buffer,0,n);left-=n};out.flush()}}
    private fun respond(s:Socket,status:Int,type:String,body:String,extra:List<String> = emptyList()){respondBytes(s,status,type,body.toByteArray(Charsets.UTF_8),extra)}
    private fun respondBytes(s:Socket,status:Int,type:String,bytes:ByteArray,extra:List<String> = emptyList()){writeHeaders(s,status,type,bytes.size.toLong(),extra);val out=s.getOutputStream();out.write(bytes);out.flush()}
    private fun writeHeaders(s:Socket,status:Int,type:String,length:Long,extra:List<String> = emptyList()){val reason=when(status){200->"OK";206->"Partial Content";401->"Unauthorized";403->"Forbidden";404->"Not Found";405->"Method Not Allowed";429->"Too Many Requests";416->"Range Not Satisfiable";else->"Bad Request"};val cache=extra.firstOrNull{it.startsWith("Cache-Control:",true)}?:"Cache-Control: no-store";val headers=listOf("HTTP/1.1 $status $reason","Content-Type: $type","Content-Length: $length","Connection: close",cache,"X-Content-Type-Options: nosniff","Content-Security-Policy: default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'")+extra.filterNot{it.startsWith("Cache-Control:",true)};val out=s.getOutputStream();out.write((headers.joinToString("\r\n")+"\r\n\r\n").toByteArray(Charsets.UTF_8));out.flush()}
    private fun makeChannel(){if(Build.VERSION.SDK_INT>=26){getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("local_panel","پنل محلی",NotificationManager.IMPORTANCE_LOW))}}
    override fun onDestroy(){running.set(false);try{server?.close()}catch(_:Exception){};authScheduler.shutdownNow();pool.shutdownNow();sessions.clear();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}


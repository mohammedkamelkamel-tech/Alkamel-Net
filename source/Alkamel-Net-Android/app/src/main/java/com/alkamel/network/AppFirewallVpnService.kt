package com.alkamel.network
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
class AppFirewallVpnService:VpnService(){
 private var tunnel:ParcelFileDescriptor?=null
 private val running=AtomicBoolean(false)
 private var worker:Thread?=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"جدار التطبيقات",NotificationManager.IMPORTANCE_LOW))
  val notification=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("منع بعض التطبيقات من استخدام الإنترنت").setContentText("حظر التطبيقات المحددة مفعّل").setOngoing(true).build() else Notification.Builder(this).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("منع بعض التطبيقات من استخدام الإنترنت").setContentText("حظر التطبيقات المحددة مفعّل").setOngoing(true).build()
  startForeground(NOTIFICATION_ID,notification); establishFirewall(); return START_STICKY
 }
 @Synchronized private fun establishFirewall(){
  closeTunnel()
  val blocked=getSharedPreferences("app_firewall",MODE_PRIVATE).getStringSet("blocked_packages",emptySet())?.toSet()?:emptySet()
  if(blocked.isEmpty()){stopSelf();return}
  try {
   val builder=Builder().setSession("شبكة الكامل - جدار التطبيقات").setMtu(1500).addAddress("10.111.0.1",32).addRoute("0.0.0.0",0).addAddress("fd00:111:111::1",128).addRoute("::",0)
   blocked.filter{it!=packageName}.forEach{builder.addAllowedApplication(it)}
   tunnel=builder.establish()?:throw IllegalStateException("VPN tunnel unavailable")
   val fd=tunnel!!.fileDescriptor; running.set(true)
   worker=Thread({try{FileInputStream(fd).use{input->val packet=ByteArray(32767);while(running.get()){if(input.read(packet)<0)break}}}catch(e:Exception){if(running.get())Log.e(TAG,"Firewall packet loop failed",e)}},"Alkamel-App-Firewall").apply{isDaemon=true;start()}
  }catch(e:Exception){Log.e(TAG,"Unable to start app firewall",e);closeTunnel();stopSelf()}
 }
 @Synchronized private fun closeTunnel(){running.set(false);runCatching{tunnel?.close()};tunnel=null;worker?.interrupt();worker=null}
 override fun onDestroy(){closeTunnel();stopForeground(true);super.onDestroy()}
 companion object{private const val CHANNEL="alkamel_app_firewall";private const val NOTIFICATION_ID=7421;private const val TAG="AlkamelFirewall"}
}

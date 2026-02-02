package com.mcaw.ui
import android.Manifest; 
import android.content.Intent; 
import android.content.pm.PackageManager; 
import android.os.Bundle; 
import android.widget.Button; 
import androidx.activity.ComponentActivity; 
import androidx.core.app.ActivityCompat; 
import androidx.core.content.ContextCompat; 
import com.mcaw.app.R; 
import com.mcaw.service.McawService; 

class MainActivity: ComponentActivity(){ override fun onCreate(savedInstanceState: Bundle?){ super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); findViewById<Button>(R.id.btnStart).setOnClickListener{ start() }; findViewById<Button>(R.id.btnStop).setOnClickListener{ stopService(Intent(this, McawService::class.java)) } } private fun start(){ val p=arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION); val miss=p.filter{ ContextCompat.checkSelfPermission(this,it)!=PackageManager.PERMISSION_GRANTED }; if(miss.isNotEmpty()) ActivityCompat.requestPermissions(this, miss.toTypedArray(), 1001) else ContextCompat.startForegroundService(this, Intent(this, McawService::class.java)) } override fun onRequestPermissionsResult(rc:Int, perms:Array<out String>, res:IntArray){ super.onRequestPermissionsResult(rc,perms,res); if(rc==1001 && res.all{it==PackageManager.PERMISSION_GRANTED}) ContextCompat.startForegroundService(this, Intent(this, McawService::class.java)) } }

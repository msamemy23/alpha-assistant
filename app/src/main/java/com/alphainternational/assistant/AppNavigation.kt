package com.alphainternational.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.lifecycle.AndroidViewModel

data class NavigationRequest(val id:Long,val destination:String,val workspace:String)

object AppNavigation {
    fun intent(context:Context,destination:String,workspace:String=""):Intent =
        Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination",destination).putExtra("workspace",workspace)
}


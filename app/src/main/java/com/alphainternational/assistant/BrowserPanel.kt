package com.alphainternational.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The live web browser inside a chat, only when a bot needs the owner to type something themselves: a sign-in, a code, personal details.
 * It no longer pops up while a bot merely browses (the owner, 2026-10-12: "I don't need that browser to pop up at all"); to watch a bot work, the
 * Watch button on its live card opens the full-screen Computer view, which shows the page the bot is on.
 */
@Composable fun BrowserPanel(activity:MainActivity,c:Controller,fullScreen:()->Unit) {
    val stage=c.browserStage
    if(stage!=Controller.BrowserStage.NEEDS_YOU || c.panelClosed) return
    val needsYou=stage==Controller.BrowserStage.NEEDS_YOU
    val screen=LocalConfiguration.current
    val host=runCatching {android.net.Uri.parse(c.browserUrl).host?.removePrefix("www.")}.getOrNull().orEmpty()
    val accent=if(needsYou) Amber else Purple
    Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp).clip(RoundedCornerShape(16.dp)).background(SheetBg)
        .border(1.dp,if(needsYou) Amber else Line,RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=2.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent,CircleShape));Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical=6.dp)) {
                Text(if(needsYou) c.browserWho.ifBlank {"A bot"}+" needs you" else c.browserWho.ifBlank {"A bot"}+" is browsing",color=Ink,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
                val sub=if(needsYou) c.browserNeed else (host.ifBlank {c.browserTitle})
                if(sub.isNotBlank()) Text(sub,color=if(needsYou) Amber else Muted,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            if(!needsYou) {
                androidx.compose.material3.IconButton(onClick=fullScreen,modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.OpenInFull,"Full screen",tint=Muted,modifier=Modifier.size(16.dp))}
                androidx.compose.material3.IconButton(onClick={c.panelCollapsed=!c.panelCollapsed},modifier=Modifier.size(34.dp)) {
                    Icon(if(c.panelCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,if(c.panelCollapsed) "Show the browser" else "Minimise",tint=Muted,modifier=Modifier.size(18.dp))
                }
                androidx.compose.material3.IconButton(onClick={c.panelClosed=true},modifier=Modifier.size(34.dp)) {Icon(Icons.Outlined.Close,"Hide the browser",tint=Muted,modifier=Modifier.size(16.dp))}
            }
        }
        // Minimised: the bar stays, the page doesn't take room. The browser keeps working underneath.
        if(c.panelCollapsed && !needsYou) return@Column
        // Watching is a fixed, readable window; when the owner has to type, the page gets a lot more room.
        val height=if(needsYou) (screen.screenHeightDp*0.52f).coerceIn(300f,520f).dp else 270.dp
        Box(Modifier.fillMaxWidth().height(height)) {
            BrowserSurface(activity,true,Modifier.matchParentSize(),interactive=needsYou)
        }
        if(needsYou) Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(onClick={c.browserDone()},modifier=Modifier.weight(1f).height(44.dp),shape=RoundedCornerShape(22.dp),
                colors=ButtonDefaults.buttonColors(containerColor=Strong,contentColor=OnStrong)) {Text("Done",fontWeight=FontWeight.SemiBold)}
            TextButton(onClick={c.browserDecline()}) {Text("Never mind",color=Muted)}
            TextButton(onClick=fullScreen) {Text("Full screen",color=Muted,fontSize=12.sp)}
        }
    }
}

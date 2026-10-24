package com.alphainternational.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * Screens that other parts of the app add (Backup, Proactive and so on), so Settings can offer a row for each without knowing how it is built.
 * A row in Settings is shown only once its screen has been registered (or marked as there with [show]); until then it simply isn't drawn.
 *
 *     Screens.register("backup","Backup") {activity,c,back -> BackupScreen(activity,c)}
 *
 * The app draws the page bar and the back button around whatever is registered. A part that already added its own branch to the app's screen list
 * only needs `Screens.show("its-route")` to make the Settings row appear.
 */
object Screens {
    class Entry(val title:String,val content:@Composable (MainActivity,Controller,()->Unit)->Unit)
    private val table=mutableStateMapOf<String,Entry>()
    private val shown=mutableStateMapOf<String,Boolean>()

    fun register(route:String,title:String,content:@Composable (MainActivity,Controller,()->Unit)->Unit) {table[route]=Entry(title,content)}
    /** Marks a route as existing (its screen is drawn by the app's own screen list) without registering content. */
    fun show(route:String) {shown[route]=true}
    fun has(route:String)=route in table || shown[route]==true
    fun get(route:String):Entry?=table[route]
}

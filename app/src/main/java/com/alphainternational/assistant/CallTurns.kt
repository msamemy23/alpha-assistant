package com.alphainternational.assistant

/** A lease makes callbacks from cancelled recognition or an earlier call harmless. */
internal class CallTurns {
    data class Lease(val session:Long,val id:Long)
    var session=0L;private set
    var connected=false;private set
    var muted=false;private set
    var taskId="";private set
    private var nextId=0L
    private var input:Lease?=null
    private val outputs=mutableSetOf<Lease>()
    val outputActive get()=outputs.isNotEmpty()
    val canListen get()=connected && !muted && taskId.isBlank() && !outputActive && input==null
    fun connect() {
        session++;connected=true;muted=false;taskId="";input=null;outputs.clear()
    }
    fun end() {session++;connected=false;taskId="";input=null;outputs.clear()}
    fun setMuted(value:Boolean) {muted=value;if(value)stopInput()}
    fun beginInput():Lease? {
        if(!canListen)return null
        return Lease(session,++nextId).also {input=it}
    }
    fun isCurrentInput(lease:Lease)=connected && !muted && !outputActive && taskId.isBlank() && input==lease
    fun finishInput(lease:Lease):Boolean {
        if(!isCurrentInput(lease))return false
        input=null;return true
    }
    fun stopInput() {input=null}
    fun awaitTask(id:String) {taskId=id;stopInput()}
    fun finishTask(id:String):Boolean {
        if(!connected || id.isBlank() || taskId!=id)return false
        taskId="";return true
    }
    fun clearTask():String {val previous=taskId;taskId="";stopInput();return previous}
    fun beginOutput():Lease {
        stopInput()
        return Lease(session,++nextId).also {outputs+=it}
    }
    fun finishOutput(lease:Lease) {outputs-=lease}
}

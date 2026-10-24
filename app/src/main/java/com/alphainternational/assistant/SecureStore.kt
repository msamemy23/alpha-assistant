package com.alphainternational.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Everything Alpha saves is encrypted with AES-GCM. Two layers: the phone's Keystore key (it never leaves the secure hardware) protects a random
 * data key, and the data key (kept only in memory once opened) seals each saved file in StateFiles. The Keystore is slow for many small files, and
 * this way a save of one changed request costs one fast encryption. Backup of the app's data is off (allowBackup=false), so a lost or reset phone
 * loses the key, and with it the data; Settings > Backup is the way to keep a copy.
 *
 * Saved data is in noBackupFilesDir/alpha-data (see StateFiles). Versions up to 0.15 kept it all in one file, alpha-state.enc; that file is read
 * once, copied into the new files, checked, and kept as alpha-state.migrated for two weeks.
 */
class SecureStore(private val context:Context) {
    private val legacy = AtomicFile(File(context.noBackupFilesDir,"alpha-state.enc"))
    private val dataDir = File(context.noBackupFilesDir,"alpha-data")
    private val keyFile = File(dataDir,"datakey.wrap")
    private val keyCopy = File(dataDir,"datakey.wrap.copy")
    private val stateKey = "alpha.state.v1"
    private val vaultKey = "alpha.vault.v1"
    private fun key(alias:String, auth:Boolean=false):SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias,null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
        if (auth) spec.setUserAuthenticationRequired(true).setUserAuthenticationParameters(120,KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }
    private fun seal(text:String,alias:String,auth:Boolean=false):String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key(alias,auth)) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    private fun open(text:String,alias:String,auth:Boolean=false):String {
        val bytes = Base64.getDecoder().decode(text)
        require(bytes.size > 28) { "Encrypted data is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(alias,auth),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
    /** The key is really gone or the file is damaged (as opposed to the Keystore being briefly unavailable, which must never look like lost data). */
    private fun permanent(e:Throwable):Boolean = generateSequence(e) {it.cause}.any {it is AEADBadTagException || it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException || it is IllegalArgumentException}

    /** The data key: opened from its wrapped copy (two copies are kept), or made on the very first start. */
    private fun dataKey():ByteArray {
        dataDir.mkdirs()
        var transient:Exception?=null
        for (file in listOf(keyFile,keyCopy)) {
            if (!file.isFile) continue
            try { return Base64.getDecoder().decode(open(file.readText(),stateKey)).also { require(it.size==32) } }
            catch (e:Exception) { if (!permanent(e)) transient=e }
        }
        if (transient!=null) throw StoreUnavailable("The phone's key store did not answer.",transient)
        // Nothing usable: the very first start, or the Keystore key is gone (the phone was reset). Whatever was there can't be opened any more.
        // (The new key is made and sealed first: if the Keystore can't do that right now, nothing old has been touched.)
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = try { seal(Base64.getEncoder().encodeToString(fresh),stateKey).toByteArray() }
            catch (e:Exception) { throw StoreUnavailable("The phone's key store did not answer.",e) }
        listOf(keyFile,keyCopy).filter { it.isFile }.forEach { runCatching { it.renameTo(File(dataDir,it.name+".lost-"+System.currentTimeMillis())) } }
        SafeFile.write(keyFile,wrapped);SafeFile.write(keyCopy,wrapped)
        return fresh
    }
    private val locked = object:DataCrypto {
        override fun seal(plain:ByteArray):ByteArray = throw StoreUnavailable("Saved data is locked for now.")
        override fun open(sealed:ByteArray):ByteArray = throw StoreUnavailable("Saved data is locked for now.")
    }
    private var unavailable = false
    /** The saved files. If the Keystore isn't answering, this is a locked, read-only copy: nothing is read as damaged and nothing is ever written. */
    val files:StateFiles by lazy {
        try { StateFiles(dataDir,AesCrypto(dataKey())) }
        catch (e:StoreUnavailable) { unavailable = true; StateFiles(dataDir,locked).also { it.readOnly = true } }
    }

    class Boot(val loaded:StateFiles.Loaded, val locked:Boolean)
    /**
     * Opens the saved core data (bots, memories, settings…). If the files are damaged it uses the newest good backup and says so; the first start
     * after an update brings the old single-file save across. The chats and requests are read separately with files.loadBulk().
     */
    @Synchronized fun boot():Boot {
        val store = files
        if (unavailable) return Boot(StateFiles.Loaded(JSONObject(),listOf("Alpha couldn't open its saved data just now. Nothing is lost: close Alpha and open it again. Changes made until then won't be kept.")),true)
        val loaded = try { store.loadCore(::legacyText) }
            catch (e:StoreUnavailable) { store.readOnly = true; unavailable = true
                return Boot(StateFiles.Loaded(JSONObject(),listOf("Alpha couldn't open its saved data just now. Nothing is lost: close Alpha and open it again. Changes made until then won't be kept.")),true) }
        // Opened from the new files while the old save is still around: an earlier start finished the copy but not the tidying.
        if (!loaded.fromOldFormat && legacy.baseFile.isFile) retireLegacy()
        store.deleteOld(File(context.noBackupFilesDir,"alpha-state.migrated"))
        return Boot(loaded,false)
    }
    /** The old save's text, or null if there isn't one. A key that's really gone is reported and the file set aside; a Keystore hiccup stops the start safely. */
    private fun legacyText():String? {
        if (!legacy.baseFile.exists()) return null
        return try { open(legacy.readFully().toString(Charsets.UTF_8),stateKey) }
        catch (e:Exception) {
            if (!permanent(e)) throw StoreUnavailable("The phone's key store did not answer.",e)
            android.util.Log.w("AlphaState","The old save could not be opened ("+e.javaClass.simpleName+"); keeping the file and starting fresh.")
            runCatching { legacy.baseFile.renameTo(File(legacy.baseFile.parentFile,"alpha-state.unreadable-"+System.currentTimeMillis()+".enc")) }
            throw e
        }
    }
    /** The copy into the new files checked out: the old single file is kept, renamed, for two weeks in case anything looks wrong. */
    fun retireLegacy() {
        runCatching {
            val target = File(context.noBackupFilesDir,"alpha-state.migrated")
            target.delete()
            if (legacy.baseFile.renameTo(target)) target.setLastModified(System.currentTimeMillis())
        }
    }
    val hasLegacy get() = legacy.baseFile.exists()

    /** Everything as one JSON object (core, plus "tasks" and "messages" arrays). Kept for tests and older callers. */
    @Synchronized fun load():JSONObject {
        val state = files.loadAll(::legacyText)
        if (legacy.baseFile.isFile && files.verify(state.optJSONArray("tasks")?.length()?:0,state.optJSONArray("messages")?.length()?:0)) retireLegacy()
        return state
    }
    /** Replaces everything saved with this state. Kept for tests and older callers. */
    @Synchronized fun save(state:JSONObject) = files.saveAll(state)
    @Synchronized fun saveText(text:String) = save(JSONObject(text))
    fun sealPassword(password:String) = seal(password,vaultKey,true)
    fun openPassword(ciphertext:String) = open(ciphertext,vaultKey,true)
}

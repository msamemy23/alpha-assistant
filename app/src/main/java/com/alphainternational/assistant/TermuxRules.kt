package com.alphainternational.assistant

/**
 * The rules for the bots' command line on the phone (Termux). A shell plus a web page or email that tries to steer a bot is dangerous, so Alpha
 * enforces hard limits itself instead of asking the model to behave: each bot works in its own folder, risky commands are refused, programs are
 * installed only from a list, every command has a time limit, and nothing here touches Alpha's private data. (Pure rules, tested without a phone.)
 */
object TermuxRules {
    const val HOME="/data/data/com.termux/files/home"
    const val USR="/data/data/com.termux/files/usr"
    const val BASH="$USR/bin/bash"
    const val MAX_SECONDS=1800
    const val DEFAULT_SECONDS=120

    /** Programs a bot may install with install_package (Termux packages). */
    val packages=setOf("python","ffmpeg","imagemagick","sox","nodejs","sqlite","git","jq","zip","unzip","poppler","tesseract","curl","wget","rsync","lame","flac","opus-tools",
        "gifsicle","graphicsmagick","pandoc","ghostscript","exiftool","libwebp","optipng","pngquant","jpegoptim","mediainfo","fluidsynth","timidity","espeak","zbar","qrencode",
        "gnuplot","bc","tree","ripgrep","fd","make","clang","cmake","rust","golang","openjdk-17","ruby","php","lua54","nano","vim","imagemagick","libvips","whisper-cpp","piper")
    /** Python packages a bot may install with install_package manager=pip. */
    val pipPackages=setOf("numpy","pandas","matplotlib","pillow","requests","beautifulsoup4","lxml","openpyxl","python-docx","python-pptx","reportlab","pypdf","moviepy","scipy",
        "mutagen","pydub","qrcode","opencv-python-headless","yt-dlp","xlsxwriter","pyyaml","tabulate","jinja2","markdown","fpdf2","pretty_midi","mido","music21","soundfile","librosa",
        "scikit-learn","networkx","sympy","seaborn","plotly","flask","fastapi","uvicorn","httpx","tqdm","rich","click","faker","pytesseract","pdfplumber","pymupdf","ffmpeg-python")

    /** A short folder name for a bot: "Customer Desk" becomes customer-desk. */
    fun slug(bot:String)=bot.lowercase().replace(Regex("[^a-z0-9]+"),"-").trim('-').take(32).ifBlank {"bot"}
    fun workDir(bot:String)="$HOME/work/"+slug(bot)

    private val firstWordsRefused=setOf("sudo","su","tsu","dd","mkfs","mke2fs","chown","chgrp","reboot","shutdown","halt","poweroff","am","pm","cmd","settings","svc","dumpsys","content","monkey",
        "service","pkill","killall","kill-all","mount","umount","swapon","iptables","insmod","setenforce","resetprop","magisk","pkg","apt","apt-get","dpkg","pacman","termux-change-repo","termux-setup-storage",
        "ssh","scp","sftp","nc","ncat","netcat","socat","telnet","nmap")
    private val installers=Regex("(^|[\\s;&|(])(pip3?|python3?\\s+-m\\s+pip|npm|npx|yarn|pnpm|cargo|gem|go\\s+install|gradle|conda|brew)\\s+(install|i|add|-g|global|get)\\b")
    private val pipeToShell=Regex("(curl|wget|fetch|http|https)\\b[^\\n]*\\|\\s*(sudo\\s+)?(sh|bash|zsh|dash|python3?|node|perl|ruby)\\b|(sh|bash|zsh|source|\\.)\\s+<\\(\\s*(curl|wget)|(sh|bash)\\s+-c\\s+[\"']?\\$\\(\\s*(curl|wget)",RegexOption.IGNORE_CASE)
    private val forkBomb=Regex(":\\s*\\(\\s*\\)\\s*\\{")
    private val privatePaths=Regex("(/data/data/(?!com\\.termux/)|/data/user|/data/misc|/data/system|/sdcard|/storage|/mnt/|/proc/|/sys/|~/storage|\\\$HOME/storage|/data/local|/apex)",RegexOption.IGNORE_CASE)
    private val hiddenHome=Regex("(~|\\\$HOME|\\\$\\{HOME\\})/\\.")
    private val parentEscape=Regex("(^|[\\s/='\"])\\.\\.(/|\\s|$|['\"])")

    /** Why [command] may not run for [bot], or null when it may. */
    fun refusal(command:String,bot:String):String? {
        val text=command.trim()
        if(text.isEmpty()) return "Give a command to run."
        if(text.length>20_000) return "That command is too long. Put the steps in a script file with file_write and run it."
        if(forkBomb.containsMatchIn(text)) return "That command would overload the phone."
        if(pipeToShell.containsMatchIn(text)) return "Downloading something and running it straight away isn't allowed. Download it to a file, look at it, then run it."
        if(installers.containsMatchIn(text)) return "Install programs with install_package, which only installs from a safe list."
        if(privatePaths.containsMatchIn(text)) return "That path is outside this bot's work folder. Use the files in your own folder; move files in and out with alpha_get and alpha_put."
        if(hiddenHome.containsMatchIn(text)) return "Hidden files in the home folder are off limits."
        if(parentEscape.containsMatchIn(text)) return "Stay inside your own work folder: paths with .. aren't allowed."
        for(segment in text.split(Regex("&&|\\|\\||;|\\||\\n|\\(|\\)|`|\\$\\("))) {
            val words=segment.trim().split(Regex("\\s+")).filter {it.isNotEmpty()}
            val first=words.firstOrNull {w->!w.contains('=') && w !in setOf("time","nohup","exec","env","command","nice","timeout","then","do","else","{")}?.substringAfterLast('/')?:continue
            if(first in firstWordsRefused || first.startsWith("mkfs") || first.startsWith("mke2fs")) return "The command \"$first\" isn't allowed here."
            if(first.startsWith("termux-")) return "Phone features (texts, calls, camera, location, contacts) aren't available to the command line. Use Alpha's own tools for those."
            if(first=="rm" || first=="rmdir" || first=="mv" || first=="cp" || first=="ln" || first=="chmod") {
                val args=words.drop(1).filter {!it.startsWith("-")}
                val own=workDir(bot)
                if(args.any {a->(a.startsWith("/") && !a.startsWith(own+"/") && a!=own) || a.startsWith("~") || a.startsWith("\$HOME")}) return "$first only works on files inside this bot's own work folder."
                if(first=="rm" && words.any {it=="-rf" || it=="-fr" || it=="-r"} && args.any {it=="*" || it=="." || it=="./"} ) return "That would empty the whole work folder. Name the files to remove."
            }
        }
        return null
    }

    /** Programs from the safe list, as a Termux pkg or pip command; or the reason one isn't allowed. */
    fun installCommand(manager:String,names:List<String>):Pair<String?,String?> {
        val list=names.map {it.trim().lowercase()}.filter {it.isNotEmpty()}.distinct()
        if(list.isEmpty()) return null to "Say which program to install."
        if(list.size>8) return null to "Install at most 8 at a time."
        return when(manager.lowercase()) {
            "pkg","termux","apt"-> {
                val bad=list.filter {it !in packages}
                if(bad.isNotEmpty()) null to bad.joinToString()+" isn't on the safe list. Allowed: "+packages.sorted().joinToString()
                else "yes | pkg install -y "+list.joinToString(" ") to null
            }
            "pip","python"-> {
                val bad=list.filter {it !in pipPackages}
                if(bad.isNotEmpty()) null to bad.joinToString()+" isn't on the safe list. Allowed: "+pipPackages.sorted().joinToString()
                else "pip install --no-input --disable-pip-version-check "+list.joinToString(" ") to null
            }
            else->null to "manager must be pkg or pip."
        }
    }

    /** The commands handed to bash: a prelude (folder, time limit, file helpers) around the bot's own command. Returns the script. */
    fun script(command:String,bot:String,seconds:Int,port:Int,token:String,marker:String):String {
        require(!command.contains(marker)) {"That command can't be run."}
        val secs=seconds.coerceIn(5,MAX_SECONDS)
        val dir=workDir(bot)
        return buildString {
            append("export HOME='$HOME' PREFIX='$USR' PATH='$USR/bin' TMPDIR='$USR/tmp'\n")
            append("export ALPHA_PORT='$port' ALPHA_TOKEN='$token'\n")
            append("mkdir -p '$dir' && cd '$dir' || exit 97\n")
            // Files move between Alpha and this folder through a private link, not through shared storage.
            append("alpha_get(){ curl -sf -H \"Authorization: Bearer \$ALPHA_TOKEN\" \"http://127.0.0.1:\$ALPHA_PORT/get/\$1\" -o \"\$1\"; }\n")
            append("alpha_put(){ curl -sf -T \"\$1\" -H \"Authorization: Bearer \$ALPHA_TOKEN\" \"http://127.0.0.1:\$ALPHA_PORT/put/\$(basename \"\$1\")\" >/dev/null; }\n")
            append("alpha_ls(){ curl -sf -H \"Authorization: Bearer \$ALPHA_TOKEN\" \"http://127.0.0.1:\$ALPHA_PORT/list\"; }\n")
            append("export -f alpha_get alpha_put alpha_ls 2>/dev/null\n")
            append("cat > .alpha-job.sh <<'$marker'\n").append(command.trimEnd('\n')).append("\n$marker\n")
            append("timeout -k 5 $secs bash .alpha-job.sh\n")
            append("code=\$?\nrm -f .alpha-job.sh\nexit \$code\n")
        }
    }

    /** A random marker for the script's here-document. */
    fun marker():String="ALPHA_END_"+randomUrl(14).filter {it.isLetterOrDigit()}.uppercase()

    /** The reply for a bot: output trimmed to what fits, with the exit code and a plain hint when it timed out. */
    fun report(exit:Int?,stdout:String,stderr:String,seconds:Int,error:String=""):String {
        val out=StringBuilder()
        if(error.isNotBlank()) out.append("The command line returned an error: ").append(error.take(300)).append('\n')
        if(exit==124 || exit==137) out.append("It ran out of time (").append(seconds).append(" seconds) and was stopped. Run longer jobs in smaller steps or raise timeout_seconds.\n")
        return out.toString()
    }
}

package venus

/* ktlint-disable no-wildcard-imports */

import org.w3c.dom.*
import org.w3c.dom.url.URL
import venus.api.venuspackage
import venus.terminal.Terminal
import venus.terminal.cmds.vdb
import venus.vfs.*
import venusbackend.assembler.*
import venusbackend.linker.LinkedProgram
import venusbackend.linker.Linker
import venusbackend.linker.ProgramAndLibraries
import venusbackend.plus
import venusbackend.riscv.*
import venusbackend.riscv.insts.dsl.types.Instruction
import venusbackend.riscv.insts.floating.Decimal
import venusbackend.simulator.*
import venusbackend.simulator.plugins.*
import venusbackend.simulator.Tracer.Companion.wordAddressed
import venusbackend.simulator.cache.BlockReplacementPolicy
import venusbackend.simulator.cache.CacheError
import venusbackend.simulator.cache.CacheHandler
import venusbackend.simulator.cache.PlacementPolicy
import venusbackend.toHex
import kotlin.browser.document
import kotlin.browser.window
import kotlin.dom.addClass
import kotlin.dom.hasClass
import kotlin.dom.removeClass

/* ktlint-enable no-wildcard-imports */

/**
 * The "driver" singleton which can be called from Javascript for all functionality.
 */
@JsName("Driver") object Driver {
    @JsName("VFS") var VFS = VirtualFileSystem("/")
    var active_abs_file_name: String? = null
    var active_abs_file_path: String? = null

    fun set_active_afpath(path: String?) {
        active_abs_file_path = path
        document.getElementById("activeFileInEditor")!!.innerHTML = path.toString()
        activeFileinEditor = path ?: ""
    }

    val mainCache: CacheHandler = CacheHandler(1)
    var cache: CacheHandler = mainCache
    var cacheLevels: ArrayList<CacheHandler> = arrayListOf(mainCache)
    val simSettings = SimulatorSettings()
    var sim: Simulator = Simulator(LinkedProgram(), VFS, settings = simSettings)

    val watchpoints = HashSet<WatchPoint>()

    var tr: Tracer = Tracer(sim)

    private var timer: Int? = null
    val LS = LocalStorage()
    var useLS = false
    private var saveInterval: Int? = null
    var p = ""
    private var ready = false
    @JsName("FReginputAsFloat") var FReginputAsFloat = true
    @JsName("ScriptManager") var ScriptManager = venus.api.ScriptManager
    @JsName("debug") var debug = false

    @JsName("terminal") var terminal = Terminal(VFS)

    @JsName("activeFileinEditor") var activeFileinEditor: String = ""

    @JsName("driver_complete_loading") var driver_complete_loading: Boolean = false

    var doCallingConventionCheck: Boolean = false

    init {
        /* This code right here is so that you can add custom kotlin code even after venus has been loaded! */
        js("window.eval_in_venus_env = function (s) {return eval(s);}")
        js("load_update_message(\"Initializing Venus: Init\");")
        console.log("Loading driver...")
        mainCache.attach(false)

        useLS = LS.get("venus") == "true"
        Renderer.renderButton(document.getElementById("sv") as HTMLButtonElement, useLS)

        window.setTimeout(Driver::initTimeout, 5)

        console.log("Finished loading driver!")
    }

    fun initTimeout() {
        js("load_update_message(\"Initializing Venus: Local Storage\");")
        loadAll(useLS)
        js("load_update_message(\"Initializing Venus: Renderer\");")
        Renderer.loadSimulator(sim)
        Renderer.renderAssembleButtons()
        saveInterval = window.setInterval(Driver::saveIntervalFn, 10000)
        Driver.ready = true
        initFinish()
    }

    fun initFinish() {
        if (Driver.ready) {
            if (!::fileExplorerCurrentLocation.isInitialized) {
                fileExplorerCurrentLocation = VFS.sentinel
                openVFObjectfromObj(VFS.sentinel)
            }
            js("""window.onbeforeunload = function(){
    driver.saveAll();
}""")
            js("window.driver_load_done();")
        } else {
            window.setInterval(Driver::initFinish, 100)
        }
    }

    @JsName("lint") fun lint(text: String): Array<LintError> = Linter.lint(text)

    /**
     * Run when the user clicks the "Simulator" tab.
     *
     * Assembles the text in the editor, and then renders the simulator.
     */
    @JsName("openSimulator") fun openSimulator() {
        openGenericMainTab("simulator")
    }

    @JsName("noAssemble") fun noAssemble() {
        Renderer.renderSimButtons()
    }

    fun getDefaultArgs(): String {
        return (document.getElementById("ArgsList") as HTMLInputElement).value
    }

    @JsName("registerECallReceiver")
    fun registerECallReceiver(receiverFunction: (String) -> String) {
        sim.registerECallReceiver(receiverFunction)
    }

    @JsName("assembleSimulator") fun assembleSimulator() {
        var text = getText()
        if (text == "") {
            js("codeMirror.refresh();codeMirror.save();")
            text = getText()
        }
        if (ready) {
            try {
                val editorFileName = active_abs_file_name ?: "editor.S"
                val fpath = active_abs_file_path ?: VFS.currentLocation.getPath() + "/$editorFileName"
                var remove = false
                if (VFS.getObjectFromPath(fpath) == null) {
                    VFS.addFile(fpath, text)
                    remove = true
                }
                val success = assemble(text, name = editorFileName, absPath = fpath)
                if (success != null) {
                    if (link(listOf(success))) {
                        val args = Lexer.lex(getDefaultArgs())
                        for (arg in args) {
                            sim.addArg(arg)
                        }
                        Renderer.loadSimulator(sim)
                        setCacheSettings()
                        Renderer.updateCache(Address(0, MemSize.WORD))
                    }
                }
                if (remove) {
                    VFS.remove(fpath)
                }
            } catch (e: Throwable) {
                Renderer.loadSimulator(Simulator(LinkedProgram(), VFS))
                handleError("Open Simulator", e)
            }
        } else {
            window.setTimeout(Driver::openSimulator, 100)
        }
    }

    @JsName("checkURLParams") fun checkURLParams() {
        var clearparams = true
        val currentURL = URL(window.location.href)

        var s = currentURL.searchParams.get("code")
        if (s != null) {
            s = parseString(s)
            js("codeMirror.save();")
            if (getText() != "") {
                if (getText() != s) {
                    val override = currentURL.searchParams.get("override")
                    val overrideb = override != null && override.toLowerCase() == "true"
                    val choice = if (overrideb) { true } else { window.confirm("You have some saved code already in venus! Do you want to override it with the code in your url?") }
                    if (choice) {
                        js("codeMirror.setValue(s);")
                    } else {
                        clearparams = false
                    }
                }
            } else {
                js("codeMirror.setValue(s);")
            }
        }

        s = currentURL.searchParams.get("tab")
        if (s != null) {
            s = parseString(s.toString())
            if (s in Renderer.mainTabs) {
                Renderer.renderTab(s, Renderer.mainTabs)
            } else {
                console.log("Unknown Tag!")
            }
        }

        s = currentURL.searchParams.get("target")
        if (s != null) {
            s = parseString(s)
            js("loadfromtarget(s);")
        }

        s = currentURL.searchParams.get("save")
        if (jsTypeOf(s) != undefined) {
            s = parseString(s.toString())
            if (s.toLowerCase() == "true") {
                persistentStorage(true)
                Renderer.renderButton(document.getElementById("sv") as HTMLButtonElement, true)
            }

            if (s.toLowerCase() == "false") {
                persistentStorage(false)
                Renderer.renderButton(document.getElementById("sv") as HTMLButtonElement, false)
            }
        }

        try {
            s = currentURL.searchParams.get("packages")
            if (s != null) {
                s = parseString(s.toString())
                var l = s.split(Regex(","))
                for (pack in l) {
                    ScriptManager.addPackage(pack)
                }
            }
        } catch (e: Exception) {
            console.warn("An error occurred when parsing the packages!")
            console.warn(e)
        }

        if (clearparams) {
            clearURLParams()
        }
    }

    fun clearURLParams() {
        val location = window.location.origin + window.location.pathname
        js("window.history.replaceState({}, document.title, location)")
    }

    fun parseString(s: String): String {
        val ps = s.replace("\\n", "\n")
                .replace("\\t", "\t")
        return ps
    }

    fun unparseString(s: String): String {
        val ps = s.replace("\n", "\\n")
                .replace("\t", "\\t")
        return ps
    }

    /**
     * Opens and renders the editor.
     */
    @JsName("openEditor") fun openEditor() {
        runEnd()
        openGenericMainTab("editor")
        js("codeMirror.refresh();")
    }

    @JsName("openVenus") fun openVenus() {
        openGenericMainTab("venus")
    }

    @JsName("openGenericMainTab") fun openGenericMainTab(name: String) {
        Renderer.renderTab(name, Renderer.mainTabs)
        if (name == "editor") {
            Renderer.renderAssembleButtons()
        }
        LS.set("defaultTab", name)
    }

    @JsName("openURLMaker") fun openURLMaker() {
        js("setUpURL();")
        Renderer.renderURLMaker()
    }

    /**
     * Gets the text from the textarea editor.
     */
    @JsName("getText") internal fun getText(): String {
        val editor = document.getElementById("asm-editor") as HTMLTextAreaElement
        return editor.value
    }

    /**
     * Assembles and links the program, sets the simulator
     *
     * @param text the assembly code.
     */
    internal fun assemble(text: String, name: String = "", absPath: String): Program? {
        val (prog, errors, warnings) = if (name != "") {
            Assembler.assemble(text, name, abspath = absPath)
        } else {
            Assembler.assemble(text, abspath = absPath)
        }
        if (errors.isNotEmpty()) {
            Renderer.displayAssemblerError(errors.first())
            return null
        }
        return prog
    }

    internal fun link(progs: List<Program>): Boolean {
        try {
            val PandL = ProgramAndLibraries(progs, VFS)
            val linked = Linker.link(PandL)
            loadSim(linked)
            return true
        } catch (e: AssemblerError) {
            Renderer.displayAssemblerError(e)
            return false
        }
    }

    fun loadSim(linked: LinkedProgram) {
        sim = Simulator(linked, VFS, simSettings)
        mainCache.reset()
        sim.state.cache = mainCache
        tr = Tracer(sim)
        if (doCallingConventionCheck) {
            this.enableCallingConvention(true)
        }
        for (wp in this.watchpoints) {
            sim.addWatchpoint(wp)
        }
    }

    fun getMaxSteps(): Int {
        return (document.getElementById("tmaxsteps-val") as HTMLInputElement).value.toInt()
    }

    @JsName("updateMaxSteps") fun updateMaxSteps() {
        runEnd()
        simSettings.maxSteps = getMaxSteps()
    }

    fun exitcodecheck() {
        if (sim.exitcode != null) {
            val msg = "Exited with error code ${sim.exitcode}"
            if (sim.exitcode ?: 0 == 0) {
                js("window.alertify.message(msg)")
            } else {
                js("window.alertify.error(msg)")
            }
        }
    }

    @JsName("externalAssemble") fun externalAssemble(text: String, absPath: String = ""): Any {
        var success = true
        var errs = ""
        var sim = js("undefined;")
        val (prog, errors, warnings) = Assembler.assemble(text, abspath = absPath)
        if (errors.isNotEmpty()) {
            errs = errors.first().toString()
            success = false
        } else {
            try {
                val PandL = ProgramAndLibraries(listOf(prog), VFS)
                val linked = Linker.link(PandL)
                sim = Simulator(linked, VFS, simSettings)
            } catch (e: AssemblerError) {
                errs = e.toString()
                success = false
            }
        }

        return js("[success, sim, errs, warnings]")
    }

    /**
     * Runs the simulator until it is done, or until the run button is pressed again.
     */
    @JsName("run") fun run() {
        if (currentlyRunning()) {
            runEnd()
        } else {
            try {
                Renderer.setRunButtonSpinning(true)
                timer = window.setTimeout(Driver::runStart, TIMEOUT_TIME, true)
                sim.step() // walk past breakpoint
            } catch (e: Throwable) {
                runEnd()
                handleError("RunStart", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
            }
        }
    }

    /**
     * Resets the simulator to its initial state
     */
    @JsName("reset") fun reset() {
        try {
            val args = sim.args
            val plugins = sim.plugins
            sim = Simulator(sim.linkedProgram, VFS, sim.settings, simulatorID = sim.simulatorID)
            tr.sim = sim
            for (arg in args) {
                sim.addArg(arg)
            }
            for (wp in this.watchpoints) {
                sim.addWatchpoint(wp)
            }
            for ((id, p) in plugins) {
                sim.registerPlugin(id, p)
            }
            mainCache.reset()
            sim.state.setCacheHandler(mainCache)
            Renderer.loadSimulator(sim)
            setCacheSettings()
            Renderer.updateCache(Address(0, MemSize.WORD))
        } catch (e: Throwable) {
            Renderer.loadSimulator(Simulator(LinkedProgram(), VFS))
            handleError("Reset Simulator", e)
        }
    }

    @JsName("toggleBreakpoint") fun addBreakpoint(idx: Int) {
        val isBreakpoint = sim.toggleBreakpointAt(idx)
        Renderer.renderBreakpointAt(idx, isBreakpoint)
    }

    @JsName("addWatchpoint") fun addWatchpoint(elm: HTMLDivElement) {
        val inputs = elm.getElementsByTagName("input")
        var onReadElm = inputs[0] as HTMLInputElement
        var onWriteElm = inputs[1] as HTMLInputElement
        var address = inputs[2] as HTMLInputElement
        var value = inputs[3] as HTMLInputElement
        var mask = inputs[4] as HTMLInputElement
        var hidden = inputs[5] as HTMLInputElement

        if (!onReadElm.checked && !onWriteElm.checked) {
            js("alertify.alert('On read and/or On write must be selected!');")
            return
        }

        if (address.value == "" && value.value == "") {
            js("alertify.alert('You must set an address and or a value.');")
            return
        }

        val newwpnode = elm.cloneNode(true) as HTMLDivElement
        val btn = newwpnode.getElementsByTagName("button")[0] as HTMLButtonElement
        btn.style.backgroundColor = "red"
        btn.onclick = { it ->
            js("driver.removeWatchpoint(this.parentNode.parentNode)")
        }
        btn.innerText = "Remove"

        val newinputs = newwpnode.getElementsByTagName("input")
        (newinputs[0] as HTMLInputElement).onclick = { it ->
            js("driver.editWatchpoint('onRead', this.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement)")
        }
        (newinputs[1] as HTMLInputElement).onclick = { it ->
            js("driver.editWatchpoint('onWrite', this.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement)")
        }
        (newinputs[2] as HTMLInputElement).onblur = { it ->
            js("driver.editWatchpoint('value', this.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement)")
        }
        (newinputs[3] as HTMLInputElement).onblur = { it ->
            js("driver.editWatchpoint('address', this.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement)")
        }
        (newinputs[4] as HTMLInputElement).onblur = { it ->
            js("driver.editWatchpoint('mask', this.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement.parentElement)")
        }

        if (internalAddWatchpoint("new", newwpnode)) {
            val wpdiv = document.getElementById("watchpoints-tab-view") as HTMLDivElement
            wpdiv.appendChild(newwpnode)

            onReadElm.checked = false
            onWriteElm.checked = false
            address.value = ""
            value.value = ""
            mask.value = ""
            hidden.value = ""
        }
    }

    fun internalAddWatchpoint(which: String, elm: HTMLDivElement): Boolean {
        val inputs = elm.getElementsByTagName("input")
        if (inputs.length != 6) {
            console.log(elm)
            console.log(inputs)
            js("alertify.alert('Unable to edit this watchpoint: This watchpoint is malformed!')")
            return false
        }
        var onReadElm = inputs[0] as HTMLInputElement
        var onWriteElm = inputs[1] as HTMLInputElement
        var address = inputs[2] as HTMLInputElement
        var value = inputs[3] as HTMLInputElement
        var mask = inputs[4] as HTMLInputElement
        var hidden = inputs[5] as HTMLInputElement
        var hiddenvalue = hidden.value

        var oldwp = try {
            if (hiddenvalue != "") {
                WatchPoint.parse(hiddenvalue)
            } else {
                WatchPoint(true, false, 0, 0, 0)
            }
        } catch (e: SimulatorError) {
            console.error(e)
            js("alertify.alert('Could not parse what this watchpoint was! Please remove this watchpoint then re-add the one you want.')")
            return false
        }
        var addressint: Number?
        var valueint: Number?
        var maskint: Number?
        try {
            addressint = if (address.value.toLowerCase() in listOf("none", "null", "na", "nil", "nul", "")) {
                null
            } else {
                userStringToLong(address.value)
            }
            valueint = if (value.value.toLowerCase() in listOf("none", "null", "na", "nil", "nul", "")) {
                null
            } else {
                userStringToLong(value.value)
            }
            maskint = if (mask.value.toLowerCase() in listOf("none", "null", "na", "nil", "nul", "")) {
                null
            } else {
                userStringToLong(mask.value)
            }
        } catch (e: NumberFormatException) {
            console.error(e)
            onReadElm.checked = oldwp.onRead
            onWriteElm.checked = oldwp.onWrite
            address.value = oldwp.address?.let { it -> toHex(it) } ?: "None"
            value.value = oldwp.value?.let { it -> toHex(it) } ?: "None"
            mask.value = oldwp.mask?.let { it -> toHex(it) } ?: "None"
            js("alertify.alert('Could not parse your watchpoint ' + which + '. ' + e);")
            return false
        }
        val newwp = try {
            WatchPoint(onReadElm.checked, onWriteElm.checked, addressint, valueint, maskint)
        } catch (e: SimulatorError) {
            console.error(e)
            onReadElm.checked = oldwp.onRead
            onWriteElm.checked = oldwp.onWrite
            address.value = oldwp.address?.let { it -> toHex(it) } ?: "None"
            value.value = oldwp.value?.let { it -> toHex(it) } ?: "None"
            mask.value = oldwp.mask?.let { it -> toHex(it) } ?: "None"
            js("alertify.alert('Could not parse ' + which + '. ' + e);")
            return false
        }
        if (this.watchpoints.contains(newwp)) {
            onReadElm.checked = oldwp.onRead
            onWriteElm.checked = oldwp.onWrite
            address.value = oldwp.address?.let { it -> toHex(it) } ?: "None"
            value.value = oldwp.value?.let { it -> toHex(it) } ?: "None"
            mask.value = oldwp.mask?.let { it -> toHex(it) } ?: "None"
            js("alertify.alert('This watchpoint already exists!');")
            return false
        }
        this.sim.addWatchpoint(newwp)
        this.watchpoints.add(newwp)
        onReadElm.checked = newwp.onRead
        onWriteElm.checked = newwp.onWrite
        address.value = newwp.address?.let { it -> toHex(it) } ?: "None"
        value.value = newwp.value?.let { it -> toHex(it) } ?: "None"
        mask.value = newwp.mask?.let { it -> toHex(it) } ?: "None"
        hidden.value = newwp.stringify()
        return true
    }

    @JsName("editWatchpoint") fun editWatchpoint(which: String, elm: HTMLDivElement) {
        val inputs = elm.getElementsByTagName("input")
        var hidden = inputs[5] as HTMLInputElement
        var hiddenvalue = hidden.value
        var oldwp = try {
            WatchPoint.parse(hiddenvalue)
        } catch (e: SimulatorError) {
            console.error(e)
            js("alertify.alert('Unable to edit this watchpoint: This watchpoint is malformed! This watchpoint was changed but not removed!')")
            return
        }
        if (!internalAddWatchpoint(which, elm)) {
            return
        }
        this.watchpoints.remove(oldwp)
        sim.removeWatchpoint(oldwp)
    }

    @JsName("removeWatchpoint") fun removeWatchpoint(elm: HTMLDivElement) {
        val inputs = elm.getElementsByTagName("input")
        if (inputs.length != 6) {
            console.log(elm)
            console.log(inputs)
            if (!window.confirm("The watchpoint you selected seems to be malformed! Do you still want to attempt to remove the watchpoint?")) {
                return
            }
        } else {
            var hidden = inputs[inputs.length - 1] as HTMLInputElement
            var hiddenvalue = hidden.value
            try {
                var oldwp = WatchPoint.parse(hiddenvalue)
                this.watchpoints.remove(oldwp)
                sim.removeWatchpoint(oldwp)
            } catch (e: SimulatorError) {
                console.error(e)
                if (!window.confirm("The watchpoint you selected seems to be malformed! Do you still want to attempt to remove the watchpoint?")) {
                    return
                }
            }
        }
        elm.parentElement?.removeChild(elm)
    }

    @JsName("addRawWatchpoint") fun addRawWatchpoint(
        onRead: Boolean = false,
        onWrite: Boolean = false,
        address: Number? = null,
        value: Number? = null,
        mask: Number? = null
    ) {
        try {
            val wp = WatchPoint(onRead, onWrite, address, value, mask)
            sim.addWatchpoint(wp)
        } catch (e: SimulatorError) {
            console.error(e)
        }
    }

    @JsName("removeRawWatchpoint") fun removeRawWatchpoint(
        onRead: Boolean = false,
        onWrite: Boolean = false,
        address: Number? = null,
        value: Number? = null,
        mask: Number? = null
    ) {
        try {
            val wp = WatchPoint(onRead, onWrite, address, value, mask)
            this.watchpoints.remove(wp)
            sim.removeWatchpoint(wp)
        } catch (e: SimulatorError) {
            console.error(e)
        }
    }

    internal const val TIMEOUT_CYCLES = 100
    internal const val TIMEOUT_TIME = 10
    internal fun runStart(useBreakPoints: Boolean) {
        try {
            var cycles = 0
            while (cycles < TIMEOUT_CYCLES) {
                if (sim.isDone() || (sim.atBreakpoint() && useBreakPoints)) {
                    exitcodecheck()
                    runEnd()
                    Renderer.updateAll()
                    return
                }

                handleNotExitOver()
                sim.step()
                Renderer.updateCache(Address(0, MemSize.WORD))
                cycles++
            }

            timer = window.setTimeout(Driver::runStart, TIMEOUT_TIME, useBreakPoints)
        } catch (e: Throwable) {
            runEnd()
            handleError("RunStart", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
        }
    }

    @JsName("runEnd") fun runEnd() {
        handleNotExitOver()
        sim.finishPlugins()
        Renderer.updatePC(sim.getPC())
        Renderer.updateAll()
        Renderer.setRunButtonSpinning(false)
        timer?.let(window::clearTimeout)
        timer = null
    }

    /**
     * Runs the simulator for one step and renders any updates.
     */
    @JsName("step") fun step() {
        try {
            val diffs = sim.step()
            handleNotExitOver()
            Renderer.updateFromDiffs(diffs)
            Renderer.updateCache(Address(0, MemSize.WORD))
            Renderer.updateControlButtons()
            exitcodecheck()
        } catch (e: Throwable) {
            handleError("step", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
        }
    }

    private fun handleNotExitOver() {
        if (sim.settings.ecallOnlyExit &&
                (sim.getPC().toInt() >= sim.getMaxPC().toInt() || sim.getPC().toInt() < MemorySegments.TEXT_BEGIN)
        ) {
//            val pcloc = (sim.getMaxPC().toInt() - MemorySegments.TEXT_BEGIN)
            val pcloc = sim.getPC().toInt()
            sim.incMaxPC(4)
            var mcode = MachineCode(0)
            var progLine = ""
            try {
                mcode = sim.getNextInstruction()
                Renderer.addToProgramListing(pcloc, mcode, Instruction[mcode].disasm(mcode))
            } catch (e: SimulatorError) {
                val short0 = sim.loadHalfWord(sim.getPC())
                val short1 = sim.loadHalfWord(sim.getPC() + 2)
                Renderer.addToProgramListing(pcloc, MachineCode((short1 shl 16) or short0), "Invalid Instruction", true)
            }
        }
    }

    /**
     * Undo the last executed instruction and render any updates.
     */
    @JsName("undo") fun undo() {
        try {
            val diffs = sim.undo()
            Renderer.updateFromDiffs(diffs)
            Renderer.updateControlButtons()
        } catch (e: Throwable) {
            handleError("undo", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
        }
    }

    /**
     * Change to register tab.
     */
    @JsName("openSimulatorTab") fun openSimulatorTab(tabid: String) {
        Renderer.renderTab(tabid, Renderer.simulatorTabs)
    }

    @JsName("openSimulatorRegisterTab") fun openSimulatorReigsterTab(tabid: String) {
        Renderer.renderTab(tabid, Renderer.simulatorRegisterTabs)
    }

    @JsName("openSettingsTab") fun openSettingsTab(tabid: String) {
        Renderer.renderTab(tabid, Renderer.venusMainSettingsTabs)
    }

    @JsName("openVenusSubSettingsTab") fun openVenusSubSettingsTab(tabid: String) {
        Renderer.renderTab(tabid, Renderer.venusSubSettingsTabs)
    }

    @JsName("openSimulatorVDBTab") fun openSimulatorVDBTab(tabid: String) {
        Renderer.renderTab(tabid, Renderer.venusSimulatorVDBTabs)
    }

    @JsName("currentlyRunning") fun currentlyRunning(): Boolean = timer != null

    @JsName("destructiveGetSimOut") fun destrictiveGetSimOut(): String {
        val tmp = sim.stdout
        sim.stdout = ""
        return tmp
    }

    @JsName("openVenusTab") fun openVenusTab(tabid: String) {
        val tabs = listOf("venus-terminal", "venus-files", "venus-url", "venus-jvm")
        Renderer.renderTab(tabid, tabs)
        if (tabid == "venus-files") {
            refreshVFS()
        }
    }

    @JsName("enableCallingConvention") fun enableCallingConvention(enabled: Boolean) {
        doCallingConventionCheck = enabled
        if (doCallingConventionCheck) {
            sim.registerPlugin("CC", CallingConventionCheck())
        } else {
            sim.removePlugin("CC")
        }
    }

    /**
     * Save a register's value
     */
    @JsName("saveRegister") fun saveRegister(reg: HTMLInputElement, id: Int) {
        if (!currentlyRunning()) {
            try {
                val input = reg.value
                sim.setRegNoUndo(id, userStringToInt(input))
            } catch (e: NumberFormatException) {
                /* do nothing */
            }
        }
        Renderer.updateRegister(id, sim.getReg(id))
    }

    @JsName("saveFRegister") fun saveFRegister(freg: HTMLInputElement, id: Int) {
        if (!currentlyRunning()) {
            try {
                val input = freg.value
                val d = Decimal(f = userStringToFloat(input), d = userStringToDouble(input), isF = FReginputAsFloat)
                sim.setFRegNoUndo(id, d)
            } catch (e: NumberFormatException) {
                /* do nothing */
            }
        }
        Renderer.updateFRegister(id, sim.getFReg(id))
    }

    @JsName("updateRegMemDisplay") fun updateRegMemDisplay() {
        Renderer.updateRegMemDisplay()
    }

    @JsName("moveMemoryJump") fun moveMemoryJump() = Renderer.moveMemoryJump()

    @JsName("moveMemoryUp") fun moveMemoryUp() = Renderer.moveMemoryUp()

    @JsName("moveMemoryDown") fun moveMemoryDown() = Renderer.moveMemoryDown()

    @JsName("moveMemoryLocation") fun moveMemoryLocation(address: String) {
        try {
            val addr = userStringToInt(address)
            Renderer.updateMemory(addr)
        } catch (e: Throwable) {
            handleError("MoveMemLoc", e, true)
        }
    }

    @JsName("updateMemoryLocation") fun updateMemoryLocation(e: HTMLSelectElement, byteOffset: Int) {
        try {
            val tr = e.parentElement!!.parentElement!!
            val addressNode = tr.children[0]!!
            val addrvalue = addressNode.innerHTML
            if (addrvalue.contains("-")) {
                return
            }
            val address = userStringToLong(addrvalue) + byteOffset
            val value = userStringToInt(e.value) and 0xFF
            sim.storeByte(address, value)
            e.value = Renderer.displayByteAsString(value)
            js("e.onkeydown();")
        } catch (e: Throwable) {
            handleError("updateMemoryLocation", e, true)
        }
    }

    fun getInstructionDump(): String {
        val sb = StringBuilder()
        for (i in 0 until sim.linkedProgram.prog.insts.size) {
            val mcode = sim.linkedProgram.prog.insts[i]
            val hexRepresentation = Renderer.toHex(mcode[InstructionField.ENTIRE].toInt())
            sb.append(hexRepresentation/*.removePrefix("0x")*/)
            sb.append("\n")
        }
        return sb.toString()
    }

    @JsName("dump") fun dump() {
        try {
            Renderer.clearConsole()
            Renderer.printConsole(getInstructionDump())
            val ta = document.getElementById("console-output") as HTMLTextAreaElement
            ta.select()
            val success = document.execCommand("copy")
            if (success) {
                // window.alert("Successfully copied machine code to clipboard")
                console.log("Successfully copied machine code to clipboard")
            }
        } catch (e: Throwable) {
            handleError("dump", e)
        }
    }

    @JsName("setOnlyEcallExit") fun setOnlyEcallExit(b: Boolean) {
        simSettings.ecallOnlyExit = b
    }

    @JsName("setAllowAccessBtnStackHeap") fun setAllowAccessBtnStackHeap(b: Boolean) {
        simSettings.allowAccessBtnStackHeap = b
    }

    @JsName("setSetRegsOnInit") fun setSetRegsOnInit(b: Boolean) {
        simSettings.setRegesOnInit = b
    }

    @JsName("verifyText") fun verifyText(input: HTMLInputElement) {
        try {
            if (!currentlyRunning()) {
                try {
                    var i = userStringToInt(input.value)
                    try {
                        MemorySegments.setTextBegin(i)
                        val tabDisplay = document.getElementById("simulator-tab") as HTMLElement
                        if (tabDisplay.classList.contains("is-active")) {
                            openSimulator()
                        }
                    } catch (e: SimulatorError) {
                        console.warn(e.toString())
                    }
                } catch (e: NumberFormatException) {
                    /* do nothing */
                    console.warn("Unknown number format!")
                }
            } else {
                console.warn("Could not change text because the program is currently running!")
            }
            val ts = Renderer.intToString(MemorySegments.TEXT_BEGIN)
            input.value = ts
        } catch (e: Throwable) {
            handleError("Verify Text", e)
        }
    }

    @JsName("setMaxHistory") fun setMaxHistory(input: HTMLInputElement) {
        try {
            var i = userStringToInt(input.value)
            try {
                sim.setHistoryLimit(i)
            } catch (e: SimulatorError) {
                console.warn(e.toString())
            }
        } catch (e: NumberFormatException) {
            /* do nothing */
            console.warn("Unknown number format!")
        }
    }

    @JsName("setNumberOfCacheLevels") fun setNumberOfCacheLevels(i: Int) {
        if (i < 1) {
            (document.getElementById("setNumCacheLvls") as HTMLInputElement).value = cacheLevels.size.toString()
            handleError("Set Number of Cache Levels (LT0)", CacheError("You must set the number of caches to at least 1! If you do not want to use any cache, set this to 1 and then disable the cache."), true)
            return
        }
        (document.getElementById("setNumCacheLvls") as HTMLInputElement).value = i.toString()
        if (i == cacheLevels.size) {
            return
        }
        if (cacheLevels.size < i) {
            val lastCache = cacheLevels[cacheLevels.size - 1]
            while (cacheLevels.size < i) {
                val newCache = CacheHandler(cacheLevels.size + 1)
                cacheLevels[cacheLevels.size - 1].nextLevelCacheHandler = newCache
                cacheLevels.add(newCache)
                Renderer.renderAddCacheLevel()
            }
            lastCache.update()
        } else if (cacheLevels.size > i) {
            while (cacheLevels.size > i) {
                val prevCacheIndex = cacheLevels.size - 1
                val prevCache = cacheLevels[prevCacheIndex]
                cacheLevels.removeAt(prevCacheIndex)
                val lastCache = cacheLevels[cacheLevels.size - 1]
                lastCache.nextLevelCacheHandler = null
                if (cache.cacheLevel == prevCache.cacheLevel) {
                    cache = lastCache
                    Renderer.renderSetCacheLevel(cache.cacheLevel)
                }
                Renderer.renderRemoveCacheLevel()
            }
            setCacheSettings()
        }
    }

    @JsName("setCacheEnabled") fun setCacheEnabled(enabled: Boolean) {
        cache.attach(enabled)
        Renderer.updateCache(Address(0, MemSize.WORD))
    }

    @JsName("updateCacheLevel") fun updateCacheLevel(e: HTMLSelectElement) {
        try {
            val level = e.value.removePrefix("L").toInt()
            updateCacheLvl(level)
        } catch (e: NumberFormatException) {
            handleError("Update Cache Level (NFE)", e, true)
        }
    }

    fun updateCacheLvl(level: Int) {
        if (level in 1..cacheLevels.size) {
            cache = cacheLevels[level - 1]
            Renderer.renderSetCacheLevel(level)
            setCacheSettings()
        } else {
            handleError("Update Cache Level (LVL)", CacheError("Cache level '" + level + "' does not exist in your current cache!"), true)
        }
    }

    @JsName("updateCacheBlockSize") fun updateCacheBlockSize(e: HTMLInputElement) {
        val v = e.value.toInt()
        try {
            cache.setCacheBlockSize(v)
        } catch (er: CacheError) {
            Renderer.clearConsole()
            Renderer.printConsole(er.toString())
        }
        e.value = cache.cacheBlockSize().toString()
        setCacheSettings()
    }

    @JsName("updateCacheNumberOfBlocks") fun updateCacheNumberOfBlocks(e: HTMLInputElement) {
        val v = e.value.toInt()
        try {
            cache.setNumberOfBlocks(v)
        } catch (er: CacheError) {
            Renderer.clearConsole()
            Renderer.printConsole(er.toString())
        }
        e.value = cache.numberOfBlocks().toString()
        setCacheSettings()
    }

    @JsName("updateCacheAssociativity") fun updateCacheAssociativity(e: HTMLInputElement) {
        val v = e.value.toInt()
        try {
            cache.setAssociativity(v)
        } catch (er: CacheError) {
            Renderer.clearConsole()
            Renderer.printConsole(er.toString())
        }
        e.value = cache.associativity().toString()
        setCacheSettings()
    }

    @JsName("updateCachePlacementPolicy") fun updateCachePlacementPolicy(e: HTMLSelectElement) {
        if (e.value == "N-Way Set Associative") {
            cache.setPlacementPol(PlacementPolicy.NWAY_SET_ASSOCIATIVE)
        } else if (e.value == "Fully Associative") {
            cache.setPlacementPol(PlacementPolicy.FULLY_ASSOCIATIVE)
        } else {
            cache.setPlacementPol(PlacementPolicy.DIRECT_MAPPING)
            e.value = "Direct Mapped"
        }
        setCacheSettings()
    }

    @JsName("updateCacheReplacementPolicy") fun updateCacheReplacementPolicy(e: HTMLSelectElement) {
        if (e.value == "Random") {
            cache.setBlockRepPolicy(BlockReplacementPolicy.RANDOM)
        } else {
            cache.setBlockRepPolicy(BlockReplacementPolicy.LRU)
            e.value = "LRU"
        }
        setCacheSettings()
    }

    @JsName("setCacheSeed") fun setCacheSeed(v: String) {
        cache.setCurrentSeed(v)
        setCacheSettings()
    }

    fun setCacheSettings() {
        val bs = cache.cacheBlockSize().toString()
        val nb = cache.numberOfBlocks().toString()
        val av = cache.associativity().toString()
        val avenabled = cache.canSetAssociativity()
        val at = cache.placementPol().toMyString()
        val rp = cache.blockRepPolicy().toMyString()
        val cs = cache.cacheSize().toString()
        val cseed = cache.seed
        val attached = cache.attached
        (document.getElementById("block-size-val") as HTMLInputElement).value = bs
        (document.getElementById("numblocks-val") as HTMLInputElement).value = nb
        val ave = (document.getElementById("associativity-val") as HTMLInputElement)
        ave.value = av
        ave.disabled = !avenabled
        (document.getElementById("associativity-type") as HTMLSelectElement).value = at
        (document.getElementById("replacementPolicy") as HTMLSelectElement).value = rp
        (document.getElementById("cache-size-val") as HTMLInputElement).value = cs
        (document.getElementById("cache-seed") as HTMLInputElement).value = cseed
        val attachedButton = (document.getElementById("cacheEnabled") as HTMLButtonElement)
        attachedButton.value = attached.toString()
        if (attached) {
            attachedButton.addClass("is-primary")
        } else {
            attachedButton.removeClass("is-primary")
        }
        Renderer.makeCacheBlocks()
        Renderer.updateCache(Address(0, MemSize.WORD))
    }

    @JsName("setAlignedAddressing") fun setAlignedAddressing(b: Boolean) {
        simSettings.alignedAddress = b
    }

    @JsName("setMutableText") fun setMutableText(b: Boolean) {
        simSettings.mutableText = b
    }

    @JsName("addPackage") fun addPackage(button: HTMLButtonElement) {
        if (!button.hasClass("is-loading")) {
            button.addClass("is-loading")
            js("window.venuspackage = {id:'LOADING!'}")
            val purlinput = document.getElementById("package-url-val") as HTMLInputElement
            val url = purlinput.value
            ScriptManager.addPackage(url)
            window.setTimeout(Driver::packageLoaded, 100, button)
        } else {
            console.log("Cannot add a new package until the previous package has finished!")
        }
    }

    @JsName("togglePackage") fun togglePackage(packageID: String) {
        window.setTimeout(ScriptManager::togglePackage, TIMEOUT_TIME, packageID)
    }

    @JsName("removePackage") fun removePackage(packageID: String) {
        window.setTimeout(ScriptManager::removePackage, TIMEOUT_TIME, packageID)
    }

    fun packageLoaded(b: HTMLButtonElement) {
        if (venuspackage == undefined) {
            b.removeClass("is-loading")
            return
        }
        window.setTimeout(Driver::packageLoaded, 100, b)
    }

    @JsName("trace") fun trace() {
        if (trTimer != null) {
            Renderer.setNameButtonSpinning("simulator-trace", false)
            trTimer?.let(window::clearTimeout)
            trTimer = null
            tr.traceFullReset()
            sim.reset()
            Renderer.updateControlButtons()
            return
        }
        Renderer.setNameButtonSpinning("simulator-trace", true)
        Renderer.clearConsole()
        loadTraceSettings()
        trTimer = window.setTimeout(Driver::traceSt, TIMEOUT_TIME)
    }

    private fun loadTraceSettings() {
        tr.format = (document.getElementById("tregPattern") as HTMLTextAreaElement).value
        tr.base = (document.getElementById("tbase-val") as HTMLInputElement).value.toInt()
        tr.totCommands = (document.getElementById("ttot-cmds-val") as HTMLInputElement).value.toInt()
        tr.maxSteps = (document.getElementById("tmaxsteps-val") as HTMLInputElement).value.toInt()
        tr.instFirst = (document.getElementById("tinst-first") as HTMLButtonElement).value == "true"
        tr.twoStage = (document.getElementById("tTwoStage") as HTMLButtonElement).value == "true"
        wordAddressed = (document.getElementById("tPCWAddr") as HTMLButtonElement).value == "true"
    }

    var trTimer: Int? = null
    internal fun traceSt() {
        try {
            tr.traceStart()
            traceLoop()
        } catch (e: Throwable) {
            handleError("Trace tr Start", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
            Renderer.setNameButtonSpinning("simulator-trace", false)
            trTimer?.let(window::clearTimeout)
            trTimer = null
        }
    }

    internal fun traceLoop() {
        try {
            var cycles = 0
            while (cycles < TIMEOUT_CYCLES) {
                if (sim.isDone()) {
                    trTimer = window.setTimeout(Driver::runTrEnd, TIMEOUT_TIME)
                    return
                }
                try {
                    tr.traceStep()
                } catch (err: SimulatorError) {
                    trTimer = window.setTimeout(Driver::runTrEnd, TIMEOUT_TIME, err)
                    return
                }
                cycles++
            }
            trTimer = window.setTimeout(Driver::traceLoop, TIMEOUT_TIME)
        } catch (e: Throwable) {
            handleError("Trace tr Loop", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
            Renderer.setNameButtonSpinning("simulator-trace", false)
            trTimer?.let(window::clearTimeout)
            trTimer = null
        }
    }
    internal fun runTrEnd(err: SimulatorError? = null) {
        try {
            tr.traceEnd()
            if (err != null) {
                tr.traceAddError(err)
            }
            tr.traceStringStart()
            trTimer = window.setTimeout(Driver::traceStringLoop, TIMEOUT_TIME)
        } catch (e: Throwable) {
            handleError("Trace Tr End", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
            Renderer.setNameButtonSpinning("simulator-trace", false)
            trTimer?.let(window::clearTimeout)
            trTimer = null
        }
    }

    internal fun traceStringLoop() {
        try {
        var cycles = 0
        while (cycles < TIMEOUT_CYCLES) {
            if (!tr.traceStringStep()) {
                trTimer = window.setTimeout(Driver::traceStringEnd, TIMEOUT_TIME)
                return
            }
        }
            trTimer = window.setTimeout(Driver::traceStringLoop, TIMEOUT_TIME)
        } catch (e: Throwable) {
            handleError("Trace String Loop", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
            Renderer.setNameButtonSpinning("simulator-trace", false)
            trTimer?.let(window::clearTimeout)
            trTimer = null
        }
    }

    internal fun traceStringEnd() {
        try {
            tr.traceStringEnd()
            Renderer.clearConsole()
            Renderer.printConsole(tr.getString())
        } catch (e: Throwable) {
            handleError("Trace String End", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
        }
        Renderer.setNameButtonSpinning("simulator-trace", false)
        trTimer?.let(window::clearTimeout)
        trTimer = null
    }

    /*@JsName("trace") fun trace() {
        //@todo make it so trace is better
        Renderer.setNameButtonSpinning("simulator-trace", true)
        Renderer.clearConsole()
        this.loadTraceSettings()
        window.setTimeout(Driver::traceStart, TIMEOUT_TIME)
    }*/
    internal fun traceStart() {
        try {
            tr.trace()
            window.setTimeout(Driver::traceString, TIMEOUT_TIME)
        } catch (e: Throwable) {
            handleError("Trace Start", e, e is AlignmentError || e is StoreError || e is ExceededAllowedCyclesError)
            Renderer.setNameButtonSpinning("simulator-trace", false)
        }
    }
    internal fun traceString() {
        try {
            tr.traceString()
            Renderer.clearConsole()
            Renderer.printConsole(tr.getString())
        } catch (e: Throwable) {
            handleError("Trace to String", e)
        }
        Renderer.setNameButtonSpinning("simulator-trace", false)
    }

    @JsName("persistentStorage") fun persistentStorage(b: Boolean) {
        useLS = b
        if (useLS) {
            console.log("Persistent storage has been enabled!")
            LS.set("venus", "true")
            saveAll()
        } else {
            console.log("Persistent storage has been disabled!")
            LS.set("venus", "false")
            // this.LS.reset()
        }
    }

    @JsName("psReset") fun psReset() {
        LS.reset()
        console.log("Persistent storage has been reset!")
    }

    fun saveIntervalFn() {
        if (useLS) {
            blinkSave(true)
            window.setTimeout(Driver::blinkSave, 500, false)
            saveAll()
        }
    }

    fun blinkSave(b: Boolean) {
        val e = document.getElementById("sv") as HTMLButtonElement
        if (b) {
            e.style.color = "yellow"
        } else {
            e.style.color = ""
        }
    }

    fun saveAll(override: Boolean = false) {
        if (!(useLS or override)) {
            return
        }
        /*Trace settings*/
        loadTraceSettings()
        LS.set("trace_format", tr.format)
        LS.set("trace_base", tr.base.toString())
        LS.set("trace_totCommands", tr.totCommands.toString())
        LS.set("trace_maxSteps", tr.maxSteps.toString())
        LS.set("trace_instFirst", tr.instFirst.toString())
        LS.set("trace_wordAddressed", wordAddressed.toString())
        LS.set("trace_TwoStage", tr.twoStage.toString())

        /* History Limit */
        LS.set("history_limit", this.simSettings.max_histroy.toString())
        /*Text Begin*/
        LS.set("text_begin", MemorySegments.TEXT_BEGIN.toString())
        /*Other Settings*/
        LS.set("aligned_memory", simSettings.alignedAddress.toString())
        LS.set("mutable_text", simSettings.mutableText.toString())
        LS.set("ecall_exit_only", simSettings.ecallOnlyExit.toString())
        LS.set("set_regs_on_init", simSettings.setRegesOnInit.toString())
        LS.set("simargs", getDefaultArgs())
        LS.set("enableCallingConvention", this.doCallingConventionCheck.toString())

        /*Program*/
        js("codeMirror.save()")
        LS.set("prog", getText())

        /*Cache*/
        val numExtraCache = LS.safeget("cache_levels", "1").toInt()
        if (cacheLevels.size < numExtraCache) {
            for (i in (cacheLevels.size + 1)..numExtraCache) {
                LS.remove("cache_L" + i + "_associativity")
                LS.remove("cache_L" + i + "_cacheBlockSize")
                LS.remove("cache_L" + i + "_numberOfBlocks")
                LS.remove("cache_L" + i + "_placementPol")
                LS.remove("cache_L" + i + "_blockRepPolicy")
                LS.remove("cache_L" + i + "_seed")
                LS.remove("cache_L" + i + "_attach")
            }
        }
        LS.set("cache_levels", cacheLevels.size.toString())
        LS.set("cache_current_level", cache.cacheLevel.toString())
        for (i in cacheLevels.indices) {
            val curCache = cacheLevels[i]
            LS.set("cache_L" + (i + 1) + "_associativity", curCache.associativity().toString())
            LS.set("cache_L" + (i + 1) + "_cacheBlockSize", curCache.cacheBlockSize().toString())
            LS.set("cache_L" + (i + 1) + "_numberOfBlocks", curCache.numberOfBlocks().toString())
            LS.set("cache_L" + (i + 1) + "_placementPol", curCache.placementPol().toString())
            LS.set("cache_L" + (i + 1) + "_blockRepPolicy", curCache.blockRepPolicy().toString())
            LS.set("cache_L" + (i + 1) + "_seed", curCache.seed)
            LS.set("cache_L" + (i + 1) + "_attach", curCache.attached.toString())
        }

        LS.set("activeFileinEditor", activeFileinEditor)
        var s = if (active_abs_file_name == null) {
            "n"
        } else {
            "p$active_abs_file_name"
        }
        LS.set("active_abs_file_name", s)
        s = if (active_abs_file_path == null) {
            "n"
        } else {
            "p$active_abs_file_path"
        }
        LS.set("active_abs_file_path", s)

        if (::fileExplorerCurrentLocation.isInitialized) {
            LS.set("fileExplorerCurrentLocation", fileExplorerCurrentLocation.getPath())
        }
        LS.set("terminalCurrentLocation", terminal.vfs.currentLocation.getPath())
    }

    /*If b is true, will load stored values else load default values.*/
    fun loadAll(b: Boolean) {
        val t = Tracer(sim)
        /*Trace Settings*/
        var fmt = t.format
        var bs = t.base.toString()
        var totC = t.totCommands.toString()
        var ms = simSettings.maxSteps.toString()
        var instf = t.instFirst.toString()
        var tws = t.twoStage.toString()
        var wa = wordAddressed.toString()

        /* History Limit */
        var hlim = this.simSettings.max_histroy.toString()
        /*Text begin*/
        var txtStart = Renderer.intToString(MemorySegments.TEXT_BEGIN)
        /*Other Settings*/
        var am = simSettings.alignedAddress.toString()
        var mt = simSettings.mutableText.toString()
        var eeo = simSettings.ecallOnlyExit.toString()
        var sroi = simSettings.setRegesOnInit.toString()
        var simargs = ""
        var defaultTab = "venus"
        var docc = "false"

        /*Program*/
        js("codeMirror.save()")
        p = getText()
        if (useLS) {
            console.log("Using local storage!")
            /*Trace Settings*/
            fmt = LS.safeget("trace_format", fmt)
            bs = LS.safeget("trace_base", bs)
            totC = LS.safeget("trace_totCommands", totC)
            ms = LS.safeget("trace_maxSteps", ms)
            instf = LS.safeget("trace_instFirst", instf)
            tws = LS.safeget("trace_TwoStage", tws)
            wa = LS.safeget("trace_wordAddressed", wa)

            /* History Limit */
            hlim = LS.safeget("history_limit", hlim)

            /*Text Begin*/
            txtStart = LS.safeget("text_begin", txtStart)

            /*Other Settings*/
            am = LS.safeget("aligned_memory", am)
            mt = LS.safeget("mutable_text", mt)
            eeo = LS.safeget("ecall_exit_only", eeo)
            sroi = LS.safeget("set_regs_on_init", sroi)
            simargs = LS.safeget("simargs", simargs)
            defaultTab = LS.safeget("defaultTab", defaultTab)
            docc = LS.safeget("enableCallingConvention", docc)

            /*Program*/
            p = LS.safeget("prog", p)

            /*Cache*/
            try {
                setNumberOfCacheLevels(LS.safeget("cache_levels", cacheLevels.size.toString()).toInt())
                updateCacheLvl(LS.safeget("cache_current_level", cache.cacheLevel.toString()).toInt())
                for (i in cacheLevels.indices) {
                    val currentCache = cacheLevels[i]
                    currentCache.setCacheBlockSize(LS.safeget("cache_L" + (i + 1) + "_cacheBlockSize", currentCache.cacheBlockSize().toString()).toInt())
                    currentCache.setNumberOfBlocks(LS.safeget("cache_L" + (i + 1) + "_numberOfBlocks", currentCache.numberOfBlocks().toString()).toInt())
                    currentCache.setBlockRepPolicy(BlockReplacementPolicy.valueOf(LS.safeget("cache_L" + (i + 1) + "_blockRepPolicy", currentCache.blockRepPolicy().toString())))
                    currentCache.setPlacementPol(PlacementPolicy.valueOf(LS.safeget("cache_L" + (i + 1) + "_placementPol", currentCache.placementPol().toString())))
                    currentCache.setAssociativity(LS.safeget("cache_L" + (i + 1) + "_associativity", currentCache.associativity().toString()).toInt())
                    currentCache.attach(LS.safeget("cache_L" + (i + 1) + "_attach", currentCache.attached.toString()) == "true")
                    currentCache.setCurrentSeed(LS.safeget("cache_L" + (i + 1) + "_seed", currentCache.seed))
                }
            } catch (e: Throwable) {
                console.warn("An error occurred when loading the cache data!")
                console.warn(e)
            }
            try {
                VFS.load()
            } catch (e: Throwable) {
                console.warn("An error occurred when loading the VFS data!")
                console.warn(e)
            }

            var tmp = LS.safeget("active_abs_file_name", "")
            active_abs_file_name = if (tmp == "" || tmp == "n") {
                null
            } else {
                tmp.substring(1 until (tmp.length))
            }

            tmp = LS.safeget("active_abs_file_path", "")
            val ntmp = if (tmp == "" || tmp == "n") {
                null
            } else {
                tmp.substring(1 until (tmp.length))
            }
            set_active_afpath(ntmp)

            activeFileinEditor = LS.safeget("activeFileinEditor", "")

            tmp = LS.safeget("fileExplorerCurrentLocation", "")
            fileExplorerCurrentLocation = VFS.sentinel
            openVFObject(tmp)

            terminal.vfs.cd(LS.safeget("terminalCurrentLocation", ""))
            js("try {term.reset();} catch (e) {console.error(e);}")
        } else {
            console.log("Local Storage has been disabled!")
        }
        /*Trace Settings*/
        (document.getElementById("tregPattern") as HTMLTextAreaElement).value = fmt
        tr.format = fmt
        (document.getElementById("tbase-val") as HTMLInputElement).value = bs
        tr.base = bs.toInt()
        (document.getElementById("ttot-cmds-val") as HTMLInputElement).value = totC
        tr.totCommands = totC.toInt()
        (document.getElementById("tmaxsteps-val") as HTMLInputElement).value = ms
        tr.maxSteps = ms.toInt()
        simSettings.maxSteps = ms.toInt()
        Renderer.renderButton(document.getElementById("tinst-first") as HTMLButtonElement, instf == "true")
        tr.instFirst = instf == "true"
        Renderer.renderButton(document.getElementById("tPCWAddr") as HTMLButtonElement, wa == "true")
        wordAddressed = wa == "true"
        Renderer.renderButton(document.getElementById("tTwoStage") as HTMLButtonElement, tws == "true")
        tr.twoStage = tws == "true"

        /* History Limit */
        val hl = document.getElementById("max-history") as HTMLInputElement
        hl.value = hlim
        setMaxHistory(hl)

        /*Text Begin*/
        val ts = document.getElementById("text-start") as HTMLInputElement
        ts.value = txtStart
        verifyText(ts)

        /*Other Settings*/
        Renderer.renderButton(document.getElementById("alignAddr") as HTMLButtonElement, am == "true")
        simSettings.alignedAddress = am == "true"
        Renderer.renderButton(document.getElementById("mutableText") as HTMLButtonElement, mt == "true")
        simSettings.mutableText = mt == "true"
        Renderer.renderButton(document.getElementById("ecallExit") as HTMLButtonElement, eeo == "true")
        simSettings.ecallOnlyExit = eeo == "true"
        Renderer.renderButton(document.getElementById("setRegsOnInit") as HTMLButtonElement, sroi == "true")
        simSettings.setRegesOnInit = sroi == "true"
        (document.getElementById("ArgsList") as HTMLInputElement).value = simargs
        Renderer.renderButton(document.getElementById("enableCallingConvention") as HTMLButtonElement, docc == "true")
        enableCallingConvention(docc == "true")

        /*Program*/
        js("codeMirror.setValue(driver.p);")
        p = ""

        mainCache.update()
        setCacheSettings()

        ScriptManager.loadDefaults()
        ScriptManager.loadPackages()

        checkURLParams()

        fun checkToSetTab() {
            js("load_update_message(\"Initializing Venus: Waiting on packages to load...\");")
            if (!this.ScriptManager.packagesLoading()) {
                this.openGenericMainTab(defaultTab)
                this.driver_complete_loading = true
                return
            }
            window.setTimeout(fun () { checkToSetTab() }, 10)
        }
        window.setTimeout(fun () { checkToSetTab() }, 10)
        js("codeMirror.refresh();")
    }

    lateinit var fileExplorerCurrentLocation: VFSObject
    lateinit var fileExplorerPreviousLocation: VFSObject

    @JsName("deleteVFObject") fun deleteVFObject(name: String) {
        if (window.confirm("Are you sure you want to delete this file?")) {
            VFS.rm(name, fileExplorerCurrentLocation)
            refreshVFS()
        }
    }

    @JsName("openVFObject") fun openVFObject(name: String) {
        var s = VFS.chdir(name, fileExplorerCurrentLocation)
        if (s is Pair<*, *>) {
            console.log(s.second)
            s = s.first!!
        }
        if (s is VFSObject && s.type in listOf(VFSType.Drive, VFSType.Folder)) {
            fileExplorerPreviousLocation = fileExplorerCurrentLocation
            fileExplorerCurrentLocation = s
            if (!openVFObjectfromObj(fileExplorerCurrentLocation)) {
                fileExplorerCurrentLocation = fileExplorerPreviousLocation
                Renderer.addFilePWD(fileExplorerCurrentLocation)
            }
        } else {
            console.error(s)
            js("window.alertify.error('Could not open `' + name + '`: ' + s).delay(15);")
            Renderer.addFilePWD(fileExplorerCurrentLocation)
        }
    }

    fun openVFObjectfromObj(obj: VFSObject): Boolean {
        if (obj.isMounted() && obj.mountedHandler!!.validate_connection() != "") {
            val name = obj.label
            val path = obj.getPath(html_color_code = true)
            js("window.alertify.error('Could not open `' + name + '` (' + path + ')');")
            return false
        }
        Renderer.clearObjectsFromDisplay()
        Renderer.addFilePWD(obj)
//        for ((key, value) in fileExplorerCurrentLocation.contents) {
//            if (key in listOf(".", "..")) {
//                Renderer.addObjectToDisplay(value as VFSObject, key)
//            } else {
//                Renderer.addObjectToDisplay(value as VFSObject)
//            }
//        }
        for (key in fileExplorerCurrentLocation.childrenNames()) {
            if (key in listOf(".", "..")) {
                Renderer.addObjectToDisplay(fileExplorerCurrentLocation.getChild(key) as VFSObject, key)
            } else {
                Renderer.addObjectToDisplay(fileExplorerCurrentLocation.getChild(key) as VFSObject)
            }
        }
        fileExplorerCurrentLocation.childrenNamesFinish()
        return true
    }

    @JsName("refreshVFS") fun refreshVFS() {
        openVFObject(".")
    }

    fun saveEditorIfModified(): Boolean {
        // Returns if there was an error.
        if (activeFileinEditor == "") {
            return true
        }
        val txt: String = try {
            js("codeMirror.save();")
            this.getText()
        } catch (e: Throwable) {
            console.error(e)
            return false
        }
        val s = VFS.getObjectFromPath(activeFileinEditor, location = fileExplorerCurrentLocation)
        if (s is VFSObject && s.type == VFSType.File) {
            if ((s as VFSFile).readText() != txt) {
                if (window.confirm("Detected a modification in the opened file in the editor! Do you want to save it before opening the new file?")) {
                    saveVFObjectfromObj(s)
                }
            }
        } else {
            console.log(s)
            return false
        }
        return true
    }

    fun editVFObjectfromObj(obj: VFSObject) {
        if (activeFileinEditor == obj.getPath()) {
            this.openEditor()
            return
        }
        if (!saveEditorIfModified()) {
            if (!window.confirm("Could not save active file! Do you still want to open the new file?")) {
                return
            }
        }
        if (obj.type !== VFSType.File) {
            window.alert("Only files can be loaded into the editor.")
            return
        }
        try {
            val txt: String = (obj as VFSFile).readText()
            active_abs_file_name = obj.label
            set_active_afpath(obj.getPath())
            js("codeMirror.setValue(txt);")
            this.openEditor()
            js("codeMirror.refresh();")
            activeFileinEditor = obj.getPath()
        } catch (e: Throwable) {
            console.error(e)
            window.alert("Could not load file to the editor!")
        }
    }

    @JsName("editVFObject") fun editVFObject(name: String) {
        val s = VFS.getObjectFromPath(name, location = fileExplorerCurrentLocation)
        if (s is VFSObject) {
            editVFObjectfromObj(s)
        } else {
            console.log(s)
        }
    }

    fun saveVFObjectfromObj(obj: VFSObject, save: Boolean = true) {
        val name = obj.getPath()
        if (activeFileinEditor != name && !window.confirm("Are you sure you want to save the file `$activeFileinEditor` to the file `$name`?")) {
            return
        }
        val txt: String
        try {
            js("codeMirror.save();")
            txt = this.getText()
        } catch (e: Throwable) {
            console.error(e)
            window.alert("Could not save file!")
            return
        }
        if (save) {
            if (obj.type != VFSType.File) {
                window.alert("You can (currently) only save to files!")
                return
            }
            var file = obj as VFSFile
            file.setText(txt)
            this.VFS.save()
        }
        active_abs_file_name = obj.label
        set_active_afpath(obj.getPath())
        activeFileinEditor = obj.getPath()
    }

    @JsName("saveVFObject") fun saveVFObject(name: String) {
        val s = VFS.getObjectFromPath(name, location = fileExplorerCurrentLocation)
        if (s is VFSObject) {
            saveVFObjectfromObj(s)
        } else {
            console.log(s)
        }
    }

    @JsName("clearActiveFileInEditor") fun clearActiveFileInEditor() {
        active_abs_file_name = null
        set_active_afpath(null)
    }

    @JsName("vdbVFObject") fun vdbVFObject(name: String) {
        val s = VFS.getObjectFromPath(name, location = fileExplorerCurrentLocation)
        if (s is VFSObject) {
            val cur_loc = VFS.currentLocation
            VFS.currentLocation = fileExplorerCurrentLocation
            val res = vdb.execute(arrayListOf("${s.getPath()}"), terminal, false)
            VFS.currentLocation = cur_loc
            if (res != "") {
                js("alert('[ERROR]\\n' + res)")
            }
        } else {
            console.log(s)
        }
    }
}

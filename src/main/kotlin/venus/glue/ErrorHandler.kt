package venus.glue

import kotlin.browser.window

/**
 * Created by thaum on 7/27/2018.
 */

internal fun handleError(where: String, error: Throwable, handled: Boolean = false) {
    Renderer.clearConsole()
    try {
        /*Here, I will attempt to get the local storage to display in the error so if someone send sme the error, I can more easily track it.*/
        val olduseLS = Driver.useLS
        Driver.useLS = false
        val oldlsm = Driver.LS.lsm

        Driver.LS.lsm = LocalStorageManager("venus_error")
        Driver.LS.remove("venus_error")
        Driver.LS.set("venus", "true")
        Driver.saveAll()
        val t = window.localStorage.getItem("venus_error")
        window.localStorage.removeItem("venus_error")

        Driver.LS.lsm = oldlsm
        Driver.useLS = olduseLS
        if (handled) {
            Renderer.printConsole("[ERROR] An error has occurred!\n\n")
        } else {
            Renderer.printConsole("[ERROR] An uncaught error has occurred! Here are the details that may help solve this issue.\n\n")
        }
        Renderer.printConsole("Error:\n`" + error.toString())
        Renderer.printConsole("\n\nID:\n'" + where + "'!\n\n")
        if (!handled) {
            Renderer.printConsole("`\n\nData:\n" + t)
        }
    } catch (t: Throwable) {
        Renderer.printConsole("An error occurred when trying to handle the error! Please tell me what you did since I do not fully know how you caused this error and could not generate a trace for me to figure that out. All I know is that the error was here: '" + where + "' and was:\n" + error.toString())
    }
}
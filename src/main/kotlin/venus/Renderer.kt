package venus
/* ktlint-disable no-wildcard-imports */

import venusbackend.assembler.AssemblerError
import venus.vfs.VirtualFileSystem
import venusbackend.linker.LinkedProgram
import venusbackend.simulator.Simulator
import venusbackend.simulator.diffs.*

/* ktlint-enable no-wildcard-imports */

/**
 * This singleton is used to render different parts of the screen, it serves as an interface between the UI and the
 * internal venusbackend.simulator.
 *
 * @todo break this up into multiple objects
 */
internal object Renderer {
    /** The memory location currently centered */
    var activeMemoryAddress: Int = 0
    /** The venusbackend.simulator being rendered */
    private var sim: Simulator = Simulator(LinkedProgram(), VirtualFileSystem("dummy"))
    /* The way the information in the registers is displayed*/
    private var displayType = "hex"

    var activeDisplay = true

    fun updateProgramListing(idx: Number, inst: Number, orig: String? = null): InstructionDiff {
        return InstructionDiff(-1, -1, "")
    }

    /**
     * Updates the register with the given id and value.
     *
     * @param id the ID of the register (e.g., x13 has ID 13)
     * @param value the new value of the register
     * @param setActive whether the register should be set to the active register (i.e., highlighted for the user)
     */
    fun updateRegister(id: Int, value: Number, setActive: Boolean = false) {}

    fun updateMemory(id: Int) {}

    fun intToString(value: Int): String {
        var v = when (displayType) {
            "Hex" -> toHex(value)
            "Decimal" -> value.toString()
            "Unsigned" -> toUnsigned(value)
            "ASCII" -> toAscii(value)
            else -> toHex(value)
        }
        return v
    }

    /**
     * Prints the given thing to the console as a string.
     *
     * @param thing the thing to print
     */
    internal fun printConsole(thing: Any) {
        if (activeDisplay) {
            print(thing)
        }
    }

    /** Display a given [AssemblerError] */
    fun displayError(thing: Any) {
        System.err.println(thing)
    }

    /** Display a given [AssemblerError] */
    fun displayWarning(thing: Any) {
        System.err.println(thing)
    }

    fun stdout(thing: Any) {
        printConsole(thing)
    }

    fun stderr(thing: Any) {
        displayError(thing)
    }

    /** a map from integers to the corresponding hex digits */
    private val hexMap = listOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'A', 'B', 'C', 'D', 'E', 'F')

    /**
     * Convert a certain byte to hex
     *
     * @param b the byte to convert
     * @return a hex string for the byte
     *
     * @throws IndexOutOfBoundsException if b is not in -127..255
     */
    private fun byteToHex(b: Int): String {
        val leftNibble = hexMap[b ushr 4]
        val rightNibble = hexMap[b and 15]
        return "$leftNibble$rightNibble"
    }

    private fun byteToDec(b: Int): String = b.toByte().toString()

    private fun byteToUnsign(b: Int): String = b.toString()

    fun toHex(value: Number): String {
        val minLen = when (value) {
            is Int -> 8
            is Long -> 16
            else -> 16
        }
        return String.format("0x%0${minLen}X", value)
    }

    private fun toUnsigned(value: Int): String =
            if (value >= 0) value.toString() else (value + 0x1_0000_0000L).toString()

    private fun toAscii(value: Int): String =
            when (value) {
                !in 0..255 -> toHex(value)
                !in 32..126 -> "\uFFFD"
                else -> "'${value.toChar()}'"
            }

    fun updateText() {}
}

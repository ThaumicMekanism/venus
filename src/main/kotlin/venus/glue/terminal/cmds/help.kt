package venus.glue.terminal.cmds

import venus.glue.terminal.Command
import venus.glue.terminal.CommandNotFoundError
import venus.glue.terminal.Terminal

var help = Command(
        name = "help",
        execute = fun (args: MutableList<String>, t: Terminal, sudo: Boolean): String {
            if (args.size != 1) {
                return Command["help"].help
            }
            return try {
                Command[args[0]].help
            } catch (e: CommandNotFoundError) {
                "help: Could not find command '${args[0]}'"
            }
        },
        tab = fun (args: MutableList<String>, t: Terminal, sudo: Boolean): ArrayList<String> {
            return if (args.size == 1) {
                val possibleCommands = ArrayList<String>()
                for (c in Command.getCommands()) {
                    if (c.startsWith(args[0])) {
                        possibleCommands.add(c)
                    }
                }
                possibleCommands
            } else {
                ArrayList()
            }
        },
        help = "help: This command takes in zero or one argument.\nIf you do not have any arguments, all of the commands" +
                " will be listed.\nIf you have one argument, then this will print out the help message of that command."
)
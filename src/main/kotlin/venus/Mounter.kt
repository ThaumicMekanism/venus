package venus

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.Exception
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

val VENUS_AUTH_TOKEN = "1yJPLzMSwOYPYqsLegJ8NpvJXdIC7PcrWLtPxPpZ6DzI9BsFv3iGwIpilpgVy0M7TmmEA063VUkBYIHezoes4vHF6m0mZA8DuTh"
val VENUS_FS_API_PATH = "/api/fs"
val VENUS_FS_VERSION = "1.0.0"

// By default we bind to loopback - this may become configurable in the future.
val DEFAULT_HOST = "127.0.0.1"

class Mounter(var port: String, var dir: String) {
//    data class LoginToken(var token: String, var expiration: String)
//    val tokens: MutableMap<String, String>

    private val baseAbsPath: Path

    /**
     * Checks that a path is within the directory that was mounted.
     * This mitigates potential RCE vulnerabilities by preventing file operations to paths like "~/.bashrc".
     * All file operations should call this function.
     *
     * @param targetPath the path of the file to access, relative to the directory the mount was initialized to
     * @param names additional components of the path of the file to be found
     * @param verbose prints the absolute path of the requested file if true
     * @returns the file object if access is allowed, otherwise null
     */
    private fun validateFilePath(targetPath: String, vararg names: String, verbose: Boolean=true): File? {
        val fpath = Paths.get(System.getProperty("user.dir"), targetPath, *names).normalize()
        if (verbose) {
            println(fpath.toUri())
        }
        val fp = File(fpath.toUri())
        return if (!fpath.startsWith(baseAbsPath)) {
            null
        } else {
            fp
        }
    }

    @Serializable
    data class GenericRequest(val data: String)
    data class GenericResponse(val success: Boolean, val data: Any)
    init {
        println("To connect, enter `mount http://localhost:$port vmfs` on Venus.")
        val fdir = File(dir)
        if (!fdir.exists() or !fdir.isDirectory) {
            System.err.println("The passed in dir is not a directory: $dir")
            exitProcess(1)
        }
        System.setProperty("user.dir", fdir.absolutePath)
        baseAbsPath = Paths.get(fdir.absolutePath).normalize()
        val app: Javalin = Javalin.create { config ->
            config.enableCorsForAllOrigins()
        }.start(DEFAULT_HOST, port.toInt())
        app.routes {
//            post("/login") { ctx ->
//                val auth_token = ctx.body()
//                if (auth_token == VENUS_AUTH_TOKEN) {
//                    ctx.json()
//                }
//            }
            ApiBuilder.get("/version") { ctx ->
                println("Got version request from ${ctx.ip()}...")
                ctx.json(mapOf(Pair("data", VENUS_FS_VERSION)))
            }
            ApiBuilder.get("/ping") { ctx ->
                println("Got ping request from ${ctx.ip()}! Ponging...")
                ctx.json(mapOf(Pair("data", "pong")))
            }
            ApiBuilder.get("$VENUS_FS_API_PATH/name") { ctx ->
                ctx.json(GenericResponse(success = true, data = "lvfs"))
            }
            ApiBuilder.post("$VENUS_FS_API_PATH/ls/names") { ctx ->
                val rdat = ctx.body()
                print("ls request: ")
                try {
                    val req = Json.parse(GenericRequest.serializer(), rdat)
                    var filepath = req.data
                    if (filepath == "") {
                        filepath = "."
                    }
                    val fp = validateFilePath(filepath)
                    if (fp == null) {
                        ctx.json(GenericResponse(false, "$filepath: No such file or directory"))
                    } else {
                        val list = fp.list()
                        ctx.json(GenericResponse(true, list))
                    }
                } catch (e: Exception) {
                    ctx.json(GenericResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }
            @Serializable
            data class fileinfoRequest(val name: String, val path: String)
            data class fileinfoResponse(val success: Boolean, val name: String = "", val type: String = "", val data: String = "")
            ApiBuilder.post("$VENUS_FS_API_PATH/file/info") { ctx ->
                val rdat = ctx.body()
                print("file info request: ")
                try {
                    val req = Json.parse(fileinfoRequest.serializer(), rdat)
                    val name = req.name
                    val fp = validateFilePath(req.path, name)
                    if (fp == null) {
                        ctx.json(fileinfoResponse(false, data = "$name: No such file or directory"))
                    } else {
                        ctx.json(fileinfoResponse(true, name = name, type = if (fp.isFile) { "file" } else { "dir" }))
                    }
                } catch (e: Exception) {
                    ctx.json(fileinfoResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }

            @Serializable
            data class filereadRequest(val path: String)
            data class filereadResponse(val success: Boolean, val data: String)
            ApiBuilder.post("$VENUS_FS_API_PATH/file/read") { ctx ->
                val rdat = ctx.body()
                print("file read request: ")
                try {
                    val req = Json.parse(filereadRequest.serializer(), rdat)
                    val path = req.path
                    val fp = validateFilePath(path)
                    if (fp == null) {
                        ctx.json(filereadResponse(false, data = "$path: No such file or directory"))
                    } else if (!fp.isFile) {
                        ctx.json(filereadResponse(false, data = "$path: Is not file"))
                    } else {
                        val data = fp.readText()
                        ctx.json(filereadResponse(true, data = data))
                    }
                } catch (e: Exception) {
                    ctx.json(filereadResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }

            @Serializable
            data class filewriteRequest(val path: String, val data: String)
            data class filewriteResponse(val success: Boolean, val data: String)
            ApiBuilder.post("$VENUS_FS_API_PATH/file/write") { ctx ->
                val rdat = ctx.body()
                print("file write request: ")
                try {
                    val req = Json.parse(filewriteRequest.serializer(), rdat)
                    val path = req.path
                    val fp = validateFilePath(path)
                    if (fp == null) {
                        ctx.json(filewriteResponse(false, data = "$path: No such file or directory"))
                    } else if (!fp.isFile) {
                        ctx.json(filewriteResponse(false, data = "$path: No such file or directory"))
                    } else {
                        fp.writeText(req.data)
                        ctx.json(filewriteResponse(true, data = ""))
                    }
                } catch (e: Exception) {
                    ctx.json(filewriteResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }
            // Functionality needed: rm, mv, cp

            @Serializable
            data class mkdirRequest(val path: String)
            data class mkdirResponse(val success: Boolean, val data: String)
            ApiBuilder.post("$VENUS_FS_API_PATH/mkdir") { ctx ->
                val rdat = ctx.body()
                print("mkdir request: ")
                try {
                    val req = Json.parse(mkdirRequest.serializer(), rdat)
                    val path = req.path
                    val fp = validateFilePath(path)
                    val s = if (fp != null) {
                        if (fp.exists()) {
                            ctx.json(mkdirResponse(false, data = "$path: Already exists"))
                        }
                        fp.mkdir()
                    } else {
                        false
                    }
                    ctx.json(mkdirResponse(s, if (!s) { "$path: Failed to create the directory" } else { "" }))
                } catch (e: Exception) {
                    ctx.json(mkdirResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }

            @Serializable
            data class touchRequest(val path: String)
            data class touchResponse(val success: Boolean, val data: String)
            ApiBuilder.post("$VENUS_FS_API_PATH/touch") { ctx ->
                val rdat = ctx.body()
                print("touch request: ")
                try {
                    val req = Json.parse(touchRequest.serializer(), rdat)
                    val path = req.path
                    val fp = validateFilePath(path)
                    val s = if (fp != null) {
                        if (fp.exists()) {
                            ctx.json(touchResponse(false, data = "$path: Already exists"))
                        }
                        fp.createNewFile()
                    } else {
                        false
                    }
                    ctx.json(touchResponse(s, if (!s) { "$path: Failed to create the file" } else { "" }))
                } catch (e: Exception) {
                    ctx.json(touchResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }

            @Serializable
            data class rmRequest(val path: String)
            data class rmResponse(val success: Boolean, val data: String)
            ApiBuilder.post("$VENUS_FS_API_PATH/rm") { ctx ->
                val rdat = ctx.body()
                print("rm request: ")
                try {
                    val req = Json.parse(rmRequest.serializer(), rdat)
                    val path = req.path
                    val fp = validateFilePath(path)
                    val s = if (fp != null) {
                        if (!fp.exists()) {
                            ctx.json(mkdirResponse(false, data = "$path: No such file or directory"))
                        }
                        fp.deleteRecursively()
                    } else {
                        false
                    }
                    ctx.json(rmResponse(s, if (!s) { "$path: Failed to delete the file or directory" } else { "" }))
                } catch (e: Exception) {
                    ctx.json(rmResponse(false, "Internal server error: $e"))
                    println("ERROR: $e")
                }
            }
        }
    }
}
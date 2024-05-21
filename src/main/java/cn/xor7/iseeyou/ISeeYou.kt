package cn.xor7.iseeyou

import cn.xor7.iseeyou.anticheat.AntiCheatListener
import cn.xor7.iseeyou.anticheat.listeners.*
import cn.xor7.iseeyou.anticheat.suspiciousPhotographers
import cn.xor7.iseeyou.metrics.Metrics
import cn.xor7.iseeyou.updatechecker.CompareVersions
import cn.xor7.iseeyou.updatechecker.UpdateChecker
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.coordinates.Vec3Argument
import net.minecraft.server.MinecraftServer
/*
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIBukkitConfig
import dev.jorel.commandapi.arguments.ArgumentSuggestions
import dev.jorel.commandapi.kotlindsl.*
 */
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.command.CommandExecutor
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.leavesmc.leaves.entity.Photographer
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.isDirectory
import kotlin.math.pow


var toml: TomlEx<ConfigData>? = null
var photographers = mutableMapOf<String, Photographer>()
var highSpeedPausedPhotographers = mutableSetOf<Photographer>()
var instance: JavaPlugin? = null

@Suppress("unused")
class ISeeYou : JavaPlugin(), CommandExecutor {
    private var outdatedRecordRetentionDays: Int = 0
    private val commandPhotographersNameUUIDMap = mutableMapOf<String, String>() // Name => UUID

    /*
    override fun onLoad() = CommandAPI.onLoad(
        CommandAPIBukkitConfig(this)
            .verboseOutput(false)
            .silentLogs(true)
    )
     */

    override fun onEnable() {
        instance = this
        //CommandAPI.onEnable()
        registerCommand()
        setupConfig()

        logger.info("██╗███████╗███████╗███████╗██╗   ██╗ ██████╗ ██╗   ██╗")
        logger.info("██║██╔════╝██╔════╝██╔════╝╚██╗ ██╔╝██╔═══██╗██║   ██║")
        logger.info("██║███████╗█████╗  █████╗   ╚████╔╝ ██║   ██║██║   ██║")
        logger.info("██║╚════██║██╔══╝  ██╔══╝    ╚██╔╝  ██║   ██║██║   ██║")
        logger.info("██║███████║███████╗███████╗   ██║   ╚██████╔╝╚██████╔╝")
        logger.info("╚═╝╚══════╝╚══════╝╚══════╝   ╚═╝    ╚═════╝  ╚═════╝")

        if (toml != null) {
            if (toml!!.data.deleteTmpFileOnLoad) {
                try {
                    Files.walk(Paths.get(toml!!.data.recordPath), Int.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)
                        .use { paths ->
                            paths.filter { it.isDirectory() && it.fileName.toString().endsWith(".tmp") }
                                .forEach { deleteTmpFolder(it) }
                        }
                } catch (_: IOException) {
                }
            }

            EventListener.pauseRecordingOnHighSpeedThresholdPerTickSquared =
                (toml!!.data.pauseRecordingOnHighSpeed.threshold / 20).pow(2.0)

            if (toml!!.data.clearOutdatedRecordFile.enabled) {
                cleanOutdatedRecordings()
                var interval = toml!!.data.clearOutdatedRecordFile.interval
                if (interval !in 1..24) {
                    interval = 24
                    logger.warning("§c[Warning] §rFailed to load the interval parameter, reset to the default value of 24.")
                }
                object : BukkitRunnable() {
                    override fun run() = cleanOutdatedRecordings()
                }.runTaskTimer(this, 0, 20 * 60 * 60 * (interval.toLong()))
            }

            Bukkit.getPluginManager().registerEvents(EventListener, this)
        } else {
            logger.warning("§c[Error] §rFailed to initialize configuration. Plugin will not enable.")
            Bukkit.getPluginManager().disablePlugin(this)
        }

        if (toml!!.data.bStats){
            val pluginId = 21845
            val metrics: Metrics = Metrics(this, pluginId)
            metrics.addCustomChart(Metrics.SimplePie("chart_id") { "My value" })
        }

        Bukkit.getPluginManager().registerEvents(AntiCheatListener, this)

        if (Bukkit.getPluginManager().isPluginEnabled("Themis") ||
            toml!!.data.recordSuspiciousPlayer.enableThemisIntegration
        ) {
            Bukkit.getPluginManager().registerEvents(ThemisListener(), this)
            logger.info("§rRegister the Themis Listener...")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Matrix") ||
            toml!!.data.recordSuspiciousPlayer.enableMatrixIntegration
        ) {
            Bukkit.getPluginManager().registerEvents(MatrixListener(), this)
            logger.info("§rRegister the Matrix Listener...")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Vulcan") ||
            toml!!.data.recordSuspiciousPlayer.enableVulcanIntegration
        ){
            Bukkit.getPluginManager().registerEvents(VulcanListener(), this)
            logger.info("§rRegister the Vulcan Listener...")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("Negativity") ||
            toml!!.data.recordSuspiciousPlayer.enableNegativityIntegration
        ) {
            Bukkit.getPluginManager().registerEvents(NegativityListener(), this)
            logger.info("§rRegister the Negativity Listener...")
        }

        if (Bukkit.getPluginManager().isPluginEnabled("GrimAC") ||
            toml!!.data.recordSuspiciousPlayer.enableGrimACIntegration
        ) {
            Bukkit.getPluginManager().registerEvents(GrimACListener(), this)
            logger.info("§rRegister the GrimAC Listener...")
        }

        if (toml!!.data.check_for_updates){
            val updateChecker = UpdateChecker(this, "ISeeYou")
            updateChecker.getVersion { latestVersion: String ->
                val currentVersion = description.version
                val comparisonResult = CompareVersions.compareVersions(currentVersion, latestVersion)
                val temp = latestVersion.removePrefix("V")
                val logMessage = when {
                    comparisonResult < 0 -> {
                        "§a[New Version] §rA new version of your plugin is available: §b$latestVersion§r\n" +
                                "§e[Download] §rYou can download the latest plugin from the following platforms:\n" +
                                "§9[MineBBS] §rhttps://www.minebbs.com/resources/iseeyou.7276/updates\n" +
                                "§c[Hangar] §rhttps://hangar.papermc.io/CerealAxis/ISeeYou/versions\n" +
                                "§6[Github] §rhttps://github.com/MC-XiaoHei/ISeeYou/releases/tag/v1.2.1"
                    }
                    comparisonResult == 0 -> {
                        "§a[Latest Version] §rYour plugin is the latest version!"
                    }
                    else -> {
                        "§e[Version Info] §rYour plugin version is ahead of the latest version §b$latestVersion§r"
                    }
                }
                logger.info(logMessage)
            }
        }
    }

    private fun registerCommand() {
        val dispatcher = MinecraftServer.getServer().commands.dispatcher
        dispatcher.register(
            literal("photographer")
                .then(literal("create")
                    .then(argument("name", StringArgumentType.word())
                        .executes {
                            if (it.source.player == null) {
                                return@executes 1
                            }
                            val location = it.source.location
                            val name = StringArgumentType.getString(it, "name")
                            if (name.length !in 4..16) {
                                it.source.player!!.bukkitEntity.sendMessage("§4摄像机名称长度必须在4-16之间！")
                                return@executes 1
                            }
                            createPhotographer(name, location)
                            it.source.sender.sendMessage("§a成功创建摄像机：$name")
                            return@executes 0
                        }
                        .then(argument("world", StringArgumentType.word())
                            .suggests { _, builder ->
                                SharedSuggestionProvider.suggest(Bukkit.getWorlds().map { it.name }.toList(), builder)
                            }
                            .then(argument("pos", Vec3Argument.vec3())
                                .executes {
                                    val world =
                                        Bukkit.getWorld(StringArgumentType.getString(it, "world")) ?: return@executes 1
                                    val vec3 = Vec3Argument.getVec3(it, "pos")
                                    val location = Location(world, vec3.x, vec3.y, vec3.z)
                                    val name = StringArgumentType.getString(it, "name")
                                    if (name.length !in 4..16) {
                                        it.source.player!!.bukkitEntity.sendMessage("§4摄像机名称长度必须在4-16之间！")
                                        return@executes 1
                                    }
                                    createPhotographer(name, location)
                                    it.source.sender.sendMessage("§a成功创建摄像机：$name")
                                    return@executes 0
                                }
                            )
                        )
                    )
                )
                .then(literal("remove")
                    .then(argument("name", StringArgumentType.word())
                        .suggests { _, builder ->
                            SharedSuggestionProvider.suggest(commandPhotographersNameUUIDMap.keys, builder)
                        }
                        .executes {
                            val name = StringArgumentType.getString(it, "name")
                            val uuid = commandPhotographersNameUUIDMap[name] ?: run {
                                it.source.bukkitSender.sendMessage("§4不存在该摄像机！")
                                return@executes 1
                            }
                            photographers[uuid]?.stopRecording(toml!!.data.asyncSave)
                            commandPhotographersNameUUIDMap.remove(name)
                            it.source.bukkitSender.sendMessage("§a成功移除摄像机：$name")
                            return@executes 0
                        }
                    )
                )
                .then(literal("list")
                    .executes {
                        val photographerNames = commandPhotographersNameUUIDMap.keys.joinToString(", ")
                        it.source.bukkitSender.sendMessage("§a摄像机列表：$photographerNames")
                        return@executes 0
                    }
                )
        )
        dispatcher.register(
            literal("instantreplay")
                .executes {
                    val player: Player = it.source.player?.bukkitEntity as Player
                    if (InstantReplayManager.replay(player)) {
                        player.sendMessage("§a成功创建即时回放")
                    } else {
                        player.sendMessage("§4操作过快，即时回放创建失败！")
                    }
                    return@executes 0
                }
        )
        /*
        commandTree("photographer") {
            literalArgument("create") {
                stringArgument("name") {
                    playerExecutor { player, args ->
                        val location = player.location
                        val name = args["name"] as String
                        if (name.length !in 4..16) {
                            player.sendMessage("§4摄像机名称长度必须在4-16之间！")
                            return@playerExecutor
                        }
                        createPhotographer(name, location)
                        player.sendMessage("§a成功创建摄像机：$name")
                    }
                    locationArgument("location") {
                        anyExecutor { sender, args ->
                            val location = args["location"] as Location
                            val name = args["name"] as String
                            if (name.length !in 4..16) {
                                sender.sendMessage("§4摄像机名称长度必须在4-16之间！")
                                return@anyExecutor
                            }
                            createPhotographer(name, location)
                            sender.sendMessage("§a成功创建摄像机：$name")
                        }
                    }
                }
            }
            literalArgument("remove") {
                stringArgument("name") {
                    replaceSuggestions(ArgumentSuggestions.strings(commandPhotographersNameUUIDMap.keys.toList())) // 这不会工作
                    anyExecutor { sender, args ->
                        val name = args["name"] as String
                        val uuid = commandPhotographersNameUUIDMap[name] ?: run {
                            sender.sendMessage("§4不存在该摄像机！")
                            return@anyExecutor
                        }
                        photographers[uuid]?.stopRecording(toml!!.data.asyncSave)
                        sender.sendMessage("§a成功移除摄像机：$name")
                    }
                }
            }
            literalArgument("list") {
                anyExecutor { sender, _ ->
                    val photographerNames = commandPhotographersNameUUIDMap.keys.joinToString(", ")
                    sender.sendMessage("§a摄像机列表：$photographerNames")
                }
            }
        }
        commandTree("instantreplay") {
            playerExecutor { player, _ ->
                if (InstantReplayManager.replay(player)) {
                    player.sendMessage("§a成功创建即时回放")
                } else {
                    player.sendMessage("§4操作过快，即时回放创建失败！")
                }
            }
            playerArgument("player") {

            }
        }

         */
    }

    private fun createPhotographer(name: String, location: Location) {
        val photographer = Bukkit
            .getPhotographerManager()
            .createPhotographer(name, location)
        if (photographer == null) throw RuntimeException("Error on create photographer $name")
        val uuid = UUID.randomUUID().toString()

        photographer.teleport(location)
        photographers[uuid] = photographer
        commandPhotographersNameUUIDMap[name] = uuid
        val currentTime = LocalDateTime.now()
        val recordPath: String = toml!!.data.recordPath
            .replace("\${name}", "$name@Command")
            .replace("\${uuid}", uuid)
        File(recordPath).mkdirs()
        val recordFile = File(recordPath + "/" + currentTime.format(EventListener.DATE_FORMATTER) + ".mcpr")
        if (recordFile.exists()) recordFile.delete()
        recordFile.createNewFile()
        photographer.setRecordFile(recordFile)
    }

    private fun setupConfig() {
        toml = TomlEx("plugins/ISeeYou/config.toml", ConfigData::class.java)
        val errMsg = toml!!.data.isConfigValid()
        if (errMsg != null) {
            throw InvalidConfigurationException(errMsg)
        }
        toml!!.data.setConfig()
        outdatedRecordRetentionDays = toml!!.data.clearOutdatedRecordFile.days
        toml!!.save()
    }

    override fun onDisable() {
        //CommandAPI.onDisable()
        for (photographer in photographers.values) {
            photographer.stopRecording(toml!!.data.asyncSave)
        }
        photographers.clear()
        highSpeedPausedPhotographers.clear()
        suspiciousPhotographers.clear()
        instance = null
    }

    private fun deleteTmpFolder(folderPath: Path) {
        try {
            Files.walkFileTree(
                folderPath,
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    @Throws(IOException::class)
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    @Throws(IOException::class)
                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun cleanOutdatedRecordings() {
        try {
            val recordPathA: String = toml!!.data.recordPath
            val recordingsDirA = Paths.get(recordPathA).parent
            val recordingsDirB: Path? =
                if (toml!!.data.recordSuspiciousPlayer.enableMatrixIntegration || toml!!.data.recordSuspiciousPlayer.enableThemisIntegration) {
                    Paths.get(toml!!.data.recordSuspiciousPlayer.recordPath).parent
                } else {
                    null
                }

            logger.info("Start to delete outdated recordings in $recordingsDirA and $recordingsDirB")
            var deletedCount = 0

            deletedCount += deleteFilesInDirectory(recordingsDirA)
            recordingsDirB?.let {
                deletedCount += deleteFilesInDirectory(it)
            }

            logger.info("Finished deleting outdated recordings, deleted $deletedCount files")
        } catch (e: IOException) {
            logger.severe("Error occurred while cleaning outdated recordings: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun deleteFilesInDirectory(directory: Path): Int {
        var count = 0
        Files.walk(directory).use { paths ->
            paths.filter { Files.isDirectory(it) && it.parent == directory }
                .forEach { folder ->
                    count += deleteRecordingFiles(folder)
                }
        }
        return count
    }


    private fun deleteRecordingFiles(folderPath: Path): Int {
        var deletedCount = 0
        var fileCount = 0
        try {
            val currentDate = LocalDate.now()
            Files.walk(folderPath).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".mcpr") }
                    .forEach { file ->
                        fileCount++
                        val fileName = file.fileName.toString()
                        val creationDateStr = fileName.substringBefore('@')
                        val creationDate = LocalDate.parse(creationDateStr)
                        val daysSinceCreation =
                            Duration.between(creationDate.atStartOfDay(), currentDate.atStartOfDay()).toDays()
                        if (daysSinceCreation > outdatedRecordRetentionDays) {
                            val executor = Executors.newSingleThreadExecutor()
                            val future = executor.submit(Callable {
                                try {
                                    Files.delete(file)
                                    logger.info("Deleted recording file: $fileName")
                                    true
                                } catch (e: IOException) {
                                    logger.severe("Error occurred while deleting recording file: $fileName, Error: ${e.message}")
                                    e.printStackTrace()
                                    false
                                }
                            })

                            try {
                                if (future.get(2, TimeUnit.SECONDS)) {
                                    deletedCount++
                                }
                            } catch (e: TimeoutException) {
                                logger.warning("Timeout deleting file: $fileName. Skipping...")
                                future.cancel(true)
                            } finally {
                                executor.shutdown()
                            }
                        }
                    }
            }
            if (fileCount == 0 || deletedCount == 0) {
                logger.info("No outdated recording files found to delete.")
            }
        } catch (e: IOException) {
            logger.severe("Error occurred while processing recording files in folder: $folderPath, Error: ${e.message}")
            e.printStackTrace()
        }
        return deletedCount
    }

}

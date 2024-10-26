package cn.xor7.iseeyou

import cn.xor7.iseeyou.EventListener.DATE_FORMATTER
import io.github.lumine1909.api.RecorderAPI
import io.github.lumine1909.api.recorder.Recorder
import io.github.lumine1909.api.recorder.RecorderOption
import net.jodah.expiringmap.ExpirationPolicy
import net.jodah.expiringmap.ExpiringMap
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit

object InstantReplayManager {
    val photographerMap: ExpiringMap<String, Recorder> = ExpiringMap.builder()
        .expiration(toml!!.data.instantReplay.replayMinutes.toLong(), TimeUnit.MINUTES)
        .expirationPolicy(ExpirationPolicy.CREATED)
        .expirationListener { uuid: String, photographer: Recorder ->
            instance?.let {
                object : BukkitRunnable() {
                    override fun run() {
                        recordFileMap.remove(uuid)
                        player2photographerUUIDMap[photographer2playerUUIDMap[uuid]]?.remove(uuid)
                        photographer2playerUUIDMap.remove(uuid)
                        photographer.stopRecording()
                    }
                }.runTask(it)
            }
        }
        .build()
    val player2photographerUUIDMap = mutableMapOf<String, MutableSet<String>>()
    val photographer2playerUUIDMap = mutableMapOf<String, String>()
    val taskMap = mutableMapOf<String, BukkitTask>()
    val recordFileMap = mutableMapOf<String, File>()

    fun watch(player: Player) {
        object : BukkitRunnable() {
            override fun run() {
                val uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                val playerUUID = player.uniqueId.toString()
                if (!player2photographerUUIDMap.containsKey(playerUUID)) {
                    player2photographerUUIDMap[playerUUID] = mutableSetOf()
                }
                player2photographerUUIDMap[playerUUID]!!.add(uuid)
                photographer2playerUUIDMap[uuid] = playerUUID
                println("uuid: ${player2photographerUUIDMap[playerUUID].toString()}")
                val recordPath: String = toml!!.data.instantReplay.recordPath
                    .replace("\${name}", player.name)
                    .replace("\${uuid}", playerUUID)
                File(recordPath).mkdirs()
                val recordFile = File(recordPath + "/" + LocalDateTime.now().format(DATE_FORMATTER) + ".mcpr")
                recordFileMap[uuid] = recordFile
                val photographer =
                    RecorderAPI.manager.createVirtualRecorder(uuid, player, recordFile, RecorderOption())
                photographerMap[uuid] = photographer
                photographer.startRecording()
            }
        }.runTaskTimer(
            instance ?: return,
            0,
            (toml!!.data.instantReplay.createMinutes * 60 * 20).toLong()
        ).also { task ->
            taskMap[player.uniqueId.toString()] = task
        }
    }

    fun replay(player: Player): Boolean {
        var firstSubmitUUID = ""
        var firstSubmitTime = Int.MAX_VALUE
        player2photographerUUIDMap[player.uniqueId.toString()]?.forEach { uuid ->
            val expectedExpiration = photographerMap.getExpectedExpiration(uuid).toInt()
            if (firstSubmitTime > expectedExpiration) {
                firstSubmitUUID = uuid
                firstSubmitTime = expectedExpiration
            }
        }
        val recordPath: String = toml!!.data.instantReplay.recordPath
            .replace("\${name}", player.name)
            .replace("\${uuid}", player.uniqueId.toString())
        File(recordPath).mkdirs()
        val recordFile = File(recordPath + "/" + LocalDateTime.now().format(DATE_FORMATTER) + ".mcpr")
        if (recordFile.exists()) {
            recordFile.delete()
        }
        recordFile.createNewFile()
        photographerMap[firstSubmitUUID]?.stopRecording() ?: return false
        photographerMap.remove(firstSubmitUUID)
        player2photographerUUIDMap[player.uniqueId.toString()]?.remove(firstSubmitUUID)
        photographer2playerUUIDMap.remove(firstSubmitUUID)
        return true
    }
}
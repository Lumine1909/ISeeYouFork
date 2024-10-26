package cn.xor7.iseeyou.anticheat

import io.github.lumine1909.api.recorder.Recorder


data class SuspiciousPhotographer(
    val photographer: Recorder,
    val name: String,
    val lastTagged: Long,
)

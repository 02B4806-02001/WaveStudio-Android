package org.mhrri.wavestudio

import java.text.SimpleDateFormat
import java.util.*

/**
 * 录音文件数据模型
 * @param id 唯一标识
 * @param date 录音时间戳
 * @param duration 录音时长（秒）
 * @param fileURL 录音文件路径
 */
data class RecordedClip(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val duration: Double,
    val fileURL: String,
    val customName: String? = null,
    /** 删除时间戳。null 表示未删除，非 null 表示已移入最近删除 */
    val deletedAt: Long? = null,
) {
    // 格式化文件名（按时间戳生成）
    val fileName: String
        get() {
            val name = customName?.trim()
            if (!name.isNullOrEmpty()) {
                return if (name.lowercase(Locale.getDefault()).endsWith(".m4a")) name else "$name.m4a"
            }
            // 旧录音统一显示为"录音"（无自定义名称时）
            return "录音.m4a"
        }

    // 格式化时长显示（保留2位小数）
    val durationText: String
        get() = String.format(Locale.getDefault(), "%.2f", duration)

    // 格式化日期显示
    val dateText: String
        get() {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return formatter.format(Date(date))
        }
}
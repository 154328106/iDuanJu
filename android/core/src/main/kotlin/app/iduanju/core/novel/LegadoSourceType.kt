/* Copied/adapted from legado-E constant/BookSourceType.kt at
 * 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c, GPL-3.0.
 * Removed Android IntDef annotation; constants retained. See UPSTREAM-PORT.md. */
package app.iduanju.core.novel


@Suppress("ConstPropertyName")
object LegadoSourceType {

    const val default = 0           // 0 文本
    const val audio = 1             // 1 音频
    const val image = 2            // 2 图片
    const val file = 3               // 3 只提供下载服务的网站
    const val video = 4             //4 视频

}

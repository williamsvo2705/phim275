package phim275.tv.com.update

import org.json.JSONObject

/** Nội dung latest.json do GitHub Actions sinh ra cho mỗi bản phát hành. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apks: Map<String, Apk>,
) {
    data class Apk(val url: String, val sha256: String)

    companion object {
        fun parse(json: String): UpdateInfo {
            val o = JSONObject(json)
            val apksJson = o.getJSONObject("apks")
            val apks = apksJson.keys().asSequence().associateWith { abi ->
                val a = apksJson.getJSONObject(abi)
                Apk(a.getString("url"), a.getString("sha256").lowercase())
            }
            return UpdateInfo(o.getInt("versionCode"), o.getString("versionName"), apks)
        }

        /**
         * versionCode = phiên bản × 10 + mã ABI (1 = armeabi-v7a, 2 = arm64-v8a, 3 = x86_64).
         * So sánh phần phiên bản để không coi bản khác ABI là "mới hơn".
         */
        fun isNewer(remoteVersionCode: Int, localVersionCode: Int): Boolean =
            remoteVersionCode / 10 > localVersionCode / 10

        fun abiOf(versionCode: Int): String? = when (versionCode % 10) {
            1 -> "armeabi-v7a"
            2 -> "arm64-v8a"
            3 -> "x86_64"
            else -> null
        }
    }
}

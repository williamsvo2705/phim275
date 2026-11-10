package phim275.tv.com.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Đọc playlist từ bản chép lúc trang tải (link playlist có token dùng một lần → xin lại bị 403), mọi thứ khác
 * (đoạn video, khóa giải mã, playlist chưa chép) tải qua mạng. Mỗi bản chép chỉ dùng một lần: phát trực tiếp
 * cần tải lại playlist mới định kỳ.
 */
@OptIn(UnstableApi::class)
class PlaylistCopyDataSource(
    private val copies: MutableMap<String, ByteArray>,
    private val upstream: DataSource,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val copy = synchronized(copies) { copies.remove(dataSpec.uri.toString()) }
        val source = if (copy != null) ByteArrayDataSource(copy) else upstream
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        current?.close()
        current = null
    }

    class Factory(private val copies: MutableMap<String, ByteArray>, private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = PlaylistCopyDataSource(copies, upstream.createDataSource())
    }
}

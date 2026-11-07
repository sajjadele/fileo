package ir.fileo.core.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** فایلی که در OFFER اعلام می‌شود (قابل سریال‌سازی) */
@Serializable
data class OfferedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
)

/** فایل فرستنده — با قابلیت باز کردن جریان خواندن */
data class OutgoingFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val openStream: () -> InputStream,
)

/**
 * مقصد نوشتن یک فایل دریافتی.
 * تولیدی: MediaStore/فایل — تستی: حافظه.
 */
interface IncomingSink {
    /** نام نهایی ذخیره‌شده (بعد از رفع تکراری بودن) */
    val displayName: String

    fun outputStream(): OutputStream

    /** پاکسازی ورودی نیمه‌تمر در خطا/لغو */
    fun onAborted()
}

fun interface IncomingSinkFactory {
    fun open(fileName: String, mimeType: String, size: Long): IncomingSink
}

/** وضعیت یک سشن انتقال — مدل مشترک فرستنده و گیرنده */
sealed interface TransferState {
    /** فرستنده: در حال اتصال/ارسال OFFER */
    data object Offering : TransferState

    /** فرستنده: منتظر تصمیم گیرنده */
    data object AwaitingApproval : TransferState

    /** گیرنده: پیشنهاد رسیده، منتظر تصمیم کاربر */
    data object IncomingOffer : TransferState

    data class Transferring(
        val fileIndex: Int,
        val fileCount: Int,
        val currentFileName: String,
        val bytesDone: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
    ) : TransferState

    /** گیرنده: نام‌های ذخیره‌شده؛ فرستنده: خالی */
    data class Completed(val savedNames: List<String>) : TransferState
    data object Rejected : TransferState
    data class Failed(val message: String) : TransferState
    data object Cancelled : TransferState
}

val TransferState.isTerminal: Boolean
    get() = this is TransferState.Completed || this is TransferState.Rejected ||
        this is TransferState.Failed || this is TransferState.Cancelled

/** سشن انتقال — از ایجاد تا پایان، مدل مشترک دو طرف */
class TransferSession(
    val transferId: String,
    val direction: Direction,
    val peerName: String,
    val files: List<OfferedFile>,
) {
    enum class Direction { SEND, RECEIVE }

    enum class Decision { ACCEPT, REJECT }

    private val _state = MutableStateFlow<TransferState>(TransferState.Offering)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val decision = CompletableDeferred<Decision>()

    @Volatile
    var cancelRequested = false
        private set

    val totalBytes: Long get() = files.sumOf { it.size }

    /** فقط موتور وضعیت را تغییر می‌دهد */
    internal fun updateState(state: TransferState) {
        _state.value = state
    }

    fun requestCancel() {
        cancelRequested = true
        // اگر گیرنده منتظر تصمیم است، لغو بلافاصله همان‌جا هم حل می‌شود
        decision.complete(Decision.REJECT)
    }

    /** تصمیم کاربر گیرنده */
    fun accept() {
        decision.complete(Decision.ACCEPT)
    }

    fun reject() {
        decision.complete(Decision.REJECT)
    }

    suspend fun awaitDecision(timeoutMs: Long): Decision? =
        withTimeoutOrNull(timeoutMs) { decision.await() }

    fun isTerminalState(): Boolean = state.value.isTerminal
}

/** لغو انتقال — سیگنال کنترلی داخلی */
class TransferCancelledException : Exception("transfer cancelled")

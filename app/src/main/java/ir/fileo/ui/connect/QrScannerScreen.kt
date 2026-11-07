package ir.fileo.ui.connect

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import ir.fileo.R
import java.util.concurrent.Executors

/**
 * اسکنر QR — CameraX + ZXing (بدون وابستگی به سرویس‌های گوگل).
 * نتیجهٔ خام به [onResult] می‌رود؛ پارس و تصمیم با فراخوان است.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            hasCameraPermission -> CameraPreviewWithAnalyzer(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onQr = onResult,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.scan_permission_needed),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.scan_grant_camera))
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithAnalyzer(
    onQr: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val previewView = PreviewView(context)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, QrFrameAnalyzer(onQr))
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
            previewView
        },
        onRelease = {
            executor.shutdown()
        },
    )
}

/** آنالایزر فریم‌های دوربین — دیکود ZXing با تلاش دوم بعد از چرخش ۹۰ درجه */
class QrFrameAnalyzer(private val onQr: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = com.google.zxing.MultiFormatReader().apply {
        setHints(
            mapOf(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val raw = ByteArray(plane.buffer.remaining())
            plane.buffer.get(raw)
            val width = image.width
            val height = image.height

            // ZXing انتظار دادهٔ فشرده (بدون padding سطرها) دارد — rowStride ممکن است بزرگ‌تر باشد
            val packed = if (plane.rowStride == width) {
                raw
            } else {
                ByteArray(width * height).also { out ->
                    for (row in 0 until height) {
                        System.arraycopy(raw, row * plane.rowStride, out, row * width, width)
                    }
                }
            }

            val source = com.google.zxing.PlanarYUVLuminanceSource(
                packed,
                width,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
            val result = try {
                reader.decodeWithState(bitmap)
            } catch (e: com.google.zxing.NotFoundException) {
                try {
                    reader.decodeWithState(bitmap.rotateCounterClockwise())
                } catch (e2: com.google.zxing.NotFoundException) {
                    null
                }
            }
            reader.reset()
            result?.text?.let(onQr)
        } catch (e: Exception) {
            // فریم خراب/ناتمام — نادیده گرفته می‌شود
        } finally {
            image.close()
        }
    }
}

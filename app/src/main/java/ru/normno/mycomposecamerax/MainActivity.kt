@file:OptIn(ExperimentalPermissionsApi::class)

package ru.normno.mycomposecamerax

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import ru.normno.mycomposecamerax.ui.theme.MyComposeCameraXTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyComposeCameraXTheme {
                CameraPreviewScreen()
            }
        }
    }
}

@ExperimentalCamera2Interop
@Composable
fun CameraPreviewScreen(modifier: Modifier = Modifier) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    if (cameraPermissionState.status.isGranted) {
        val viewModel = viewModel<CameraPreviewViewModel>()
        CameraPreviewContent(
            viewModel = viewModel,
        )
    } else {
        val textToShow = if (cameraPermissionState.status.shouldShowRationale)
            "Whoops! Looks like we need your camera to work our magic!" +
                    "Don't worry, we just wanna see your pretty face (and maybe some cats).  " +
                    "Grant us permission and let's get this party started!"
        else {
            "Hi there! We need your camera to work our magic! ✨\n" +
                    "Grant us permission and let's get this party started! \uD83C\uDF89"
        }
        Column(
            modifier = modifier
                .fillMaxSize()
                .wrapContentSize()
                .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = textToShow,
                textAlign = TextAlign.Center,
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp)
            )
            Button(
                onClick = {
                    cameraPermissionState.launchPermissionRequest()
                }
            ) {
                Text(
                    text = "Unleash the Camera!"
                )
            }
        }
    }
}

@Composable
fun CameraPreviewContent(
    viewModel: CameraPreviewViewModel,
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
) {
    val surfaceRequest by viewModel.surfaceRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(lifecycleOwner) {
        viewModel.bindToCamera(context.applicationContext, lifecycleOwner)
    }

    val sensorFaceRects by viewModel.sensorFaceRects.collectAsStateWithLifecycle()
    val transformationInfo by
    produceState<SurfaceRequest.TransformationInfo?>(null, surfaceRequest) {
        try {
            surfaceRequest?.setTransformationInfoListener(Runnable::run) { transformationInfo ->
                value = transformationInfo
            }
            awaitCancellation()
        } finally {
            surfaceRequest?.clearTransformationInfoListener()
        }
    }
    val shouldSpotlightFaces by remember {
        derivedStateOf { sensorFaceRects.isNotEmpty() && transformationInfo != null}
    }
    val spotlightColor = Color(0xDDE60991)

    var autofocusRequest by remember { mutableStateOf(UUID.randomUUID() to Offset.Unspecified) }

    val autofocusRequestId = autofocusRequest.first
    // Show the autofocus indicator if the offset is specified
    val showAutofocusIndicator = autofocusRequest.second.isSpecified
    // Cache the initial coords for each autofocus request
    val autofocusCoords = remember(autofocusRequestId) { autofocusRequest.second }

    // Queue hiding the request for each unique autofocus tap
    if (showAutofocusIndicator) {
        LaunchedEffect(autofocusRequestId) {
            delay(1000)
            // Clear the offset to finish the request and hide the indicator
            autofocusRequest = autofocusRequestId to Offset.Unspecified
        }
    }

    surfaceRequest?.let { request ->
        val coordinateTransformer = remember { MutableCoordinateTransformer() }
        CameraXViewfinder(
            surfaceRequest = request,
            coordinateTransformer = coordinateTransformer,
            modifier = modifier.pointerInput(viewModel, coordinateTransformer) {
                detectTapGestures { tapCoords ->
                    with(coordinateTransformer) {
                        viewModel.tapToFocus(tapCoords.transform())
                    }
                    autofocusRequest = UUID.randomUUID() to tapCoords
                }
            }
        )

        AnimatedVisibility(shouldSpotlightFaces, enter = fadeIn(), exit = fadeOut()) {
            Canvas(Modifier.fillMaxSize()) {
                val uiFaceRects = sensorFaceRects.transformToUiCoords(
                    transformationInfo = transformationInfo,
                    uiToBufferCoordinateTransformer = coordinateTransformer
                )

                // Fill the whole space with the color
                drawRect(spotlightColor)
                // Then extract each face and make it transparent

                uiFaceRects.forEach { faceRect ->
                    drawRect(
                        Brush.radialGradient(
                            0.4f to Color.Black, 1f to Color.Transparent,
                            center = faceRect.center,
                            radius = faceRect.minDimension * 2f,
                        ),
                        blendMode = BlendMode.DstOut
                    )
                }
            }
        }
    }
}

private fun List<Rect>.transformToUiCoords(
    transformationInfo: SurfaceRequest.TransformationInfo?,
    uiToBufferCoordinateTransformer: MutableCoordinateTransformer
): List<Rect> = this.map { sensorRect ->
    val bufferToUiTransformMatrix = Matrix().apply {
        setFrom(uiToBufferCoordinateTransformer.transformMatrix)
        invert()
    }

    val sensorToBufferTransformMatrix = Matrix().apply {
        transformationInfo?.let {
            setFrom(it.sensorToBufferTransform)
        }
    }

    val bufferRect = sensorToBufferTransformMatrix.map(sensorRect)
    val uiRect = bufferToUiTransformMatrix.map(bufferRect)

    uiRect
}
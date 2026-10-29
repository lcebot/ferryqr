@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.lcebot.ferryqr.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lcebot.ferryqr.Phase
import com.lcebot.ferryqr.R
import com.lcebot.ferryqr.ScanViewModel
import com.lcebot.ferryqr.content.Classifier
import com.lcebot.ferryqr.dispatch.ActionResult
import com.lcebot.ferryqr.dispatch.Cards
import com.lcebot.ferryqr.dispatch.Dispatcher
import kotlinx.coroutines.launch
import kotlin.math.abs

private val CONTROL_LABEL_WIDTH = 88.dp

@Composable
fun ScanScreen(vm: ScanViewModel, onOpenHistory: () -> Unit, onOpenGenerator: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val phase by vm.phase.collectAsStateWithLifecycle()
    val sighting by vm.sighting.collectAsStateWithLifecycle()
    val session by vm.cameraSession.collectAsStateWithLifecycle()
    val helperNotice by vm.helperNotice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var torch by rememberSaveable { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val haptics = rememberHaptics()
    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            when (message) {
                R.string.reset_done -> haptics.confirm()
                R.string.no_code_found, R.string.image_unreadable, R.string.no_camera -> haptics.reject()
            }
            snackbar.showSnackbar(context.getString(message))
        }
    }

    LaunchedEffect(phase) {
        val routing = phase as? Phase.Routing ?: return@LaunchedEffect
        vm.record(routing.detection)
        val payload = Classifier.classify(routing.detection.text)
        if (Dispatcher.autoLaunch(activity, payload)) activity.finish()
        else vm.show(Cards.build(activity, payload, routing.detection.format))
    }

    val camera = rememberCameraPermission(activity)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::decodeImage)
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (camera.granted) {
                CameraPreview(
                    analyzer = vm.analyzer,
                    executor = vm.analysisExecutor,
                    autoZoom = vm.autoZoom,
                    session = session,
                    torch = torch,
                    onFlashUnit = { hasFlash = it },
                    onZoom = { zoom = it },
                    onUnavailable = { vm.message(R.string.no_camera) },
                    modifier = Modifier.fillMaxSize(),
                )
                ScanOverlay(
                    sighting = sighting,
                    confirming = phase as? Phase.Confirming,
                    onConfirmed = vm::confirmed,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PermissionPanel(
                    openSettings = camera.permanentlyDenied,
                    onRequest = camera.request,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (phase == Phase.Decoding) {
                LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (camera.granted) {
                    // The column grows upwards, so the notice appearing moves nothing below it.
                    AnimatedVisibility(
                        visible = helperNotice,
                        enter = fadeIn(MaterialTheme.motionScheme.slowEffectsSpec()) +
                            scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), initialScale = 0.8f),
                        exit = fadeOut(MaterialTheme.motionScheme.slowEffectsSpec()) +
                            scaleOut(MaterialTheme.motionScheme.defaultSpatialSpec(), targetScale = 0.9f),
                    ) {
                        HelperNotice()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            stringResource(
                                when {
                                    sighting?.deep == true -> R.string.hint_looking
                                    sighting != null -> R.string.hint_hold
                                    else -> R.string.hint_aim
                                }
                            )
                        )
                        if (abs(zoom - 1f) >= 0.05f) StatusPill(stringResource(R.string.zoom_label, zoom))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    ControlButton(R.drawable.ic_photo_library, R.string.action_gallery) {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    if (camera.granted && hasFlash) {
                        TorchButton(checked = torch, onCheckedChange = { torch = it })
                    } else {
                        Box(Modifier.width(CONTROL_LABEL_WIDTH))
                    }
                    if (camera.granted) {
                        ControlButton(R.drawable.ic_restart_alt, R.string.action_reset, pressFeedback = false, onClick = vm::manualReset)
                    } else {
                        Box(Modifier.width(CONTROL_LABEL_WIDTH))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(padding)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopIconButton(R.drawable.ic_qr_code_2, R.string.action_generate, onOpenGenerator)
                TopIconButton(R.drawable.ic_history, R.string.action_history, onOpenHistory)
            }

            (phase as? Phase.Picking)?.let { picking ->
                PickOverlay(picking.frame, picking.detections, picking.alreadyShown, onPick = vm::pick, onClose = vm::resume)
            }
        }
    }

    fun onResult(result: ActionResult) {
        when (result) {
            ActionResult.Handoff -> {
                haptics.tap()
                activity.finish()
            }
            ActionResult.Copied -> {
                haptics.confirm()
                // Android 13 and later confirm clipboard writes with their own overlay.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.copied)) }
                }
            }
            ActionResult.Unavailable -> {
                haptics.reject()
                scope.launch { snackbar.showSnackbar(context.getString(R.string.no_app)) }
            }
            ActionResult.Done -> haptics.tap()
        }
    }

    (phase as? Phase.Showing)?.let { showing ->
        ResultSheet(
            card = showing.card,
            onDismiss = vm::resume,
            onAction = { action -> onResult(action.run(activity)) },
        )
    }
}

/** A labeled round button; labels keep the less familiar reset action discoverable. */
@Composable
private fun ControlButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    pressFeedback: Boolean = true,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier.width(CONTROL_LABEL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            onClick = {
                if (pressFeedback) haptics.tap()
                onClick()
            },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TopIconButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FilledTonalIconButton(
        onClick = { haptics.tap(); onClick() },
        shapes = IconButtonDefaults.shapes(),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun TorchButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    Column(
        modifier = Modifier.width(CONTROL_LABEL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledIconToggleButton(
            checked = checked,
            onCheckedChange = { haptics.toggle(it); onCheckedChange(it) },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
        ) {
            Icon(
                painterResource(if (checked) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off),
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
        Text(
            stringResource(R.string.action_torch),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

/** Tells the user that WeChat codes skip the second scan, because the LSPosed helper answered. */
@Composable
private fun HelperNotice() {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.helper_ready), style = MaterialTheme.typography.titleSmallEmphasized)
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.5f), contentColor = Color.White) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private class CameraPermissionState(
    val granted: Boolean,
    val permanentlyDenied: Boolean,
    val request: () -> Unit,
)

@Composable
private fun rememberCameraPermission(activity: Activity): CameraPermissionState {
    fun check() = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(check()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA)
    }
    // The user may grant the permission from system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        granted = check()
        onPauseOrDispose { }
    }
    val permanentlyDenied = asked && !granted &&
        !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    return CameraPermissionState(granted, permanentlyDenied) {
        if (permanentlyDenied) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
            )
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
}

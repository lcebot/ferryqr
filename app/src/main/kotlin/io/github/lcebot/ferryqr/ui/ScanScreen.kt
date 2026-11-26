@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.ferryqr.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.ferryqr.Phase
import io.github.lcebot.ferryqr.R
import io.github.lcebot.ferryqr.ScanViewModel
import io.github.lcebot.ferryqr.dispatch.Dispatcher
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The smallest a control label shrinks to before it is cut short. */
private val MIN_LABEL_SIZE = 9.sp

/** Closer looks this long in the making count as looking closer, and the hint says so. */
private const val LOOKING_CLOSER_AFTER_MS = 300L

/** The hint keeps saying so for this long after the closer looks pause, which they do between reads. */
private const val LOOKING_CLOSER_HOLD_MS = 400L

/** How long the zoom pill stays once the zoom is back at the base, showing it there, before it leaves. */
private const val ZOOM_HOLD_MS = 1000L

@Composable
fun ScanScreen(vm: ScanViewModel, onOpenHistory: () -> Unit, onOpenGenerator: () -> Unit, onOpenSettings: () -> Unit) {
    val activity = LocalActivity.current ?: return
    val context = LocalContext.current
    val phase by vm.phase.collectAsStateWithLifecycle()
    val session by vm.cameraSession.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val torch = remember { Torch() }
    var zoom by remember { mutableFloatStateOf(1f) }

    val haptics = rememberHaptics()
    // Messages the scanner sends by itself, such as an image without a code, mark a failure with a
    // haptic of their own; an action's message follows the haptic its button already gave.
    LaunchedEffect(vm) {
        vm.messages.collectLatest { message ->
            when (message) {
                R.string.no_code_found, R.string.image_unreadable, R.string.no_camera -> haptics.reject()
            }
            snackbar.showLatest(context.getString(message))
        }
    }

    LaunchedEffect(phase) {
        if (phase is Phase.MiniCodeFound) haptics.found()
    }
    // A code goes to its app from here, the only place with an activity to start it; each is tried once.
    LaunchedEffect(vm) {
        vm.routes.collect { effect ->
            if (Dispatcher.autoLaunch(activity, effect.payload, effect.origin, effect.plan)) activity.finish() else vm.show(effect)
        }
    }
    // The sighting changes with nearly every frame while a code is in view; only the overlay that
    // follows the code reads it as it is, and the rest of the page only whether there is one and
    // whether it is looked at closer, so the page does not compose again at the camera's pace.
    val sightingState = vm.sighting.collectAsStateWithLifecycle()
    val sighted by remember { derivedStateOf { sightingState.value != null } }
    val deep by remember { derivedStateOf { sightingState.value?.deep == true } }
    // The hint and the indicator on the code tell that it is being looked at closer only once that
    // goes on for a moment, so a code read at the first closer look never makes them blink.
    val lookingCloser = rememberSteady(deep, showAfterMs = LOOKING_CLOSER_AFTER_MS, holdMs = LOOKING_CLOSER_HOLD_MS)
    // Back at the base zoom, the pill shows it for a moment before it leaves, so a zoom that comes
    // back to where it started is seen to arrive there.
    val zoomShown = rememberSteady(abs(zoom - 1f) >= 0.05f, showAfterMs = 0L, holdMs = ZOOM_HOLD_MS)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val pillEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val pillSpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val pillResize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()

    val camera = rememberCameraPermission(activity)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::decodeImage)
    }

    Box(Modifier.fillMaxSize()) {
        // The controls are the scaffold's top and bottom bars, so the permission panel between them
        // and the snackbar above them are placed knowing their size in the same pass.
        Scaffold(
            containerColor = Color.Black,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopIconButton(R.drawable.ic_settings, R.string.action_settings, onOpenSettings)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TopIconButton(R.drawable.ic_qr_code_2, R.string.action_generate, onOpenGenerator)
                        TopIconButton(R.drawable.ic_history, R.string.action_history, onOpenHistory)
                    }
                }
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (camera.granted) {
                        // The hint gives way to the zoom pill, which is short, so both stay inside the
                        // edges. The pill's place opens and closes together with the gap before it, so
                        // the hint slides aside and back rather than jumping, while the pill itself
                        // grows and fades in whole, its rounded ends never cut off by the opening place.
                        // Both are small parts of the page, which the scheme's fast springs move.
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusPill(
                                when {
                                    sighted && lookingCloser -> R.string.hint_looking
                                    sighted -> R.string.hint_hold
                                    else -> R.string.hint_aim
                                },
                                Modifier.weight(1f, fill = false),
                            )
                            AnimatedVisibility(
                                visible = zoomShown,
                                enter = fadeIn(pillEffects) + scaleIn(pillSpatial) +
                                    expandHorizontally(pillResize, expandFrom = Alignment.Start, clip = false),
                                exit = fadeOut(pillEffects) + scaleOut(pillSpatial) +
                                    shrinkHorizontally(pillResize, shrinkTowards = Alignment.Start, clip = false),
                            ) {
                                ZoomPill(zoom, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        ControlButton(R.drawable.ic_photo_library, R.string.action_gallery, Modifier.weight(1f)) {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        // Each control keeps its place in the row and fades in once it can be used.
                        Box(Modifier.weight(1f)) {
                            // Named in full: inside the row, the row's own overload would be picked and refused.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = camera.granted && torch.available,
                                enter = fadeIn(effects) + scaleIn(spatial),
                                exit = fadeOut(effects) + scaleOut(spatial),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TorchButton(checked = torch.on, onCheckedChange = torch::set, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            // Named in full: inside the row, the row's own overload would be picked and refused.
                            androidx.compose.animation.AnimatedVisibility(
                                visible = camera.granted,
                                enter = fadeIn(effects) + scaleIn(spatial),
                                exit = fadeOut(effects) + scaleOut(spatial),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ControlButton(
                                    R.drawable.ic_restart_alt,
                                    R.string.action_reset,
                                    Modifier.fillMaxWidth(),
                                    onClick = vm::manualReset,
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            // The camera fills the whole window behind the bars.
            Box(Modifier.fillMaxSize()) {
                if (camera.granted) {
                    CameraPreview(
                        analyzer = vm.analyzer,
                        executor = vm.analysisExecutor,
                        autoZoom = vm.autoZoom,
                        session = session,
                        torch = torch,
                        onZoom = { zoom = it },
                        onUnavailable = { vm.message(R.string.no_camera) },
                        scanning = phase == Phase.Scanning,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ScanOverlay(
                        sighting = { sightingState.value },
                        lookingCloser = lookingCloser,
                        confirming = phase as? Phase.Confirming,
                        onConfirmed = vm::confirmed,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Between the bars, so when a large font makes it scroll, nothing of it hides behind them.
                    PermissionPanel(
                        openSettings = camera.permanentlyDenied,
                        onRequest = camera.request,
                        modifier = Modifier.align(Alignment.Center).padding(padding),
                    )
                }

                AnimatedVisibility(
                    visible = phase == Phase.Decoding,
                    enter = fadeIn(effects) + scaleIn(spatial),
                    exit = fadeOut(effects) + scaleOut(spatial),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    LoadingIndicator(Modifier.size(64.dp))
                }
            }
        }

        // The frozen frame and its dimming fade in over the whole page, controls included, and back
        // out when one is picked; only the one in view answers the back gesture.
        AnimatedContent(
            targetState = phase as? Phase.Picking,
            contentKey = { it != null },
            // Each fades where it is, so the frame never grows from a corner as the container would.
            transitionSpec = { (fadeIn(effects) togetherWith fadeOut(effects)) using null },
            label = "pick",
        ) { picking ->
            if (picking != null) {
                PickOverlay(
                    picking.frame,
                    picking.detections,
                    picking.alreadyShown,
                    active = phase == picking,
                    onPick = vm::pick,
                    onClose = vm::resume,
                )
            }
        }
    }

    val onResult = rememberActionResultHandler(activity::finish) { message ->
        scope.launch { snackbar.showLatest(context.getString(message)) }
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            onClick = {
                haptics.tap()
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
        ControlLabel(stringResource(label))
    }
}

/**
 * A control's name under its button. A large font scale shrinks it before it would need a third
 * line, so the three controls keep their buttons in one row.
 */
@Composable
private fun ControlLabel(text: String) {
    val style = MaterialTheme.typography.labelMedium
    Text(
        text,
        style = style,
        color = Color.White,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = MIN_LABEL_SIZE, maxFontSize = style.fontSize),
    )
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
private fun TorchButton(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Column(
        modifier = modifier,
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
        ControlLabel(stringResource(R.string.action_torch))
    }
}

/** The hint under the camera; a new hint fades in over the old one as the pill takes its width. */
@Composable
private fun StatusPill(@StringRes text: Int, modifier: Modifier = Modifier) {
    val fade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val resize = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    PillSurface(modifier) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform { _, _ -> resize } },
            contentAlignment = Alignment.Center,
            label = "hint",
        ) { shown ->
            PillText(stringResource(shown))
        }
    }
}

/** The zoom, in figures of one width, so the pill and the hint beside it keep still as it changes. */
@Composable
private fun ZoomPill(zoom: Float, modifier: Modifier = Modifier) {
    PillSurface(modifier) {
        PillText(stringResource(R.string.zoom_label, zoom), MaterialTheme.typography.labelLarge.withTabularFigures())
    }
}

@Composable
private fun PillSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = 0.5f),
        contentColor = Color.White,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun PillText(text: String, style: TextStyle = MaterialTheme.typography.labelLarge) {
    Text(
        text,
        style = style,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
    // Set as the request goes out rather than when it is answered, so a page made again while the
    // system asks, as after a rotation, does not ask a second time.
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked && !requested) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
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

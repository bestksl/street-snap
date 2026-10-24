package dev.streetsnap.app.pose

import android.os.VibrationEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * T72 pose assistant viewfinder layer: the detected white stick figure hugs the body,
 * the amber target figure shows where the joints should go, and the bottom card carries
 * the joint-level nudge. With no model installed it renders the demo browser instead.
 */
@Composable
internal fun PoseOverlayLayer(
    state: PoseUiState,
    hapticEnabled: Boolean,
    onNextPose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val definition = PoseCatalog.byId(state.currentPoseId)
    when {
        state.live -> LivePoseLayer(state, definition, hapticEnabled, onNextPose, modifier)
        state.demo -> DemoPoseLayer(definition, onNextPose, modifier)
    }
}

@Composable
private fun LivePoseLayer(
    state: PoseUiState,
    definition: PoseDefinition,
    hapticEnabled: Boolean,
    onNextPose: () -> Unit,
    modifier: Modifier,
) {
    val guide = state.guide
    val ready = guide?.ready == true
    val context = LocalContext.current
    LaunchedEffect(guide?.achievedAtMs) {
        if ((guide?.achievedAtMs ?: 0L) > 0L && hapticEnabled) {
            val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                as? android.os.Vibrator
            @Suppress("MissingPermission") // VIBRATE is a normal permission, granted at install.
            vibrator?.vibrate(VibrationEffect.createOneShot(20L, 60))
        }
    }
    // Auto-advance shortly after the pose is held, keeping the shooting rhythm moving.
    LaunchedEffect(ready, definition.id) {
        if (ready) {
            delay(2_200L)
            onNextPose()
        }
    }
    val targetColor by animateColorAsState(
        if (ready) Color(0xFF54C97E) else Color(0xFFFFB36B),
        animationSpec = tween(220, easing = FastOutSlowInEasing), label = "poseTargetColor")
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val sample = guide?.sample
            // Only draw once a person is actually detected: the white figure hugs the real
            // body and the amber target is projected onto that same body (it follows the
            // moving person). With nobody in frame nothing is rendered — the bottom card
            // carries the instruction; a fixed centre figure would pretend the person is
            // already placed where the overlay is.
            if (sample != null && guide.personVisible) {
                drawSkeleton(
                    points = sample.points.mapIndexed { index, point -> index to point }.toMap(),
                    normalised = true,
                    visible = { index -> sample.points.getOrNull(index)?.isVisible == true },
                    color = Color.White.copy(alpha = .72f),
                    jointRadius = 3.2.dp.toPx(),
                    strokeWidth = 2.dp.toPx(),
                    dashed = false,
                )
                definition.template.projectTo(sample)?.let { projected ->
                    drawSkeleton(
                        points = projected,
                        normalised = true,
                        visible = { true },
                        color = targetColor,
                        jointRadius = 4.dp.toPx(),
                        strokeWidth = 2.5.dp.toPx(),
                        dashed = false,
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp, start = 28.dp, end = 28.dp)
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = .68f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                if (ready) "姿势到位 · 保持" else definition.title,
                color = if (ready) Color(0xFF54C97E) else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            val supporting = when {
                ready -> "很好，下一个姿势马上来"
                guide?.personVisible == false -> "把人放进画面，头到脚都入镜"
                guide?.hint != null -> guide.hint
                else -> definition.invite
            }
            Text(supporting, Modifier.padding(top = 2.dp),
                color = Color.White.copy(alpha = .82f), fontSize = 11.sp,
                maxLines = 2)
            if (!ready) {
                val score = (guide?.score ?: 0f).coerceIn(0f, 1f)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(score).height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(targetColor))
                }
            }
        }
    }
}

@Composable
private fun DemoPoseLayer(
    definition: PoseDefinition,
    onNextPose: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 260.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = .66f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("演示模式 · 未启用实时识别",
                color = Color.White.copy(alpha = .62f), fontSize = 10.sp, maxLines = 1)
            Canvas(Modifier.fillMaxWidth().height(168.dp).padding(vertical = 6.dp)) {
                val projected = PoseTemplate.projectCentered(
                    definition.template.joints,
                    centerX = size.width / 2f,
                    topY = size.height * .04f,
                    pixelsPerUnit = size.height * .80f,
                )
                drawSkeleton(
                    points = projected,
                    normalised = false,
                    visible = { true },
                    color = Color(0xFFFFB36B),
                    jointRadius = 4.dp.toPx(),
                    strokeWidth = 2.5.dp.toPx(),
                    dashed = false,
                )
            }
            Text(definition.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(definition.invite, Modifier.padding(top = 3.dp),
                color = Color.White.copy(alpha = .82f), fontSize = 11.sp,
                lineHeight = 15.sp)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PoseCatalog.poses.forEach { pose ->
                    val active = pose.id == definition.id
                    Box(Modifier.padding(horizontal = 4.dp).size(8.dp)
                        .clip(CircleShape)
                        .background(if (active) Color(0xFFFFB36B) else Color.White.copy(alpha = .30f)))
                }
            }
            Box(
                Modifier.padding(top = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = .14f))
                    .clickable(role = Role.Button) { onNextPose() }
                    .semantics {
                        contentDescription = "下一个姿势：${definition.title}"
                        role = Role.Button
                    }
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            ) {
                Text("换个姿势", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

// ---- drawing ------------------------------------------------------------------

private val DRAWN_JOINTS = setOf(
    PoseJoint.NOSE,
    PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER,
    PoseJoint.LEFT_ELBOW, PoseJoint.RIGHT_ELBOW,
    PoseJoint.LEFT_WRIST, PoseJoint.RIGHT_WRIST,
    PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP,
    PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE,
    PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE,
)

/**
 * Draws the MediaPipe edge subset plus a nose-to-shoulder-mid head line. [normalised]
 * observed/projected points multiply the canvas size; demo projections are pixel space.
 */
private fun DrawScope.drawSkeleton(
    points: Map<Int, PosePoint>,
    normalised: Boolean,
    visible: (Int) -> Boolean,
    color: Color,
    jointRadius: Float,
    strokeWidth: Float,
    dashed: Boolean,
) {
    fun place(point: PosePoint): Offset = if (normalised) {
        Offset(point.x.coerceIn(-.1f, 1.1f) * size.width, point.y.coerceIn(-.1f, 1.1f) * size.height)
    } else {
        Offset(point.x, point.y)
    }
    fun at(index: Int): PosePoint? = points[index]?.takeIf { visible(index) }
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) else null
    fun edge(first: Int, second: Int) {
        val a = at(first) ?: return
        val b = at(second) ?: return
        drawLine(color, place(a), place(b), strokeWidth, cap = StrokeCap.Round, pathEffect = effect)
    }
    PoseJoint.SKELETON_EDGES.forEach { (first, second) -> edge(first, second) }
    // Head line: nose to the shoulder midpoint (not a MediaPipe connection of its own).
    val nose = at(PoseJoint.NOSE)
    val ls = at(PoseJoint.LEFT_SHOULDER)
    val rs = at(PoseJoint.RIGHT_SHOULDER)
    if (nose != null && ls != null && rs != null) {
        drawLine(color, place(nose), place(midpoint(ls, rs)), strokeWidth,
            cap = StrokeCap.Round, pathEffect = effect)
    }
    DRAWN_JOINTS.forEach { index ->
        val point = at(index) ?: return@forEach
        drawCircle(color, jointRadius, place(point))
    }
}

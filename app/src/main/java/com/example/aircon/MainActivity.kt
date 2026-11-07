package com.example.aircon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

// ---------- Model ----------
enum class Mode(val label: String, val icon: String) {
    AUTO("Auto", "✦"), COOL("Cool", "❄"), DRY("Dry", "💧"), HEAT("Heat", "☀"), FAN("Fan", "🌀")
}
enum class Fan(val label: String) { AUTO("Auto"), LOW("Low"), MED("Medium"), HIGH("High"), TURBO("Turbo") }

data class AcState(
    val power: Boolean = true, val mode: Mode = Mode.COOL, val target: Float = 24f, val room: Float = 27.5f,
    val humidity: Int = 48, val fan: Fan = Fan.AUTO, val swingV: Boolean = false, val swingH: Boolean = false,
    val sleep: Boolean = false, val curve: Int = 2, val health: Boolean = false, val clean: Boolean = false,
    val display: Boolean = true, val timerOff: Int = 0,
)

/** Sleep curves: how far the temperature drifts from your setting, hour by hour over 8 hours. */
val CURVES = listOf(
    "Steady" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Light" to listOf(0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f),
    "Standard" to listOf(0f, 1f, 2f, 2f, 2f, 2f, 1f, 0f),
)

/** The only thing that touches the air conditioner. Swap DemoBackend for a real one (AUX Cloud or LAN). */
interface AcBackend { suspend fun read(): AcState; suspend fun write(s: AcState) }
class DemoBackend : AcBackend {
    private var s = AcState()
    override suspend fun read() = s
    override suspend fun write(s: AcState) { this.s = s }
}

// ---------- Look ----------
fun palette(s: AcState): List<Color> = if (!s.power)
    listOf(Color(0xFF0E1116), Color(0xFF2A3240), Color(0xFF1B2230), Color(0xFF313A4A))
else when (s.mode) {
    Mode.COOL -> listOf(Color(0xFF04122B), Color(0xFF00B4F0), Color(0xFF3A6BFF), Color(0xFF00E0C0))
    Mode.HEAT -> listOf(Color(0xFF2A0A0F), Color(0xFFFF6A2B), Color(0xFFFF2E6E), Color(0xFFFFB84D))
    Mode.DRY -> listOf(Color(0xFF12082B), Color(0xFF8A5CFF), Color(0xFFFF5CC8), Color(0xFF4D7CFF))
    Mode.FAN -> listOf(Color(0xFF06231F), Color(0xFF2BD98A), Color(0xFF00B8C4), Color(0xFFB8F05C))
    Mode.AUTO -> listOf(Color(0xFF160B2E), Color(0xFFB04DFF), Color(0xFF2E8BFF), Color(0xFFFF8A4D))
}

val LocalBg = compositionLocalOf<@Composable (Modifier) -> Unit> { { } }
val LocalScreen = compositionLocalOf { IntSize.Zero }

@Composable
fun Aurora(phase: State<Float>, c: List<Color>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(c[0])
        val t = phase.value * 2f * PI.toFloat()
        for (i in 1 until c.size) {
            val kx = 1 + i % 2
            val ky = 2 - i % 2
            val cx = size.width * (0.5f + 0.42f * sin(t * kx + i * 2.1f))
            val cy = size.height * (0.5f + 0.42f * cos(t * ky + i * 1.3f))
            val r = size.maxDimension * (0.55f + 0.1f * sin(t + i))
            drawCircle(Brush.radialGradient(listOf(c[i], Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
        }
    }
}

/** Frosted glass: draws its own blurred copy of the live wallpaper, aligned to the screen, so it glides with it. */
@Composable
fun Glass(modifier: Modifier = Modifier, radius: Dp = 28.dp, content: @Composable BoxScope.() -> Unit) {
    val bg = LocalBg.current
    val screen = LocalScreen.current
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).onGloballyPositioned {
        val p = it.positionInRoot(); pos = IntOffset(p.x.roundToInt(), p.y.roundToInt())
    }) {
        if (screen.width > 0) Box(Modifier.matchParentSize().blur(32.dp)) {
            bg(Modifier.layout { m, c ->
                val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(screen.width, screen.height))
                layout(c.maxWidth, c.maxHeight) { p.place(-pos.x, -pos.y) }
            })
        }
        Box(
            Modifier.matchParentSize()
                .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x1F000000))))
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(.55f), Color.White.copy(.06f))), shape)
        )
        content()
    }
}

@Composable
fun Choice(icon: String, label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val fg = if (on) Color(0xFF101828) else Color.White
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(if (on) Color.White.copy(.92f) else Color.White.copy(.08f))
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp, color = fg)
        Text(label, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Pill(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(if (on) Color.White.copy(.92f) else Color.White.copy(.08f))
            .clickable(onClick = onClick).padding(vertical = 13.dp, horizontal = 16.dp),
        Alignment.Center
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color(0xFF101828) else Color.White) }
}

@Composable
fun RoundBtn(label: String, size: Dp = 56.dp, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(.14f))
            .border(1.dp, Color.White.copy(.3f), CircleShape).clickable(onClick = onClick),
        Alignment.Center
    ) { Text(label, fontSize = 22.sp, color = if (filled) Color(0xFF101828) else Color.White, fontWeight = FontWeight.Medium) }
}

class Feat(val label: String, val on: Boolean, val toggle: (AcState) -> AcState)

fun fmt(v: Float) = if (v % 1f == 0f) "${v.toInt()}" else "${v.toInt()}.5"

// ---------- Screen ----------
@Composable
fun App(backend: AcBackend) {
    var st by remember { mutableStateOf(AcState()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { st = backend.read(); delay(5000) } }
    fun set(n: AcState) { st = n; scope.launch { backend.write(n) } }

    LaunchedEffect(st.timerOff > 0) {
        while (st.timerOff > 0) {
            delay(60_000)
            if (st.timerOff > 1) set(st.copy(timerOff = st.timerOff - 1)) else set(st.copy(timerOff = 0, power = false))
        }
    }

    val cols = palette(st).map { animateColorAsState(it, tween(1200), label = "c").value }
    val phase = rememberInfiniteTransition(label = "p")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "ph")
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val bg: @Composable (Modifier) -> Unit = { m -> Aurora(phase, cols, m) }

    val verb = when (st.mode) { Mode.COOL -> "cooling to"; Mode.HEAT -> "heating to"; Mode.DRY -> "drying toward"; Mode.AUTO -> "holding"; Mode.FAN -> "moving air at" }
    val feats = listOf(
        Feat("Sleep", st.sleep) { it.copy(sleep = !it.sleep) }, Feat("Health", st.health) { it.copy(health = !it.health) },
        Feat("Self clean", st.clean) { it.copy(clean = !it.clean) }, Feat("Unit display", st.display) { it.copy(display = !it.display) },
    )

    CompositionLocalProvider(LocalBg provides bg, LocalScreen provides screen) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { screen = it.size }) {
            bg(Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.padding(start = 6.dp, top = 8.dp)) {
                    Text("Air conditioner", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (st.power) "Room ${fmt(st.room)}° and ${st.humidity}% humidity, $verb ${fmt(st.target)}°" else "Off, room ${fmt(st.room)}° and ${st.humidity}% humidity",
                        color = Color.White.copy(.75f), fontSize = 15.sp
                    )
                }

                Glass(Modifier.fillMaxWidth(), 40.dp) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(250.dp), Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val sw = 14.dp.toPx()
                                val tl = Offset(sw / 2, sw / 2)
                                val sz = Size(size.width - sw, size.height - sw)
                                drawArc(Color.White.copy(.18f), 135f, 270f, false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                                if (st.power) drawArc(Color.White, 135f, 270f * ((st.target - 16f) / 16f).coerceIn(0.02f, 1f), false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (st.power) "${fmt(st.target)}°" else "Off", color = Color.White, fontSize = 68.sp, fontWeight = FontWeight.ExtraLight)
                                Text(st.mode.label, color = Color.White.copy(.8f), fontSize = 16.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundBtn("−") { set(st.copy(target = max(16f, st.target - .5f), power = true)) }
                            RoundBtn(if (st.power) "On" else "Off", 76.dp, st.power) { set(st.copy(power = !st.power)) }
                            RoundBtn("+") { set(st.copy(target = min(32f, st.target + .5f), power = true)) }
                        }
                    }
                }

                Glass(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Mode.entries.forEach { m -> Choice(m.icon, m.label, st.mode == m, Modifier.weight(1f)) { set(st.copy(mode = m, power = true)) } }
                    }
                }

                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Fan speed", color = Color.White.copy(.8f), fontSize = 14.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Fan.entries.forEach { f -> Pill(f.label, st.fan == f) { set(st.copy(fan = f)) } }
                        }
                        Text("Airflow", color = Color.White.copy(.8f), fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Swing up and down", st.swingV, Modifier.weight(1f)) { set(st.copy(swingV = !st.swingV)) }
                            Pill("Swing left and right", st.swingH, Modifier.weight(1f)) { set(st.copy(swingH = !st.swingH)) }
                        }
                    }
                }

                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (st.timerOff > 0) "Turns off in ${st.timerOff / 60}h ${st.timerOff % 60}m" else "Turn off after",
                            color = Color.White.copy(.8f), fontSize = 14.sp
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "No timer", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", 480 to "8 hours").forEach { (m, l) ->
                                Pill(l, m == 0 && st.timerOff == 0) { set(st.copy(timerOff = m)) }
                            }
                        }
                    }
                }

                Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Comfort and care", color = Color.White.copy(.8f), fontSize = 14.sp)
                        feats.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { f -> Pill(f.label, f.on, Modifier.weight(1f)) { set(f.toggle(st)) } }
                            }
                        }
                    }
                }

                if (st.sleep) Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sleep curve over 8 hours", color = Color.White.copy(.8f), fontSize = 14.sp)
                        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                            val pts = CURVES[st.curve].second
                            val step = size.width / (pts.size - 1)
                            val pad = 6.dp.toPx()
                            val path = androidx.compose.ui.graphics.Path()
                            pts.forEachIndexed { i, v ->
                                val x = i * step
                                val y = size.height - pad - (v / 2f) * (size.height - 2 * pad)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, Color.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CURVES.forEachIndexed { i, c -> Pill(c.first, st.curve == i, Modifier.weight(1f)) { set(st.copy(curve = i)) } }
                        }
                    }
                }

                Text(
                    "Demo mode: changes are not sent to your air conditioner yet.",
                    color = Color.White.copy(.55f), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp)
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val backend = DemoBackend()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { App(backend) }
    }
}

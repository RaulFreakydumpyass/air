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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.SharedPreferences
import android.util.Base64
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.launch
import kotlin.math.*

// ---------- Model ----------
enum class Mode(val label: String, val icon: String) {
    AUTO("Auto", "✦"), COOL("Cool", "❄"), DRY("Dry", "💧"), HEAT("Heat", "☀"), FAN("Fan", "🌀")
}
enum class Fan(val label: String) { AUTO("Auto"), LOW("Low"), MED("Medium"), HIGH("High"), TURBO("Turbo") }

data class AcState(
    val power: Boolean = true, val mode: Mode = Mode.COOL, val target: Float = 24f, val room: Float = 27.5f,
    val humidity: Int? = 48, val fan: Fan = Fan.AUTO, val swingV: Boolean = false, val swingH: Boolean = false,
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
fun App(backend: AcBackend?, onLogin: (String, String, String) -> Unit, onLogout: () -> Unit) {
    var st by remember(backend) { mutableStateOf(AcState()) }
    var err by remember(backend) { mutableStateOf<String?>(null) }
    var ready by remember(backend) { mutableStateOf(false) }
    var touched by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(backend) {
        val b = backend ?: return@LaunchedEffect
        while (true) {
            try {
                val r = b.read()
                if (System.currentTimeMillis() - touched > 8000) st = r.copy(timerOff = st.timerOff, curve = st.curve)
                ready = true; err = null
            } catch (e: Exception) {
                err = e.message ?: e.toString()
                if (e is AuxError && e.bad) return@LaunchedEffect
            }
            delay(5000)
        }
    }
    fun set(n: AcState) {
        st = n; touched = System.currentTimeMillis()
        val b = backend ?: return
        scope.launch { try { b.write(n); err = null } catch (e: Exception) { err = e.message ?: e.toString() } }
    }

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
    val hum = st.humidity?.let { " and $it% humidity" } ?: ""
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
                        if (backend == null) "Sign in to control your AC" else if (!ready) "Connecting..." else if (st.power) "Room ${fmt(st.room)}°$hum, $verb ${fmt(st.target)}°" else "Off, room ${fmt(st.room)}°$hum",
                        color = Color.White.copy(.75f), fontSize = 15.sp
                    )
                }

                if (backend == null) LoginCard(onLogin)
                else if (!ready) Glass(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (err == null) "Connecting to your AC..." else "Could not connect: $err", color = Color.White, fontSize = 15.sp)
                        Pill("Log out", false, Modifier.fillMaxWidth()) { onLogout() }
                    }
                } else {
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
                        Text("Sleep curve preview (not sent to the AC)", color = Color.White.copy(.8f), fontSize = 14.sp)
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

                }
                if (backend != null && ready) Text(
                    if (backend is DemoBackend) "Demo mode: changes are not sent to your air conditioner."
                    else if (err != null) "Connection problem: $err" else "Connected through AUX cloud",
                    color = Color.White.copy(.6f), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)
                )
                if (backend != null && ready) Pill("Log out", false, Modifier.fillMaxWidth()) { onLogout() }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun Field(value: String, hint: String, type: KeyboardType, secret: Boolean, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(.12f)).padding(horizontal = 16.dp, vertical = 14.dp)) {
        if (value.isEmpty()) Text(hint, color = Color.White.copy(.5f), fontSize = 16.sp)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White), keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun LoginCard(onLogin: (String, String, String) -> Unit) {
    var region by remember { mutableStateOf("eu") }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Glass(Modifier.fillMaxWidth(), 32.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sign in with your AC Freedom account", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text("Region of your account", color = Color.White.copy(.8f), fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("eu" to "Europe", "usa" to "USA", "rus" to "Russia", "cn" to "China").forEach { (k, l) -> Pill(l, region == k) { region = k } }
            }
            Field(email, "Email", KeyboardType.Email, false) { email = it }
            Field(pw, "Password", KeyboardType.Password, true) { pw = it }
            Pill("Connect", true, Modifier.fillMaxWidth()) { if (email.isNotBlank() && pw.isNotEmpty()) onLogin(region, email, pw) }
            Pill("Try demo (no AC)", false, Modifier.fillMaxWidth()) { onLogin("demo", "", "") }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val prefs = getSharedPreferences("air", MODE_PRIVATE)
        setContent {
            var backend by remember { mutableStateOf(load(prefs)) }
            App(
                backend,
                onLogin = { region, email, pw ->
                    if (region == "demo") backend = DemoBackend() else {
                        prefs.edit().putString("region", region).putString("email", email).putString("pw", pw).apply()
                        backend = AuxCloud(region, email, pw)
                    }
                },
                onLogout = { prefs.edit().clear().apply(); backend = null }
            )
        }
    }

    private fun load(p: SharedPreferences): AcBackend? {
        val e = p.getString("email", null) ?: return null
        return AuxCloud(p.getString("region", "eu") ?: "eu", e, p.getString("pw", "") ?: "")
    }
}

// ---------- AUX cloud (the same service the AC Freedom app uses) ----------
/*
 * The login, request signing and device control below are ported from the open-source
 * project ha-aux-cloud (https://github.com/maeek/ha-aux-cloud), used under its MIT license:
 *
 * MIT License
 *
 * Copyright (c) 2026 maeek
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
private const val TS_KEY = "kdixkdqp54545^#*"
private const val PW_KEY = "4969fj#k23#"
private const val BODY_KEY = "xgx3d*fe3478\$ukx"
private const val LICENSE = "PAFbJJ3WbvDxH5vvWezXN5BujETtH/iuTtIIW5CE/SeHN7oNKqnEajgljTcL0fBQQWM0XAAAAAAnBhJyhMi7zIQMsUcwR/PEwGA3uB5HLOnr+xRrci+FwHMkUtK7v4yo0ZHa+jPvb6djelPP893k7SagmffZmOkLSOsbNs8CAqsu8HuIDs2mDQAAAAA="
private const val LICENSE_ID = "3c015b249dd66ef0f11f9bef59ecd737"
private const val COMPANY_ID = "48eb1b36cf0202ab2ef07b880ecda60d"
private val IV = byteArrayOf(-22, -86, -86, 58, -69, 88, 98, -94, 25, 24, -75, 119, 29, 22, 21, -86)
private val HALF_IDS = setOf("0000000000000000000000001f620000", "00000000000000000000000045620000", "000000000000000000000000c9100100")
private val AC_IDS = HALF_IDS + setOf(
    "000000000000000000000000c0620000", "0000000000000000000000002a4e0000", "0000000000000000000000007faf0000",
    "00000000000000000000000082af0000", "00000000000000000000000028620000", "000000000000000000000000c5510000",
    "00000000000000000000000056ac0000", "000000000000000000000000a44e0000"
)

class AuxError(val code: Int?, msg: String) : Exception(msg) {
    val bad get() = code in setOf(-1006, -1008, -1020, -1033, -1035, -1037)
    val expired get() = code in setOf(-30129, -1000, -1009, -1012, -3003, -49009, 10011)
}

private fun msgFor(c: Int) = when (c) {
    -1006, -1008, -1020, -1033, -1035, -1037 -> "Email or password is wrong (or try another region)"
    -30129, -1000, -1009, -1012, -3003, -49009, 10011 -> "Login expired"
    -3 -> "The AC is offline or unavailable"
    -7 -> "The AC refused the command"
    -1036, -2001 -> "Too many requests, try again in a minute"
    else -> "AUX cloud error $c"
}

class AuxCloud(region: String, private val email: String, private val password: String) : AcBackend {
    private val base = mapOf(
        "eu" to "https://app-service-deu-f0e9ebbb.smarthomecs.de",
        "usa" to "https://app-service-usa-fd7cc04c.smarthomecs.com",
        "cn" to "https://app-service-chn-31a93883.ibroadlink.com",
        "rus" to "https://app-service-rus-b8bbc3be.smarthomecs.com"
    )[region] ?: "https://app-service-deu-f0e9ebbb.smarthomecs.de"
    private var session: String? = null
    private var user = ""
    private var device: JSONObject? = null
    private var last: AcState? = null
    private val mutex = Mutex()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())

    private fun errCode(j: JSONObject): Int? {
        fun chk(o: JSONObject?): Int? {
            val v = o?.opt("status") ?: return null
            val n = (v as? Number)?.toInt() ?: v.toString().toIntOrNull()
            return if (n == null || n == 0) null else n
        }
        return chk(j) ?: chk(j.optJSONObject("payload")) ?: chk(j.optJSONObject("event")?.optJSONObject("payload"))
    }

    private fun post(endpoint: String, body: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap()): JSONObject {
        val c = URL("$base/$endpoint").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 15000; c.doOutput = true
            val h = mapOf(
                "Content-Type" to "application/x-java-serialized-object", "licenseId" to LICENSE_ID, "lid" to LICENSE_ID,
                "language" to "en", "appVersion" to "2.2.10.456537160",
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 12; SM-G991B Build/SP1A.210812.016)",
                "system" to "android", "appPlatform" to "android", "loginsession" to (session ?: ""), "userid" to user
            ) + extra
            h.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body) }
            val status = c.responseCode
            if (status == 401 || status == 403) throw AuxError(-1000, "Login expired")
            val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (status >= 400) throw AuxError(null, "AUX cloud answered HTTP $status")
            val json = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (e: Exception) { throw AuxError(null, "AUX cloud sent an unreadable answer") }
            errCode(json)?.let { throw AuxError(it, msgFor(it)) }
            return json
        } finally { c.disconnect() }
    }

    private fun login() {
        val ms = System.currentTimeMillis()
        val ts = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
        val json = JSONObject().put("email", email.trim().lowercase())
            .put("password", hex(MessageDigest.getInstance("SHA-1").digest((password + PW_KEY).toByteArray())))
            .put("companyid", COMPANY_ID).put("lid", LICENSE_ID).toString()
        val token = hex(md5(json + BODY_KEY))
        val plain = json.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(md5(ts + TS_KEY), "AES"), IvParameterSpec(IV))
        val enc = cipher.doFinal(plain + ByteArray(16 - plain.size % 16))
        val r = post("account/login", enc, mapOf("timestamp" to ts, "token" to token))
        session = r.optString("loginsession"); user = r.optString("userid")
        if (session.isNullOrEmpty() || user.isEmpty()) { session = null; throw AuxError(null, "Login failed") }
    }

    private fun <T> guarded(block: () -> T): T = try {
        if (session == null) login()
        try { block() } catch (e: AuxError) { if (e.expired) { login(); block() } else throw e }
    } catch (e: org.json.JSONException) { throw AuxError(null, "Unexpected data from AUX cloud: ${e.message}") }

    private fun findDevice(): JSONObject {
        device?.let { return it }
        val fams = post("appsync/group/member/getfamilylist").getJSONObject("data").getJSONArray("familyList")
        var first: JSONObject? = null
        for (i in 0 until fams.length()) {
            val fid = fams.getJSONObject(i).getString("familyid")
            val r = post("appsync/group/dev/query?action=select", "{\"pids\":[]}".toByteArray(), mapOf("familyid" to fid))
            val eps = r.optJSONObject("data")?.optJSONArray("endpoints") ?: continue
            for (j in 0 until eps.length()) {
                val d = eps.getJSONObject(j)
                if (first == null) first = d
                if (d.optString("productId") in AC_IDS) { device = d; return d }
            }
        }
        device = first ?: throw AuxError(null, "No air conditioner found in this account")
        return device!!
    }

    private fun parseStd(data: Any?): Map<String, Any?> {
        val o = when (data) { is String -> if (data.isBlank()) return emptyMap() else JSONObject(data); is JSONObject -> data; else -> return emptyMap() }
        val out = HashMap<String, Any?>()
        if (!o.has("params") || !o.has("vals")) { o.keys().forEach { out[it] = o.opt(it) }; return out }
        val ps = o.getJSONArray("params"); val vs = o.getJSONArray("vals")
        for (i in 0 until ps.length()) out[ps.getString(i)] = vs.optJSONArray(i)?.optJSONObject(0)?.opt("val")
        return out
    }

    private fun call(d: JSONObject, act: String, params: List<String>, vals: List<Any>?): Map<String, Any?> {
        val raw = d.getString("cookie")
        val ck = JSONObject(String(Base64.decode(raw + "=".repeat((4 - raw.length % 4) % 4), Base64.DEFAULT)))
        val aes = ck.getString("aeskey"); val eid = d.getString("endpointId"); val pid = d.getString("productId")
        val mac = d.getString("mac"); val ses = d.getString("devSession")
        val mapped = Base64.encodeToString(
            JSONObject().put("device", JSONObject().put("id", ck.get("terminalid")).put("key", aes).put("devSession", ses)
                .put("aeskey", aes).put("did", eid).put("pid", pid).put("mac", mac)).toString().toByteArray(), Base64.NO_WRAP
        )
        val now = (System.currentTimeMillis() / 1000).toString()
        val v = if (act == "get" && params.size == 1) JSONArray().put(JSONArray().put(JSONObject().put("val", 0).put("idx", 1)))
        else JSONArray().also { a -> vals?.forEach { a.put(JSONArray().put(JSONObject().put("idx", 1).put("val", it))) } }
        val dir = JSONObject()
            .put("header", JSONObject().put("namespace", "DNA.KeyValueControl").put("name", "KeyValueControl").put("interfaceVersion", "2")
                .put("senderId", "sdk").put("messageId", "$eid-$now").put("timstamp", now))
            .put("endpoint", JSONObject().put("devicePairedInfo", JSONObject().put("did", eid).put("pid", pid).put("mac", mac)
                .put("devicetypeflag", d.get("devicetypeFlag")).put("cookie", mapped))
                .put("endpointId", eid).put("cookie", JSONObject()).put("devSession", ses))
            .put("payload", JSONObject().put("act", act).put("params", JSONArray(params)).put("vals", v).put("did", eid))
        val r = post("device/control/v2/sdkcontrol?license=" + URLEncoder.encode(LICENSE, "UTF-8"), JSONObject().put("directive", dir).toString().toByteArray())
        val ev = r.optJSONObject("event") ?: throw AuxError(null, "AUX cloud sent no answer")
        if (ev.optJSONObject("header")?.optString("name") != "Response") throw AuxError(null, "Unexpected answer from AUX cloud")
        return parseStd(ev.optJSONObject("payload")?.opt("data"))
    }

    private fun toState(p: Map<String, Any?>): AcState {
        fun i(k: String) = (p[k] as? Number)?.toInt()
        val pid = device?.optString("productId") ?: ""
        var t = i("temp") ?: 240
        if (pid in HALF_IDS && i("ac_tempdec") == 1) t = (t / 10) * 10 + 5
        return AcState(
            power = i("pwr") == 1,
            mode = when (i("ac_mode")) { 1 -> Mode.HEAT; 2 -> Mode.DRY; 3 -> Mode.FAN; 4 -> Mode.AUTO; else -> Mode.COOL },
            target = t / 10f, room = (i("envtemp") ?: 0) / 10f, humidity = null,
            fan = when (i("ac_mark")) { 1, 5, 6 -> Fan.LOW; 2 -> Fan.MED; 3, 7 -> Fan.HIGH; 4 -> Fan.TURBO; else -> Fan.AUTO },
            swingV = i("ac_vdir") == 1, swingH = i("ac_hdir") == 1, sleep = i("ac_slp") == 1,
            health = i("ac_health") == 1, clean = i("ac_clean") == 1, display = i("scrdisp") != 0
        )
    }

    override suspend fun read(): AcState = mutex.withLock {
        withContext(Dispatchers.IO) { guarded { val d = findDevice(); toState(call(d, "get", emptyList(), null)).also { last = it } } }
    }

    override suspend fun write(s: AcState) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                guarded {
                    val d = findDevice()
                    val prev = last ?: toState(call(d, "get", emptyList(), null))
                    val v = LinkedHashMap<String, Any>()
                    if (s.power != prev.power) v["pwr"] = if (s.power) 1 else 0
                    if (s.mode != prev.mode) v["ac_mode"] = when (s.mode) { Mode.COOL -> 0; Mode.HEAT -> 1; Mode.DRY -> 2; Mode.FAN -> 3; Mode.AUTO -> 4 }
                    if (s.target != prev.target) {
                        val x10 = Math.round(s.target * 10)
                        if (d.optString("productId") in HALF_IDS) { val b = (x10 / 10) * 10; v["temp"] = b; v["ac_tempdec"] = if (x10 - b >= 5) 1 else 0 }
                        else v["temp"] = x10
                    }
                    if (s.fan != prev.fan) v["ac_mark"] = when (s.fan) { Fan.AUTO -> 0; Fan.LOW -> 1; Fan.MED -> 2; Fan.HIGH -> 3; Fan.TURBO -> 4 }
                    if (s.swingV != prev.swingV) v["ac_vdir"] = if (s.swingV) 1 else 0
                    if (s.swingH != prev.swingH) v["ac_hdir"] = if (s.swingH) 1 else 0
                    if (s.sleep != prev.sleep) v["ac_slp"] = if (s.sleep) 1 else 0
                    if (s.health != prev.health) v["ac_health"] = if (s.health) 1 else 0
                    if (s.clean != prev.clean) v["ac_clean"] = if (s.clean) 1 else 0
                    if (s.display != prev.display) v["scrdisp"] = if (s.display) 1 else 0
                    if (v.isNotEmpty()) call(d, "set", v.keys.toList(), v.values.toList())
                    last = s
                }
            }
        }
    }
}

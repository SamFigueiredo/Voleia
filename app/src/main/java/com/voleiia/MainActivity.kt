package com.voleiia

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VoleiIA"
    }

    private lateinit var prefs: Prefs
    private lateinit var engine: ScoreEngine
    private lateinit var detector: BallDetector
    private val tracker = BallTracker()
    private val replay = ReplayBuffer()

    @Volatile private var calibration: CourtCalibration? = null
    @Volatile private var frameW = 1280
    @Volatile private var frameH = 720
    @Volatile private var lastFrameTMs = 0L
    @Volatile private var aiPaused = false
    @Volatile private var lastStatusMs = 0L

    private val camExecutor = Executors.newSingleThreadExecutor()
    private val detExecutor = Executors.newSingleThreadExecutor()
    private val detBusy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var tvLeftName: TextView
    private lateinit var tvLeftPts: TextView
    private lateinit var tvRightName: TextView
    private lateinit var tvRightPts: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBanner: TextView
    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var confirmPanel: View
    private lateinit var tvConfirm: TextView
    private lateinit var btnConfirmA: Button
    private lateinit var btnConfirmB: Button
    private lateinit var replayLayer: View
    private lateinit var ivReplay: ImageView
    private lateinit var tvReplayLabel: TextView

    // Estado de UI
    private val calibTaps = ArrayList<PointF>()
    private var replayClip: List<ReplayBuffer.Frame> = emptyList()
    private var replayIdx = 0
    private var lastReplayWindow: Pair<Long, Long>? = null
    private var pendingEvent: LandingEvent? = null
    private val hideBannerRunnable = Runnable { tvBanner.visibility = View.GONE }
    private val hideConfirmRunnable = Runnable { hideConfirm() }
    private val replayStep = Runnable { showReplayFrame() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else showBanner("Sem permissão da câmera. Conceda nas configurações do app.", null)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        prefs = Prefs(this)
        engine = ScoreEngine(prefs.matchConfig())
        detector = BallDetector(this)

        previewView = findViewById(R.id.previewView)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        tvLeftName = findViewById(R.id.tvLeftName)
        tvLeftPts = findViewById(R.id.tvLeftPts)
        tvRightName = findViewById(R.id.tvRightName)
        tvRightPts = findViewById(R.id.tvRightPts)
        tvInfo = findViewById(R.id.tvInfo)
        tvStatus = findViewById(R.id.tvStatus)
        tvBanner = findViewById(R.id.tvBanner)
        btnLeft = findViewById(R.id.btnLeft)
        btnRight = findViewById(R.id.btnRight)
        confirmPanel = findViewById(R.id.confirmPanel)
        tvConfirm = findViewById(R.id.tvConfirm)
        btnConfirmA = findViewById(R.id.btnConfirmA)
        btnConfirmB = findViewById(R.id.btnConfirmB)
        replayLayer = findViewById(R.id.replayLayer)
        ivReplay = findViewById(R.id.ivReplay)
        tvReplayLabel = findViewById(R.id.tvReplayLabel)

        overlay.showDebug = prefs.debug
        prefs.calib?.let { applyCalibration(it) }

        btnLeft.setOnClickListener { manualPoint(engine.teamOnSide(Side.LEFT)) }
        btnRight.setOnClickListener { manualPoint(engine.teamOnSide(Side.RIGHT)) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            if (engine.undo()) {
                updateScoreboard()
                showBanner("Último ponto desfeito", 2000)
            }
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.btnIgnore).setOnClickListener { hideConfirm() }
        replayLayer.setOnClickListener { endReplay() }
        overlay.onCalibTap = { nx, ny -> onCalibTap(nx, ny) }

        updateScoreboard()
        updateStatus()
        if (!detector.available) downloadModelIfMissing()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ Câmera + IA

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val resSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(resSel).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(camExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir câmera", e)
                showBanner("Falha ao abrir a câmera: ${e.message}", null)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Thread da câmera: guarda frame no buffer de replay e entrega ao detector se ele estiver livre. */
    private fun analyze(proxy: ImageProxy) {
        try {
            val tMs = proxy.imageInfo.timestamp / 1_000_000L
            var bmp = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            if (bmp.width != frameW || bmp.height != frameH) {
                frameW = bmp.width
                frameH = bmp.height
                overlay.frameW = frameW
                overlay.frameH = frameH
                runOnUiThread { applyNetLine() }
            }
            lastFrameTMs = tMs
            replay.add(tMs, bmp)

            val cal = calibration
            if (cal != null && detector.available && !aiPaused && detBusy.compareAndSet(false, true)) {
                val frame = bmp
                detExecutor.execute {
                    try {
                        detectStep(frame, tMs, cal)
                    } catch (e: Exception) {
                        Log.e(TAG, "detectStep", e)
                    } finally {
                        detBusy.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze", e)
        } finally {
            proxy.close()
        }
    }

    /** Thread do detector: acha a bola, atualiza a trilha e detecta o pouso. */
    private fun detectStep(bmp: Bitmap, tMs: Long, cal: CourtCalibration) {
        if (!cal.prepare(bmp.width, bmp.height)) return
        val t0 = SystemClock.elapsedRealtime()
        val scale = bmp.width / 1280f
        val pred = tracker.predict(tMs)

        val raw = ArrayList<Candidate>()
        if (pred != null) {
            // Já está rastreando: procura só ao redor da posição prevista (em resolução nativa).
            raw += detector.detect(bmp, squareAround(pred.x, pred.y, (384 * scale).toInt(), bmp.width, bmp.height))
        } else {
            // Procurando: varre o quadro todo com 2 recortes quadrados (bola pequena precisa de zoom).
            val side = min(bmp.height, bmp.width)
            raw += detector.detect(bmp, Rect(0, 0, side, side))
            raw += detector.detect(bmp, Rect(bmp.width - side, 0, bmp.width, side))
        }
        val cands = raw.filter { it.w in 5f * scale..110f * scale && it.h in 5f * scale..110f * scale }
        val event = tracker.update(tMs, cands, cal, bmp.width)
        val inferMs = SystemClock.elapsedRealtime() - t0

        overlay.trail = tracker.snapshotTrail()
        overlay.ball = tracker.lastObs()?.takeIf { it.tMs == tMs }
        overlay.ballState = tracker.state()
        overlay.candidates = cands
        overlay.postInvalidate()

        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusMs > 250) {
            lastStatusMs = now
            runOnUiThread { updateStatus(inferMs) }
        }
        if (event != null) runOnUiThread { handleLanding(event) }
    }

    private fun squareAround(cx: Float, cy: Float, size: Int, w: Int, h: Int): Rect {
        val s = min(size, min(w, h))
        val l = (cx - s / 2f).toInt().coerceIn(0, w - s)
        val t = (cy - s / 2f).toInt().coerceIn(0, h - s)
        return Rect(l, t, l + s, t + s)
    }

    // ------------------------------------------------------------------ Pontos

    private fun teamName(t: Team) = if (t == Team.A) prefs.teamA else prefs.teamB

    private fun handleLanding(e: LandingEvent) {
        tracker.resetRally()
        if (engine.state.finished || confirmPanel.visibility == View.VISIBLE) return
        val team = engine.teamOnSide(e.scoringSide)
        if (e.confidence >= prefs.confThreshold) {
            registerPoint(team, e.tMs, e.crossings, e.rallyStartMs, "IA ${(e.confidence * 100).toInt()}%")
        } else {
            showConfirm(e, team)
        }
    }

    private fun manualPoint(team: Team) {
        val crossings = tracker.crossings
        val rallyStart = tracker.rallyStartMs
        tracker.resetRally()
        registerPoint(team, lastFrameTMs, crossings, rallyStart, "manual")
    }

    private fun registerPoint(team: Team, tMs: Long, crossings: Int, rallyStartMs: Long, note: String) {
        val res = engine.addPoint(team) ?: return
        updateScoreboard()
        val sb = StringBuilder("Ponto ${teamName(team)} ($note)")
        if (res.setWonBy != null && res.matchWonBy == null) sb.append("\nSet para ${teamName(res.setWonBy)}!")
        if (res.sideSwitch) sb.append("\nTroca de lados!")
        showBanner(sb.toString(), 4000)

        if (res.matchWonBy != null) {
            AlertDialog.Builder(this)
                .setTitle("Fim de jogo!")
                .setMessage("${teamName(res.matchWonBy)} venceu a partida.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Novo jogo") { _, _ -> newGame() }
                .show()
        }

        if (prefs.replayEnabled && (prefs.replayMinCrossings == 0 || crossings >= prefs.replayMinCrossings)) {
            val before = prefs.replayBeforeSec * 1000L
            val from = if (rallyStartMs > 0) maxOf(tMs - before, rallyStartMs - 1000L) else tMs - before
            val to = tMs + 1500L
            lastReplayWindow = from to to
            // Espera 1,5 s para ter os quadros logo após o lance.
            handler.postDelayed({ startReplay(from, to) }, 1500L)
        }
    }

    private fun showConfirm(e: LandingEvent, suggested: Team) {
        pendingEvent = e
        val lado = if (e.landingSide == Side.LEFT) "esquerdo" else "direito"
        val onde = if (e.inside) "dentro" else "FORA"
        tvConfirm.text = "Bola no lado $lado ($onde) • confiança ${(e.confidence * 100).toInt()}%\n" +
            "Sugestão: ponto ${teamName(suggested)}"
        btnConfirmA.text = (if (suggested == Team.A) "★ " else "") + "Ponto ${prefs.teamA}"
        btnConfirmB.text = (if (suggested == Team.B) "★ " else "") + "Ponto ${prefs.teamB}"
        btnConfirmA.setOnClickListener { confirmPoint(Team.A) }
        btnConfirmB.setOnClickListener { confirmPoint(Team.B) }
        confirmPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideConfirmRunnable)
        handler.postDelayed(hideConfirmRunnable, 12_000L)
    }

    private fun confirmPoint(team: Team) {
        val e = pendingEvent
        hideConfirm()
        if (e != null) registerPoint(team, e.tMs, e.crossings, e.rallyStartMs, "confirmado")
    }

    private fun hideConfirm() {
        handler.removeCallbacks(hideConfirmRunnable)
        confirmPanel.visibility = View.GONE
        pendingEvent = null
    }

    private fun newGame() {
        engine.reset()
        tracker.resetRally()
        updateScoreboard()
        showBanner("Novo jogo iniciado", 2000)
    }

    private fun updateScoreboard() {
        val s = engine.state
        val left = engine.teamOnSide(Side.LEFT)
        val right = left.other()
        fun pts(t: Team) = if (t == Team.A) s.ptsA else s.ptsB
        fun sets(t: Team) = if (t == Team.A) s.setsA else s.setsB
        tvLeftName.text = teamName(left)
        tvRightName.text = teamName(right)
        tvLeftPts.text = pts(left).toString()
        tvRightPts.text = pts(right).toString()
        val setNo = s.setsA + s.setsB + 1
        val prev = if (s.finishedSets.isEmpty()) "" else "  •  " +
            s.finishedSets.joinToString(" ") { (a, b) -> if (left == Team.A) "$a-$b" else "$b-$a" }
        tvInfo.text = if (s.finished) "Fim de jogo  •  Sets ${sets(left)}–${sets(right)}$prev"
        else "Set $setNo (até ${engine.currentTarget()})  •  Sets ${sets(left)}–${sets(right)}$prev"
        btnLeft.text = "+1 ${teamName(left)}"
        btnRight.text = "+1 ${teamName(right)}"
    }

    private fun updateStatus(inferMs: Long = -1) {
        tvStatus.text = when {
            !detector.available -> "IA: modelo ausente (sem internet?) • modo manual"
            calibration == null -> "Calibre a quadra: Menu ▸ Calibrar"
            aiPaused -> "IA pausada"
            else -> {
                val ball = when (tracker.state()) {
                    2 -> "🟢 Bola rastreada"
                    1 -> "🟡 Possível bola"
                    else -> "🔴 Procurando bola"
                }
                val ms = if (inferMs >= 0) " • $inferMs ms" else ""
                "$ball$ms • travessias da rede: ${tracker.crossings}"
            }
        }
    }

    /** Se o modelo não veio no APK, baixa uma vez e guarda no armazenamento do app. */
    private fun downloadModelIfMissing() {
        showBanner("Baixando modelo de IA (~5 MB)…", null)
        Thread {
            val urls = listOf(
                "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite",
                "https://tfhub.dev/tensorflow/lite-model/efficientdet/lite0/detection/metadata/1?lite-format=tflite"
            )
            val target = detector.modelFile()
            var ok = false
            for (u in urls) {
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 20000
                    conn.readTimeout = 120000
                    conn.inputStream.use { i -> target.outputStream().use { i.copyTo(it) } }
                    if (target.length() > 1_000_000 && detector.tryLoad()) { ok = true; break }
                } catch (e: Exception) {
                    Log.e(TAG, "Download do modelo falhou ($u): ${e.message}")
                }
            }
            if (!ok) target.delete()
            runOnUiThread {
                if (ok) showBanner("Modelo de IA pronto! Agora é só calibrar a quadra.", 5000)
                else showBanner("Não consegui baixar o modelo de IA. Conecte à internet e reabra o app.", 9000)
                updateStatus()
            }
        }.start()
    }

    private fun showBanner(text: String, autoHideMs: Long?) {
        handler.removeCallbacks(hideBannerRunnable)
        tvBanner.text = text
        tvBanner.visibility = View.VISIBLE
        if (autoHideMs != null) handler.postDelayed(hideBannerRunnable, autoHideMs)
    }

    // ------------------------------------------------------------------ Replay

    private fun startReplay(fromMs: Long, toMs: Long) {
        val clip = replay.clip(fromMs, toMs)
        if (clip.size < 5) {
            showBanner("Sem vídeo suficiente para replay", 2000)
            return
        }
        replayClip = clip
        replayIdx = 0
        replayLayer.visibility = View.VISIBLE
        showReplayFrame()
    }

    private fun showReplayFrame() {
        val clip = replayClip
        if (replayLayer.visibility != View.VISIBLE || replayIdx >= clip.size) {
            endReplay()
            return
        }
        val f = clip[replayIdx]
        val bmp = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)
        ivReplay.setImageBitmap(bmp)

        val tEnd = clip.last().tMs
        val slow = (tEnd - f.tMs) <= 2500L
        val factor = if (slow) prefs.slowFactor.coerceIn(0.1f, 1f) else 1f
        tvReplayLabel.text = if (slow) "REPLAY  ${"%.2f".format(factor)}x" else "REPLAY"
        val gap = if (replayIdx + 1 < clip.size) clip[replayIdx + 1].tMs - f.tMs else 400L
        replayIdx++
        handler.postDelayed(replayStep, (gap / factor).toLong().coerceIn(15L, 500L))
    }

    private fun endReplay() {
        handler.removeCallbacks(replayStep)
        replayLayer.visibility = View.GONE
        ivReplay.setImageDrawable(null)
        replayClip = emptyList()
    }

    // ------------------------------------------------------------------ Calibração

    private fun applyCalibration(norm: FloatArray) {
        calibration = CourtCalibration(norm)
        overlay.calibNorm = norm
        applyNetLine()
    }

    private fun applyNetLine() {
        val cal = calibration ?: return
        if (cal.prepare(frameW, frameH)) {
            overlay.netLine = cal.netLine()
            overlay.postInvalidate()
        }
    }

    private fun startCalibration() {
        hideConfirm()
        calibTaps.clear()
        overlay.calibNorm = null
        overlay.netLine = null
        overlay.calibPoints = emptyList()
        overlay.calibrating = true
        overlay.invalidate()
        showCalibInstruction()
    }

    private fun showCalibInstruction() {
        val texts = listOf(
            "1/4: toque no canto DISTANTE-ESQUERDO da quadra",
            "2/4: toque no canto DISTANTE-DIREITO",
            "3/4: toque no canto PRÓXIMO-DIREITO",
            "4/4: toque no canto PRÓXIMO-ESQUERDO"
        )
        showBanner(texts[calibTaps.size] + "\n(esquerda/direita como você vê na tela; distante = lado mais longe da câmera)", null)
    }

    private fun onCalibTap(nx: Float, ny: Float) {
        calibTaps.add(PointF(nx, ny))
        overlay.calibPoints = calibTaps.toList()
        overlay.invalidate()
        if (calibTaps.size < 4) {
            showCalibInstruction()
        } else {
            overlay.calibrating = false
            tvBanner.visibility = View.GONE
            val norm = FloatArray(8)
            calibTaps.forEachIndexed { i, p -> norm[i * 2] = p.x; norm[i * 2 + 1] = p.y }
            overlay.calibNorm = norm
            overlay.invalidate()
            AlertDialog.Builder(this)
                .setTitle("Calibração")
                .setMessage("O contorno verde está sobre a quadra (linhas de fundo e laterais)?")
                .setPositiveButton("Salvar") { _, _ ->
                    prefs.calib = norm
                    applyCalibration(norm)
                    updateStatus()
                    showBanner("Calibração salva. Não mexa mais no celular!", 4000)
                }
                .setNegativeButton("Refazer") { _, _ -> startCalibration() }
                .setCancelable(false)
                .show()
        }
    }

    private fun cancelCalibration() {
        overlay.calibrating = false
        overlay.calibPoints = emptyList()
        tvBanner.visibility = View.GONE
        overlay.calibNorm = prefs.calib
        applyNetLine()
        overlay.invalidate()
    }

    // ------------------------------------------------------------------ Menu e configurações

    private fun showMenu() {
        val items = arrayOf(
            "Calibrar quadra",
            "Configurações",
            "Trocar lados manualmente",
            "Novo jogo",
            "Rever último lance",
            if (aiPaused) "Retomar IA" else "Pausar IA",
            if (prefs.debug) "Ocultar depuração" else "Mostrar depuração"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startCalibration()
                    1 -> showSettings()
                    2 -> { engine.toggleSides(); updateScoreboard() }
                    3 -> AlertDialog.Builder(this)
                        .setMessage("Zerar o placar e começar um novo jogo?")
                        .setPositiveButton("Sim") { _, _ -> newGame() }
                        .setNegativeButton("Não", null).show()
                    4 -> {
                        val w = lastReplayWindow
                        if (w != null) startReplay(w.first, w.second)
                        else showBanner("Ainda não há lance para rever", 2000)
                    }
                    5 -> { aiPaused = !aiPaused; updateStatus() }
                    6 -> {
                        prefs.debug = !prefs.debug
                        overlay.showDebug = prefs.debug
                        overlay.postInvalidate()
                        updateStatus()
                    }
                }
            }
            .show()
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        fun field(label: String, value: String, type: Int): EditText {
            box.addView(TextView(this).apply { text = label; setTextColor(Color.LTGRAY); textSize = 12f })
            val et = EditText(this).apply { setText(value); inputType = type; setSingleLine(true) }
            box.addView(et)
            return et
        }
        val txt = InputType.TYPE_CLASS_TEXT
        val num = InputType.TYPE_CLASS_NUMBER
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        val fA = field("Nome do time A", prefs.teamA, txt)
        val fB = field("Nome do time B", prefs.teamB, txt)
        val fBest = field("Número de sets (1, 3 ou 5)", prefs.bestOf.toString(), num)
        val fPts = field("Pontos por set", prefs.pointsSet.toString(), num)
        val fDec = field("Pontos do set decisivo", prefs.pointsDecider.toString(), num)
        val fWin = field("Diferença mínima para fechar o set", prefs.winBy.toString(), num)
        val fConf = field(
            "Confiança mínima p/ ponto automático, % (0 = sempre automático, 100 = sempre pergunta)",
            (prefs.confThreshold * 100).toInt().toString(), num
        )
        val cb = CheckBox(this).apply {
            text = "Replay automático"
            isChecked = prefs.replayEnabled
            setTextColor(Color.WHITE)
        }
        box.addView(cb)
        val fMinX = field("Replay a partir de quantas travessias de rede (0 = todos os pontos)", prefs.replayMinCrossings.toString(), num)
        val fBefore = field("Segundos de vídeo antes do ponto no replay", prefs.replayBeforeSec.toString(), num)
        val fSlow = field("Velocidade da câmera lenta (0.25 a 1.0)", prefs.slowFactor.toString(), dec)

        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salvar") { _, _ ->
                prefs.teamA = fA.text.toString().ifBlank { "Time A" }
                prefs.teamB = fB.text.toString().ifBlank { "Time B" }
                prefs.bestOf = fBest.text.toString().toIntOrNull()?.takeIf { it in listOf(1, 3, 5) } ?: 3
                prefs.pointsSet = fPts.text.toString().toIntOrNull()?.coerceIn(5, 50) ?: 21
                prefs.pointsDecider = fDec.text.toString().toIntOrNull()?.coerceIn(5, 50) ?: 15
                prefs.winBy = fWin.text.toString().toIntOrNull()?.coerceIn(1, 3) ?: 2
                prefs.confThreshold = (fConf.text.toString().toIntOrNull()?.coerceIn(0, 100) ?: 70) / 100f
                prefs.replayEnabled = cb.isChecked
                prefs.replayMinCrossings = fMinX.text.toString().toIntOrNull()?.coerceIn(0, 50) ?: 3
                prefs.replayBeforeSec = fBefore.text.toString().toIntOrNull()?.coerceIn(3, 25) ?: 10
                prefs.slowFactor = fSlow.text.toString().toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 0.5f
                engine.config = prefs.matchConfig()
                updateScoreboard()
                Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ Ciclo de vida

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            replayLayer.visibility == View.VISIBLE -> endReplay()
            overlay.calibrating -> cancelCalibration()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        camExecutor.shutdown()
        detExecutor.shutdown()
        super.onDestroy()
    }
}

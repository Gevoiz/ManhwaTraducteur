package com.manhwatraducteur

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var accessStatus: TextView
    private lateinit var accessButton: MaterialButton
    private lateinit var accessHelp: TextView

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Capture d'écran refusée", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val intent = Intent(this, TranslatorService::class.java)
            .putExtra(TranslatorService.EXTRA_RESULT_CODE, res.resultCode)
            .putExtra(TranslatorService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "C'est parti ! Ouvre ton manhwa et touche la bulle.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        overlayStatus = findViewById(R.id.overlayStatus)
        overlayButton = findViewById(R.id.overlayButton)
        startButton = findViewById(R.id.startButton)
        accessStatus = findViewById(R.id.accessStatus)
        accessButton = findViewById(R.id.accessButton)
        accessHelp = findViewById(R.id.accessHelp)
        accessButton.setOnClickListener {
            startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        bindSpinner(findViewById(R.id.sourceSpinner), SOURCE_LANGS, settings.sourceLang) { settings.sourceLang = it }
        bindSpinner(findViewById(R.id.targetSpinner), TARGET_LANGS, settings.targetLang) { settings.targetLang = it }

        findViewById<MaterialSwitch>(R.id.followScrollSwitch).apply {
            isChecked = settings.followScroll
            setOnCheckedChangeListener { _, v -> settings.followScroll = v }
        }

        val deadZoneLabel = findViewById<TextView>(R.id.deadZoneLabel)
        fun showDeadZone(v: Int) { deadZoneLabel.text = "Zone ignorée en bas de l'écran : $v %" }
        showDeadZone(settings.bottomDeadZone)
        findViewById<Slider>(R.id.deadZoneSlider).apply {
            value = settings.bottomDeadZone.coerceIn(0, 25).toFloat()
            addOnChangeListener { _, v, _ ->
                settings.bottomDeadZone = v.toInt()
                showDeadZone(v.toInt())
            }
        }

        val geminiSection = findViewById<View>(R.id.geminiSection)
        findViewById<RadioGroup>(R.id.engineGroup).apply {
            check(if (settings.engine == ENGINE_GEMINI) R.id.engineGemini else R.id.engineMlkit)
            geminiSection.visibility = if (settings.engine == ENGINE_GEMINI) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, id ->
                settings.engine = if (id == R.id.engineGemini) ENGINE_GEMINI else ENGINE_MLKIT
                geminiSection.visibility = if (id == R.id.engineGemini) View.VISIBLE else View.GONE
            }
        }
        findViewById<EditText>(R.id.geminiKey).apply {
            setText(settings.geminiKey)
            doAfterTextChanged { settings.geminiKey = it?.toString().orEmpty() }
        }
        findViewById<EditText>(R.id.geminiModel).apply {
            setText(settings.geminiModel)
            doAfterTextChanged { settings.geminiModel = it?.toString().orEmpty() }
        }
        findViewById<View>(R.id.geminiKeyButton).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        findViewById<MaterialSwitch>(R.id.autoModeSwitch).apply {
            isChecked = settings.autoMode
            setOnCheckedChangeListener { _, v -> settings.autoMode = v }
        }

        overlayButton.setOnClickListener {
            startActivity(
                Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        startButton.setOnClickListener {
            if (TranslatorService.running) {
                stopService(Intent(this, TranslatorService::class.java))
                startButton.postDelayed({ refreshUi() }, 300)
            } else {
                start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val canOverlay = SysSettings.canDrawOverlays(this)
        overlayStatus.text = if (canOverlay) "✅ Affichage par-dessus les applis autorisé"
        else "⚠️ Il faut autoriser l'affichage par-dessus les autres applis"
        overlayButton.visibility = if (canOverlay) View.GONE else View.VISIBLE
        startButton.isEnabled = canOverlay
        startButton.text = if (TranslatorService.running) "Arrêter" else "Démarrer"

        val access = OverlayAccessibilityService.isEnabled(this)
        accessStatus.text = if (access) "✅ Défilement à travers les traductions activé"
        else "ℹ️ Optionnel : sans ce réglage, un glissement qui commence sur une traduction ne fait pas défiler la page"
        accessButton.visibility = if (access) View.GONE else View.VISIBLE
        accessHelp.visibility = if (access) View.GONE else View.VISIBLE
    }

    private fun start() {
        if (!SysSettings.canDrawOverlays(this)) { refreshUi(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Force la capture de l'écran entier (pas d'une seule appli) pour que les positions collent.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun bindSpinner(spinner: Spinner, langs: List<Lang>, current: String, onPick: (String) -> Unit) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs.map { it.label })
        spinner.setSelection(langs.indexOfFirst { it.code == current }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(langs[pos].code)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

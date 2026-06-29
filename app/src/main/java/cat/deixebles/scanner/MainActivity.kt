package cat.deixebles.scanner

/**
 * Deixebles Scanner v2 — tres estats: verd / groc / vermell
 *  - Verd  (code=ok):  primera validació
 *  - Groc  (code=wa):  ja validat anteriorment
 *  - Vermell (code=fa): QR no vàlid
 */

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

// Tres estats possibles
enum class ScanStatus { VALID, ALREADY_USED, INVALID }

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    private val cookieJar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { c -> cookies.any { it.name == c.name } }
            store.addAll(cookies)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store
    }
    private val http = OkHttpClient.Builder().cookieJar(cookieJar).build()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) handleQrUrl(result.contents)
        else showHome()
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSecurePrefs(this)
        if (prefs.getString("wp_logged_in", null) != null) showHome() else showLogin()
    }

    // ─── LOGIN ────────────────────────────────────────────────────────────────

    private fun showLogin(errorMsg: String? = null) {
        val ctx = this
        val p = dp(24)

        val scroll = ScrollView(ctx).apply { setBackgroundColor(0xFF0f0f1a.toInt()) }
        val root = col(0xFF0f0f1a.toInt(), p, p * 3, p, p)

        root.addView(txt("🎫", 64f, 0xFFFFFFFF.toInt(), Gravity.CENTER))
        root.addView(txt("Deixebles Scanner", 28f, 0xFFFFFFFF.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
        })
        root.addView(txt("Validació d'entrades", 16f, 0xFF6666AA.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(32)
        })

        if (errorMsg != null) {
            root.addView(txt("⚠ $errorMsg", 16f, 0xFFFF6B6B.toInt(), Gravity.CENTER).apply {
                setBackgroundColor(0xFF2a1010.toInt())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20)
            })
        }

        root.addView(label("URL del WordPress"))
        val etUrl = input("https://www.deixebles.cat").also {
            it.setText(prefs.getString("site_url", "https://www.deixebles.cat"))
            root.addView(it)
        }
        root.addView(label("Usuari"))
        val etUser = input("admin").also {
            it.setText(prefs.getString("last_user", ""))
            root.addView(it)
        }
        root.addView(label("Contrasenya"))
        val etPass = input("••••••••", isPassword = true).also { root.addView(it) }

        val progress = ProgressBar(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = dp(8); it.bottomMargin = dp(8)
            }
        }

        val btnLogin = btn("Entrar") {
            val url  = etUrl.text.toString().trim().trimEnd('/')
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString()
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(ctx, "Omple tots els camps", Toast.LENGTH_SHORT).show()
                return@btn
            }
            it.isEnabled = false
            progress.visibility = View.VISIBLE
            doLogin(url, user, pass) { ok, error ->
                runOnUiThread {
                    it.isEnabled = true
                    progress.visibility = View.GONE
                    if (ok) {
                        prefs.edit().putString("site_url", url).putString("last_user", user).apply()
                        showHome()
                    } else {
                        showLogin("Login incorrecte: $error")
                    }
                }
            }
        }

        root.addView(progress)
        root.addView(btnLogin)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun doLogin(siteUrl: String, user: String, pass: String, cb: (Boolean, String?) -> Unit) {
        val loginUrl = "$siteUrl/wp-login.php"
        val body = FormBody.Builder()
            .add("log", user).add("pwd", pass)
            .add("wp-submit", "Log+In")
            .add("redirect_to", "$siteUrl/wp-admin/")
            .add("testcookie", "1").build()
        val req = Request.Builder()
            .url(loginUrl)
            .header("Cookie", "wordpress_test_cookie=WP%20Cookie%20check")
            .header("User-Agent", "DeixeblesScanner/2.0 Android")
            .post(body).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(false, e.message)
            override fun onResponse(call: Call, response: Response) {
                val cookies = cookieJar.loadForRequest(response.request.url)
                val loggedIn = cookies.find { it.name.startsWith("wordpress_logged_in") }
                if (loggedIn != null) {
                    prefs.edit().putString("wp_logged_in", loggedIn.name).apply()
                    cb(true, null)
                } else {
                    val location = response.header("Location") ?: ""
                    if (response.code in 200..302 && location.contains("wp-admin")) {
                        prefs.edit().putString("wp_logged_in", "ok").apply()
                        cb(true, null)
                    } else {
                        cb(false, "Comprova usuari i contrasenya (codi ${response.code})")
                    }
                }
            }
        })
    }

    // ─── HOME ─────────────────────────────────────────────────────────────────

    private fun showHome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val p = dp(24)
        val root = col(0xFF0f0f1a.toInt(), p, 0, p, 0).apply { gravity = Gravity.CENTER }

        root.addView(txt("🎫", 60f, 0xFFFFFFFF.toInt(), Gravity.CENTER))
        root.addView(txt("Deixebles Scanner", 26f, 0xFFFFFFFF.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
        })
        val user = prefs.getString("last_user", "") ?: ""
        val site = prefs.getString("site_url", "") ?: ""
        root.addView(txt("$user · $site", 14f, 0xFF555577.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(40)
        })
        root.addView(btn("📷   Escanejar entrada", accent = true) { launchScanner() })
        root.addView(txt("Tancar sessió", 14f, 0xFF444466.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
            it.setOnClickListener { logout() }
        })

        setContentView(root)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Apunta al QR de l'entrada")
            setBeepEnabled(true)
            setOrientationLocked(true)  // bloqueja en vertical (segueix l'orientació de l'activitat)
        }
        scanLauncher.launch(options)
    }

    // ─── VALIDACIÓ ────────────────────────────────────────────────────────────

    private fun handleQrUrl(url: String) {
        if (!url.contains("qrcet")) {
            showResult(ScanStatus.INVALID, "No és un QR d'entrada vàlid.", emptyMap())
            return
        }
        showLoading()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "DeixeblesScanner/2.0 Android")
            .get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showResult(ScanStatus.INVALID, "Error de xarxa: ${e.message}", emptyMap()) }
            }
            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                runOnUiThread { parseAndShow(html) }
            }
        })
    }

    private fun parseAndShow(html: String) {
        // Detectar quin dels tres estats ha retornat el plugin
        val scanStatus = when {
            html.contains("qrcet_custom_nonce_status_ok") -> ScanStatus.VALID
            html.contains("qrcet_custom_nonce_status_wa") -> ScanStatus.ALREADY_USED
            else -> ScanStatus.INVALID
        }

        // Extreure el text d'estat (text directe dins el div principal)
        val statusClass = when (scanStatus) {
            ScanStatus.VALID        -> "qrcet_custom_nonce_status_ok"
            ScanStatus.ALREADY_USED -> "qrcet_custom_nonce_status_wa"
            ScanStatus.INVALID      -> "qrcet_custom_nonce_status_fa"
        }
        val status = Regex("""class="$statusClass"[^>]*>([^<]+)""")
            .find(html)?.groupValues?.get(1)?.trim() ?: when (scanStatus) {
                ScanStatus.VALID        -> "Entrada vàlida"
                ScanStatus.ALREADY_USED -> "QR Code ja validat"
                ScanStatus.INVALID      -> "QR Code no vàlid"
            }

        val details = mutableMapOf<String, String>()

        fun extract(cssClass: String, label: String) {
            // HTML del plugin: <div class="cssClass"><span>Label: </span><span>Valor</span></div>
            // Regex robust: salta el primer span (label) i captura el segon (valor)
            // Admet espais/salts de línia entre spans
            Regex("""class="$cssClass"[^>]*>\s*<span>[^<]*</span>\s*<span>([^<]+)</span>""")
                .find(html)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { details[label] = it }
        }

        when (scanStatus) {
            ScanStatus.VALID -> {
                // qrcet_order_number ara conté "#9484 Èlia Rifà Grajera"
                extract("qrcet_order_number",  "Comanda")
                extract("qrcet_order_date",    "Data")
                extract("qrcet_product_name",  "Producte")
                extract("qrcet_product_sku",   "SKU")
                extract("qrcet_product_descr", "Descripció")
            }
            ScanStatus.ALREADY_USED -> {
                // Cas groc: comanda+client, producte, referència i data de validació
                extract("qrcet_order_number",    "Comanda")
                extract("qrcet_product_name",    "Producte")
                extract("qrcet_product_descr",   "Descripció")
                extract("qrcet_ref",             "Referència")
                extract("qrcet_validated_date",  "Validat el")
            }
            ScanStatus.INVALID -> {
                // Res extra
            }
        }

        showResult(scanStatus, status, details)
    }

    // ─── PANTALLES DE RESULTAT ────────────────────────────────────────────────

    private fun showLoading() {
        val root = col(0xFF0f0f1a.toInt(), dp(24), 0, dp(24), 0).apply { gravity = Gravity.CENTER }
        root.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(16)
            }
        })
        root.addView(txt("Validant entrada…", 20f, 0xFFAAAAAA.toInt(), Gravity.CENTER))
        setContentView(root)
    }

    private fun showResult(status: ScanStatus, statusText: String, details: Map<String, String>) {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Colors per a cada estat
        val bgColor     = when (status) {
            ScanStatus.VALID        -> 0xFF081a08.toInt()
            ScanStatus.ALREADY_USED -> 0xFF1a1500.toInt()
            ScanStatus.INVALID      -> 0xFF1a0808.toInt()
        }
        val accentColor = when (status) {
            ScanStatus.VALID        -> 0xFF4CAF50.toInt()
            ScanStatus.ALREADY_USED -> 0xFFFFCC00.toInt()
            ScanStatus.INVALID      -> 0xFFF44336.toInt()
        }
        val subColor = when (status) {
            ScanStatus.VALID        -> 0xFF81C784.toInt()
            ScanStatus.ALREADY_USED -> 0xFFFFE066.toInt()
            ScanStatus.INVALID      -> 0xFFEF9A9A.toInt()
        }
        val sepColor = when (status) {
            ScanStatus.VALID        -> 0xFF1a3a1a.toInt()
            ScanStatus.ALREADY_USED -> 0xFF3a3000.toInt()
            ScanStatus.INVALID      -> 0xFF3a1a1a.toInt()
        }
        val emoji = when (status) {
            ScanStatus.VALID        -> "✅"
            ScanStatus.ALREADY_USED -> "⚠️"
            ScanStatus.INVALID      -> "❌"
        }
        val mainText = when (status) {
            ScanStatus.VALID        -> "ENTRADA VÀLIDA"
            ScanStatus.ALREADY_USED -> "JA VALIDADA"
            ScanStatus.INVALID      -> "ENTRADA NO VÀLIDA"
        }

        // Layout vertical
        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor) }
        val root = col(bgColor, dp(24), dp(48), dp(24), dp(32))

        // Emoji + text gran
        root.addView(txt(emoji, 80f, 0xFFFFFFFF.toInt(), Gravity.CENTER))
        root.addView(txt(mainText, 32f, accentColor, Gravity.CENTER).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
        })
        root.addView(txt(statusText, 18f, subColor, Gravity.CENTER).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(28)
        })

        // Detalls
        if (details.isNotEmpty()) {
            root.addView(View(this).apply {
                setBackgroundColor(sepColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).also { it.bottomMargin = dp(24) }
            })

            details.forEach { (label, value) ->
                root.addView(txt(label.uppercase(), 13f, 0xFF667766.toInt()).apply {
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4)
                })
                root.addView(txt(value, 21f, 0xFFEEEEEE.toInt()).apply {
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(18)
                })
            }
        }

        // Botó escanejar
        root.addView(btn("📷  Escanejar una altra entrada") {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            launchScanner()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24)
        })
        root.addView(txt("Tornar a l'inici", 14f, 0xFF444466.toInt(), Gravity.CENTER).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
            setOnClickListener { showHome() }
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    // ─── Logout ──────────────────────────────────────────────────────────────

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Tancar sessió")
            .setMessage("Segur que vols sortir?")
            .setPositiveButton("Sí") { _, _ ->
                prefs.edit().remove("wp_logged_in").apply()
                showLogin()
            }
            .setNegativeButton("Cancel·la", null)
            .show()
    }

    // ─── Helpers UI ──────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun col(bg: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(l, t, r, b)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

    private fun txt(text: String, size: Float, color: Int, gravity: Int = Gravity.START) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun label(text: String) = txt(text, 14f, 0xFF666688.toInt()).apply {
        (layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(16); bottomMargin = dp(6)
        }
    }

    private fun input(hint: String, isPassword: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(0xFF444466.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            setBackgroundColor(0xFF1e1e30.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            if (isPassword) inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun btn(text: String, accent: Boolean = false, onClick: (Button) -> Unit) =
        Button(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(if (accent) 0xFF5c4fff.toInt() else 0xFF222240.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
            )
            setOnClickListener { onClick(this) }
        }
}

// ─── Prefs xifrades ──────────────────────────────────────────────────────────

fun getSecurePrefs(ctx: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(ctx)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        ctx, "deixebles_v2_prefs", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}

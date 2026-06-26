package cat.deixebles.scanner

/**
 * Deixebles Scanner v2 — sense modificar el plugin WordPress
 *
 * Fa exactament el mateix que el navegador:
 *  1. Login amb cookie de sessió real de WordPress
 *  2. Escaneja el QR amb la càmera
 *  3. Crida la URL del QR amb la cookie → rep l'HTML del plugin
 *  4. Parseja l'HTML i mostra verd/vermell + detalls
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Client HTTP que guarda cookies automàticament (com un navegador)
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

        // Si ja tenim sessió guardada, va directe a escanejar
        if (prefs.getString("wp_logged_in", null) != null) {
            showHome()
        } else {
            showLogin()
        }
    }

    // ─── PANTALLA LOGIN ───────────────────────────────────────────────────────

    private fun showLogin(errorMsg: String? = null) {
        val ctx = this
        val p = dp(24)

        val scroll = ScrollView(ctx).apply { setBackgroundColor(0xFF0f0f1a.toInt()) }
        val root = col(0xFF0f0f1a.toInt(), p, p * 4, p, p)

        // Logo
        root.addView(txt("🎫", 64f, 0xFFFFFFFF.toInt(), Gravity.CENTER))
        root.addView(txt("Deixebles\nScanner", 26f, 0xFFFFFFFF.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
        })
        root.addView(txt("Validació d'entrades", 13f, 0xFF6666AA.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(40)
        })

        if (errorMsg != null) {
            root.addView(txt("⚠ $errorMsg", 13f, 0xFFFF6B6B.toInt(), Gravity.CENTER).also {
                it.setBackgroundColor(0xFF2a1010.toInt())
                it.setPadding(dp(16), dp(12), dp(16), dp(12))
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20)
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
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.topMargin = dp(8)
                it.bottomMargin = dp(8)
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
                        prefs.edit()
                            .putString("site_url", url)
                            .putString("last_user", user)
                            .apply()
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

    // Login real de WordPress (igual que el formulari web)
    private fun doLogin(siteUrl: String, user: String, pass: String, cb: (Boolean, String?) -> Unit) {
        // Pas 1: obtenir el login_nonce (necessari a alguns WordPress)
        val loginUrl = "$siteUrl/wp-login.php"

        val body = FormBody.Builder()
            .add("log", user)
            .add("pwd", pass)
            .add("wp-submit", "Log+In")
            .add("redirect_to", "$siteUrl/wp-admin/")
            .add("testcookie", "1")
            .build()

        val req = Request.Builder()
            .url(loginUrl)
            .header("Cookie", "wordpress_test_cookie=WP%20Cookie%20check")
            .header("User-Agent", "DeixeblesScanner/2.0 Android")
            .post(body)
            .build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cb(false, e.message)
            override fun onResponse(call: Call, response: Response) {
                // Si tenim cookie wp_logged_in → login ok
                val cookies = cookieJar.loadForRequest(response.request.url)
                val loggedIn = cookies.find { it.name.startsWith("wordpress_logged_in") }
                if (loggedIn != null) {
                    prefs.edit().putString("wp_logged_in", loggedIn.name).apply()
                    cb(true, null)
                } else {
                    // Mira si ha redirigit a /wp-admin (també significa ok)
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

    // ─── PANTALLA PRINCIPAL ───────────────────────────────────────────────────

    private fun showHome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val ctx = this
        val p = dp(24)

        val root = col(0xFF0f0f1a.toInt(), p, 0, p, 0).apply {
            gravity = Gravity.CENTER
        }

        root.addView(txt("🎫", 56f, 0xFFFFFFFF.toInt(), Gravity.CENTER))
        root.addView(txt("Deixebles Scanner", 22f, 0xFFFFFFFF.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
        })

        val user = prefs.getString("last_user", "") ?: ""
        val site = prefs.getString("site_url", "") ?: ""
        root.addView(txt("$user · $site", 11f, 0xFF555577.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(52)
        })

        root.addView(btn("📷   Escanejar entrada", accent = true) {
            launchScanner()
        })

        root.addView(txt("Tancar sessió", 12f, 0xFF444466.toInt(), Gravity.CENTER).also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
            it.setOnClickListener { logout() }
        })

        setContentView(root)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Apunta al QR de l'entrada")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    // ─── VALIDACIÓ ────────────────────────────────────────────────────────────

    private fun handleQrUrl(url: String) {
        // Comprova que és una URL del plugin
        if (!url.contains("qrcet")) {
            showResult(valid = false, status = "No és un QR d'entrada vàlid.", details = emptyMap())
            return
        }

        showLoading()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "DeixeblesScanner/2.0 Android")
            .get()
            .build()

        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    showResult(false, "Error de xarxa: ${e.message}", emptyMap())
                }
            }
            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                runOnUiThread { parseAndShow(html) }
            }
        })
    }

    // Parseja l'HTML que retorna el plugin (sense modificar-lo)
    private fun parseAndShow(html: String) {
        val valid = html.contains("qrcet_custom_nonce_status_ok")

        // Extreu el text d'estat (primer div)
        val status = Regex("""qrcet_custom_nonce_status_(?:ok|fa)"[^>]*>([^<]+)""")
            .find(html)?.groupValues?.get(1)?.trim() ?: if (valid) "Entrada vàlida" else "Entrada no vàlida"

        // Extreu els detalls de dins qrcet_ticket_info
        val details = mutableMapOf<String, String>()

        fun extract(cssClass: String, label: String) {
            Regex("""class="$cssClass"[^>]*>.*?<span>([^<]+)</span>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { details[label] = it }
        }

        // Camps que mostra el plugin per a admins/editors
        extract("qrcet_order_number",  "Comanda #")
        extract("qrcet_customer_name", "Client")
        extract("qrcet_order_date",    "Data")
        extract("qrcet_product_name",  "Producte")
        extract("qrcet_product_sku",   "SKU")
        extract("qrcet_product_descr", "Descripció")

        // Redeemed?
        val redeemed = html.contains("qrcet_code_redeemed")

        showResult(valid, status, details, redeemed)
    }

    // ─── PANTALLES DE RESULTAT ────────────────────────────────────────────────

    private fun showLoading() {
        val root = col(0xFF0f0f1a.toInt(), dp(24), 0, dp(24), 0).apply {
            gravity = Gravity.CENTER
        }
        root.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(16)
            }
        })
        root.addView(txt("Validant entrada…", 16f, 0xFFAAAAAA.toInt(), Gravity.CENTER))
        setContentView(root)
    }

    private fun showResult(
        valid: Boolean,
        status: String,
        details: Map<String, String>,
        redeemed: Boolean = false
    ) {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val bgColor     = if (valid) 0xFF081a08.toInt() else 0xFF1a0808.toInt()
        val accentColor = if (valid) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        val emoji       = if (valid) "✅" else "❌"
        val mainText    = if (valid) "ENTRADA VÀLIDA" else "ENTRADA NO VÀLIDA"

        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor) }
        val root = col(bgColor, dp(24), dp(56), dp(24), dp(32))

        // Emoji + text gran
        root.addView(txt(emoji, 80f, 0xFFFFFFFF.toInt(), Gravity.CENTER))
        root.addView(txt(mainText, 30f, accentColor, Gravity.CENTER).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
        })
        root.addView(txt(status, 14f, if (valid) 0xFF81C784.toInt() else 0xFFEF9A9A.toInt(), Gravity.CENTER).apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(28)
        })

        // Indicador "ja escanejat"
        if (valid && redeemed) {
            root.addView(txt("✔ QR marcat com a usat", 13f, 0xFF81C784.toInt(), Gravity.CENTER).apply {
                setBackgroundColor(0xFF0d2a0d.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20)
            })
        }

        // Detalls
        if (details.isNotEmpty()) {
            // Separador
            root.addView(View(this).apply {
                setBackgroundColor(if (valid) 0xFF1a3a1a.toInt() else 0xFF3a1a1a.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).also { it.bottomMargin = dp(20) }
            })

            details.forEach { (label, value) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = dp(10) }
                }
                row.addView(txt("$label:", 13f, 0xFF667766.toInt()).apply {
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(txt(value, 13f, 0xFFEEEEEE.toInt()).apply {
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
                })
                root.addView(row)
            }
        }

        // Botó següent
        root.addView(btn("📷   Escanejar una altra entrada") {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            launchScanner()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(32)
        })

        root.addView(txt("Tornar a l'inici", 12f, 0xFF444466.toInt(), Gravity.CENTER).apply {
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
                cookieJar.run {
                    // Neteja les cookies
                    loadForRequest("https://dummy".toHttpUrl()).toMutableList().clear()
                }
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

    private fun label(text: String) = txt(text, 12f, 0xFF666688.toInt()).apply {
        (layoutParams as LinearLayout.LayoutParams).apply {
            topMargin = dp(16)
            bottomMargin = dp(6)
        }
    }

    private fun input(hint: String, isPassword: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setHintTextColor(0xFF444466.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
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
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(if (accent) 0xFF5c4fff.toInt() else 0xFF222240.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
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

# 🎫 Deixebles Scanner v2 — Guia d'instal·lació

## Què fa aquesta versió

- ✅ **No cal modificar el plugin WordPress**
- ✅ Login amb usuari + contrasenya normals de WordPress
- ✅ Escaneja el QR amb la càmera (sense obrir el navegador)
- ✅ Mostra el resultat verd/vermell amb els detalls
- ✅ Funciona amb tots els QRs ja enviats

---

## Com muntar l'app (Android Studio)

### Pas 1 — Crea el projecte

1. Obre **Android Studio**
2. **New Project → Empty Activity**
3. Configura:
   - **Name:** Deixebles Scanner
   - **Package name:** `cat.deixebles.scanner`
   - **Language:** Kotlin
   - **Minimum SDK:** API 26 (Android 8.0)
4. Clica **Finish**

---

### Pas 2 — Substitueix els fitxers

Substitueix el contingut d'aquests fitxers pels del paquet:

| Fitxer del paquet | On posar-lo al projecte |
|---|---|
| `MainActivity.kt` | `app/src/main/java/cat/deixebles/scanner/MainActivity.kt` |
| `AndroidManifest.xml` | `app/src/main/AndroidManifest.xml` |
| `app/build.gradle` | `app/build.gradle` |
| `build.gradle` | `build.gradle` (arrel del projecte) |
| `settings.gradle` | `settings.gradle` (arrel del projecte) |

---

### Pas 3 — Sincronitza i compila

1. Android Studio demanarà **"Sync Now"** → clica-ho
2. Espera que descarregui les dependències (~1-2 min)
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. L'APK estarà a: `app/build/outputs/apk/debug/app-debug.apk`

---

### Pas 4 — Instal·la als dispositius

**Opció A — Per cable:**
```
adb install app-debug.apk
```

**Opció B — Per Telegram/WhatsApp/Drive:**
Envia l'APK directament. Al mòbil:
- Ajustes → Seguretat → "Instal·lar apps desconegudes" → activa per Telegram/Chrome
- Obre l'APK i instal·la

---

## Com funciona el login

L'app fa exactament el mateix que el navegador quan entres a WordPress:

1. Envia usuari + contrasenya a `/wp-login.php`
2. WordPress retorna una **cookie de sessió** (`wordpress_logged_in_...`)
3. L'app guarda aquesta cookie de forma **xifrada** al dispositiu
4. Cada vegada que escaneja un QR, usa la cookie (com faria el navegador)

> La cookie es guarda fins que tanques sessió manualment.
> Les credencials (usuari/contrasenya) **no es guarden**, només la cookie.

---

## Flux d'ús

```
[Obres l'app per primera vegada]
        ↓
[Pantalla login → URL + usuari + contrasenya]
        ↓
[Pantalla principal]
        ↓
[Botó "Escanejar entrada" → càmera s'obre]
        ↓
[Escaneja el QR del PDF]
        ↓
   ┌─────────────────────────────────┐
   │  ✅ VERD — Entrada vàlida        │
   │     Comanda #, Client, Producte  │
   │                                  │
   │  ❌ VERMELL — No vàlida/usada    │
   └─────────────────────────────────┘
        ↓
[Botó "Escanejar una altra" → càmera immediatament]
```

---

## Requisits del compte WordPress

L'usuari que s'usa per fer login ha de ser **Editor** o **Administrador**,
ja que el plugin original només mostra els detalls complets a aquests rols.

---

## Diferència amb la v1

| | v1 (amb API nova) | v2 (aquesta) |
|---|---|---|
| Cal modificar el plugin | ✅ Sí | ❌ No |
| Registre d'hora d'escaneig | ✅ Sí | ❌ No (fins que actualitzis) |
| Funciona amb QRs existents | ✅ Sí | ✅ Sí |
| Login | Application Password | Usuari + contrasenya normals |

Quan en el futur actualitzis el plugin, pots passar a la v1 per tenir
el registre d'hores d'escaneig.

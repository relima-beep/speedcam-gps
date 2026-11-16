package com.speedcam.gps

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject

/** Persistent effect choices and user presets. Preview effects are NOT encoded into MP4. */
data class EffectStyle(
    val filter: String = "Normal",
    val frame: String = "Racing",
    val speedometer: String = "Digital",
    val sticker: String = "Ingen"
) {
    fun json() = JSONObject().put("filter", filter).put("frame", frame)
        .put("speedometer", speedometer).put("sticker", sticker).toString()
    companion object {
        fun parse(s: String): EffectStyle = try {
            val j = JSONObject(s)
            EffectStyle(j.optString("filter", "Normal"), j.optString("frame", "Racing"),
                j.optString("speedometer", "Digital"), j.optString("sticker", "Ingen"))
        } catch (_: Exception) { EffectStyle() }
    }
}

class EffectStudio(private val context: Context, private val onChange: (EffectStyle) -> Unit) {
    private val prefs = context.getSharedPreferences("effect_studio_v5", Context.MODE_PRIVATE)
    var style: EffectStyle = EffectStyle.parse(prefs.getString("active", "") ?: "")
        private set

    private val filters = listOf("Normal", "Cinematic", "Svartvitt", "Neon Night", "Retro VHS", "Varm", "Kall")
    private val frames = listOf("Racing", "Minimal", "Neon", "Retro", "Ingen")
    private val speedometers = listOf("Digital", "Analog", "Båda", "Dold")
    private val stickers = listOf("Ingen", "REC", "Stjärnor", "Blixt", "Trofé")

    fun open() {
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 12, 32, 8) }
        fun spinner(title: String, values: List<String>, selected: String): Spinner {
            layout.addView(TextView(context).apply { text = title; textSize = 16f; setPadding(0, 12, 0, 2) })
            return Spinner(context).also { s ->
                s.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
                s.setSelection(values.indexOf(selected).coerceAtLeast(0))
                layout.addView(s)
            }
        }
        val f = spinner("Filter", filters, style.filter)
        val r = spinner("Ram", frames, style.frame)
        val h = spinner("Hastighetsmätare", speedometers, style.speedometer)
        val st = spinner("Extra effekt", stickers, style.sticker)
        val info = TextView(context).apply {
            text = "Effekterna visas i förhandsvisningen. MP4-filer sparas fortfarande som original utan effekter eller inbränd GPS-hastighet."
            setPadding(0, 20, 0, 12)
        }
        layout.addView(info)
        fun chosen() = EffectStyle(f.selectedItem.toString(), r.selectedItem.toString(), h.selectedItem.toString(), st.selectedItem.toString())
        AlertDialog.Builder(context).setTitle("Effektstudio v5").setView(layout)
            .setPositiveButton("Använd") { _, _ -> apply(chosen()) }
            .setNeutralButton("Spara profil") { _, _ -> saveProfileDialog(chosen()) }
            .setNegativeButton("Avbryt", null).show()
    }

    private fun saveProfileDialog(chosen: EffectStyle) {
        val name = EditText(context).apply { hint = "Profilnamn, t.ex. Nattkörning"; setSingleLine() }
        AlertDialog.Builder(context).setTitle("Spara egen profil").setView(name)
            .setPositiveButton("Spara") { _, _ ->
                val key = name.text.toString().trim().take(32)
                if (key.isNotBlank()) {
                    prefs.edit().putString("profile_$key", chosen.json()).apply()
                    apply(chosen)
                }
            }.setNegativeButton("Avbryt", null).show()
    }

    fun showProfiles() {
        val names = prefs.all.keys.filter { it.startsWith("profile_") }.map { it.removePrefix("profile_") }.sorted()
        if (names.isEmpty()) {
            AlertDialog.Builder(context).setMessage("Inga profiler sparade ännu. Välj 'Spara profil' i Effektstudio.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(context).setTitle("Mina profiler")
            .setItems(names.toTypedArray()) { _, index ->
                val name = names[index]
                val saved = prefs.getString("profile_$name", "") ?: ""
                apply(EffectStyle.parse(saved))
            }.setNegativeButton("Stäng", null).show()
    }

    private fun apply(next: EffectStyle) {
        style = next
        prefs.edit().putString("active", next.json()).apply()
        onChange(next)
    }
}

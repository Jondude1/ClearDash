package com.jondude1.cleardash.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import com.jondude1.cleardash.R
import com.jondude1.cleardash.data.DataHub
import com.jondude1.cleardash.data.Geo
import com.jondude1.cleardash.data.PlaceNames
import com.jondude1.cleardash.data.Prefs
import com.jondude1.cleardash.data.Trips
import com.jondude1.cleardash.render.DashMode

/**
 * The NAV panel: pick a destination with the knob and Google Maps takes over the screen with
 * turn-by-turn. Come back to ClearDash any time from Android Auto's app bar; the next turn
 * keeps showing on the ROUTE view while Maps navigates.
 */
class NavScreen(ctx: CarContext) : Screen(ctx) {

    private val holo = CarColor.createCustom(0xFF00E5FF.toInt(), 0xFF00A7BB.toInt())
    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).setTint(holo).build()

    private fun go(uri: String, what: String) {
        try {
            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(uri)))
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Couldn't open navigation for $what", CarToast.LENGTH_LONG).show()
        }
    }

    private fun geoFor(lat: Double, lon: Double, label: String) =
        "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})"

    override fun onGetTemplate(): Template {
        var limit = 6
        try {
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
                limit = carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
            }
        } catch (_: Exception) {
        }
        val rows = ArrayList<Row>()
        fun row(title: String, text: String?, res: Int, onClick: () -> Unit) {
            rows += Row.Builder().setTitle(title).apply { if (!text.isNullOrBlank()) addText(text) }
                .setImage(icon(res)).setOnClickListener(onClick).build()
        }

        val nav = DataHub.state.value.nav
        if (nav != null) {
            row("Route view", listOf(nav.distance, nav.instruction).filter { it.isNotBlank() }.joinToString(" · "), R.drawable.ic_q_nav) {
                DashScreen.currentMode = DashMode.ROUTE
                screenManager.pop()
            }
        }
        row("Google Maps", "Search, or pick from Maps' own list", R.drawable.ic_q_search) { go("geo:0,0?q=", "Google Maps") }
        if (Prefs.homeAddr.isNotBlank()) row("Home", Prefs.homeAddr, R.drawable.ic_q_home) { go("geo:0,0?q=" + Uri.encode(Prefs.homeAddr), "Home") }
        if (Prefs.workAddr.isNotBlank()) row("Work", Prefs.workAddr, R.drawable.ic_q_work) { go("geo:0,0?q=" + Uri.encode(Prefs.workAddr), "Work") }

        // parked spot and saved places
        val here = DataHub.state.value
        fun far(lat: Double, lon: Double) = here.lat == null || here.lon == null || Geo.distanceM(here.lat, here.lon, lat, lon) > 150.0
        val used = ArrayList<Pair<Double, Double>>()
        fun fresh(lat: Double, lon: Double) = used.none { Geo.distanceM(it.first, it.second, lat, lon) < 150.0 }
        Trips.parked()?.let { p ->
            if (far(p.lat, p.lon)) {
                used += p.lat to p.lon
                row("Where I parked", PlaceNames.cached(p.lat, p.lon) ?: p.whenText(), R.drawable.ic_q_car) { go(geoFor(p.lat, p.lon, "Parked"), "your parking spot") }
            }
        }
        for (sp in Trips.spots()) {
            if (!fresh(sp.lat, sp.lon) || !far(sp.lat, sp.lon)) continue
            used += sp.lat to sp.lon
            val title = if (sp.note == "Marked from the car") PlaceNames.cached(sp.lat, sp.lon) ?: "Saved spot" else sp.note
            row(title, PlaceNames.cached(sp.lat, sp.lon)?.takeIf { it != title } ?: sp.whenText(), R.drawable.ic_q_pin) {
                go(geoFor(sp.lat, sp.lon, title), title)
            }
        }
        // where recent drives ended
        for (t in Trips.history()) {
            val lat = t.endLat ?: continue
            val lon = t.endLon ?: continue
            if (!fresh(lat, lon) || !far(lat, lon)) continue
            used += lat to lon
            val name = PlaceNames.cached(lat, lon) ?: "Recent destination"
            row(name, "Drove here " + java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.US).format(java.util.Date(t.endedAt)), R.drawable.ic_q_history) {
                go(geoFor(lat, lon, name), name)
            }
        }

        val list = ItemList.Builder()
        rows.take(limit).forEach { list.addItem(it) }
        return ListTemplate.Builder()
            .setTitle("Navigate")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

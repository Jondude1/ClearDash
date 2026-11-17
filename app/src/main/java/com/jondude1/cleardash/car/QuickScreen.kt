package com.jondude1.cleardash.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jondude1.cleardash.R
import com.jondude1.cleardash.data.Callouts
import com.jondude1.cleardash.data.Spot
import com.jondude1.cleardash.data.TripSummary
import com.jondude1.cleardash.data.Trips
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HOLO = CarColor.createCustom(0xFF00E5FF.toInt(), 0xFF00A7BB.toInt())

private fun CarContext.icon(res: Int, tint: CarColor? = HOLO): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { if (tint != null) setTint(tint) }.build()

/** Opens Google Maps (or the default navigation app) on the car screen with a route to [s]. */
private fun CarContext.navigateTo(s: Spot) {
    try {
        startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(${Uri.encode(s.note)})")))
    } catch (_: Exception) {
        CarToast.makeText(this, "Couldn't open navigation", CarToast.LENGTH_SHORT).show()
    }
}

/**
 * Quick actions, made for the commander knob: nudge to the ⌘ button on the dash, press,
 * then turn and press to pick. Everything here works without touching the screen.
 */
class QuickScreen(ctx: CarContext) : Screen(ctx) {

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        fun add(title: String, text: String?, icon: Int, onClick: () -> Unit) {
            items.addItem(
                GridItem.Builder().setTitle(title).apply { if (text != null) setText(text) }
                    .setImage(carContext.icon(icon), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener(onClick)
                    .build()
            )
        }
        val sm = carContext.getCarService(ScreenManager::class.java)
        val live = Trips.live.value
        add("Trip", live?.let { "%.1f mi".format(it.distanceMi) } ?: Trips.last()?.let { "Last: %.1f mi".format(it.distanceMi) }, R.drawable.ic_q_trip) {
            sm.push(TripScreen(carContext))
        }
        add("Mark spot", "Save this place", R.drawable.ic_q_pin) {
            val s = Trips.markSpot("Marked from the car")
            CarToast.makeText(carContext, if (s != null) "Spot saved" else "No GPS fix yet", CarToast.LENGTH_SHORT).show()
            invalidate()
        }
        add("Places", "Parked + saved", R.drawable.ic_q_list) { sm.push(SpotsScreen(carContext)) }
        add("Repeat", "Last callout", R.drawable.ic_q_repeat) {
            if (!Callouts.repeatLast()) CarToast.makeText(carContext, "Nothing said yet this drive", CarToast.LENGTH_SHORT).show()
        }
        val muted = Callouts.mutedThisDrive
        add(if (muted) "Unmute" else "Mute", if (muted) "Callouts are off" else "Callouts this drive", if (muted) R.drawable.ic_q_voice else R.drawable.ic_q_mute) {
            Callouts.mutedThisDrive = !Callouts.mutedThisDrive
            CarToast.makeText(carContext, if (Callouts.mutedThisDrive) "Callouts muted for this drive" else "Callouts on", CarToast.LENGTH_SHORT).show()
            invalidate()
        }
        return GridTemplate.Builder()
            .setTitle("Quick actions")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

/** The drive in progress and the last finished one. */
class TripScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { delay(5_000); invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val t = Trips.live.value
        if (t != null) {
            pane.addRow(Row.Builder().setTitle("This drive")
                .addText("%.1f mi · %s".format(t.distanceMi, TripSummary.fmtDuration(t.totalSec)))
                .addText("Avg ${t.avgMph.roundToInt()} mph · top ${t.topMph.roundToInt()} mph")
                .setImage(carContext.icon(R.drawable.ic_q_trip)).build())
            pane.addRow(Row.Builder().setTitle("Over the limit")
                .addText(if (t.overLimitSec < 5) "None" else TripSummary.fmtDuration(t.overLimitSec)).build())
            val a = t.fuelStartPct; val b = t.fuelNowPct
            if (a != null && b != null) {
                pane.addRow(Row.Builder().setTitle("Fuel")
                    .addText("$b% now" + if (a > b) " · used ≈ %.2f gal".format((a - b) / 100f * com.jondude1.cleardash.data.Prefs.tankGal) else "").build())
            }
        }
        val last = Trips.last()
        if (last != null) {
            pane.addRow(Row.Builder().setTitle("Last trip · " + last.headline())
                .addText("Avg ${last.avgMph.roundToInt()} mph · top ${last.topMph.roundToInt()} mph" +
                    (last.fuelUsedGal?.let { " · ≈ %.2f gal".format(it) } ?: "")).build())
        }
        if (t == null && last == null) {
            pane.addRow(Row.Builder().setTitle("No trips yet").addText("Trips are recorded while ClearDash is open in the car").build())
        }
        return PaneTemplate.Builder(pane.build()).setTitle("Trip").setHeaderAction(Action.BACK).build()
    }
}

/** Where you parked plus saved spots; pick one to navigate there. */
class SpotsScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("No places yet — use Mark spot, or park once with ClearDash open")
        val parked = Trips.parked()
        val all = listOfNotNull(parked) + Trips.spots()
        all.take(6).forEach { s ->
            list.addItem(
                Row.Builder()
                    .setTitle(if (s === parked) "Parked" else s.note)
                    .addText(s.whenText())
                    .setImage(carContext.icon(if (s === parked) R.drawable.ic_q_car else R.drawable.ic_q_pin))
                    .setOnClickListener { carContext.navigateTo(s) }
                    .build()
            )
        }
        return ListTemplate.Builder().setTitle("Places").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

package com.jondude1.cleardash.car

import android.graphics.Color
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jondude1.cleardash.R
import com.jondude1.cleardash.data.HaEntity
import com.jondude1.cleardash.data.HomeAssistant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home Assistant controls on the car screen. */
class HomeScreen(ctx: CarContext, private val arrived: Boolean = false) : Screen(ctx) {
    private var entities: List<HaEntity>? = null
    private var busy: String? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(6_000)
                }
            }
        }
    }

    private suspend fun refresh() {
        entities = HomeAssistant.fetchChosen()
        invalidate()
    }

    private fun iconFor(e: HaEntity): Int = when (e.domain) {
        "light" -> R.drawable.ic_ha_light
        "cover" -> R.drawable.ic_ha_garage
        "lock" -> R.drawable.ic_ha_lock
        "alarm_control_panel" -> R.drawable.ic_ha_shield
        "scene", "script", "button" -> R.drawable.ic_ha_play
        else -> R.drawable.ic_ha_power
    }

    private fun tint(e: HaEntity): CarColor = when {
        e.state == "unavailable" -> CarColor.createCustom(Color.GRAY, Color.GRAY)
        e.domain == "lock" && e.state == "unlocked" -> CarColor.createCustom(0xFFFFB547.toInt(), 0xFFFFB547.toInt())
        e.domain == "cover" && e.state != "closed" -> CarColor.createCustom(0xFFFFB547.toInt(), 0xFFFFB547.toInt())
        e.isOn -> CarColor.createCustom(0xFF39D0FF.toInt(), 0xFF39D0FF.toInt())
        else -> CarColor.createCustom(0xFFDDE3EA.toInt(), 0xFFDDE3EA.toInt())
    }

    override fun onGetTemplate(): Template {
        val b = GridTemplate.Builder()
            .setTitle(if (arrived) "Welcome home" else "Home")
            .setHeaderAction(Action.BACK)
        val list = entities
        if (list == null) {
            return b.setLoading(true).build()
        }
        var limit = 6
        try {
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
                limit = carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
            }
        } catch (_: Exception) {
        }
        val items = ItemList.Builder().setNoItemsMessage("Pick devices in the ClearDash phone app")
        list.take(limit).forEach { e ->
            val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, iconFor(e))).setTint(tint(e)).build()
            items.addItem(
                GridItem.Builder()
                    .setTitle(e.name)
                    .setText(if (busy == e.id) "Working…" else HomeAssistant.prettyState(e))
                    .setImage(icon, GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener {
                        busy = e.id
                        invalidate()
                        lifecycleScope.launch {
                            val ok = HomeAssistant.tap(e)
                            if (!ok) CarToast.makeText(carContext, "Couldn't reach Home Assistant", CarToast.LENGTH_SHORT).show()
                            delay(700)
                            busy = null
                            refresh()
                        }
                    }
                    .build()
            )
        }
        return b.setSingleList(items.build()).build()
    }
}

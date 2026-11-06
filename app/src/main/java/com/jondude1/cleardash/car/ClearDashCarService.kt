package com.jondude1.cleardash.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.model.Alert
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jondude1.cleardash.data.CarAlert
import com.jondude1.cleardash.data.DataHub
import kotlinx.coroutines.launch

class ClearDashCarService : CarAppService() {
    // Sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    private var alertId = 0

    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                DataHub.acquire()
                DataHub.startService(carContext)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                DataHub.release()
                DataHub.stopService(carContext) // car disconnected: stop draining the battery
            }
        })
        lifecycleScope.launch { DataHub.alerts.collect { show(it) } }
        return DashScreen(carContext)
    }

    private fun show(a: CarAlert) {
        try {
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
                val alert = Alert.Builder(++alertId, CarText.create(a.title), 10_000)
                    .setSubtitle(CarText.create(a.message))
                    .setIcon(CarIcon.ALERT)
                    .build()
                carContext.getCarService(AppManager::class.java).showAlert(alert)
            } else {
                CarToast.makeText(carContext, "${a.title} — ${a.message}", CarToast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            CarToast.makeText(carContext, "${a.title} — ${a.message}", CarToast.LENGTH_LONG).show()
        }
    }
}

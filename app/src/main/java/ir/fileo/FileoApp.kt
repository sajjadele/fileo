package ir.fileo

import android.app.Application
import ir.fileo.di.AppContainer

class FileoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

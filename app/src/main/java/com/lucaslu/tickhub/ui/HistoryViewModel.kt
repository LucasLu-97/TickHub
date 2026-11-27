package com.lucaslu.tickhub.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Trigger history list and log deletion. */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val c = AppContainer.get(app)

    val logs get() = c.logs

    fun deleteLog(id: String) {
        viewModelScope.launch { c.repository.deleteLog(id) }
    }
}

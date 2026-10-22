package dev.javieraguilera.mysone.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.data.Fact
import dev.javieraguilera.mysone.data.FactState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoryViewModel(private val container: AppContainer) : ViewModel() {
    private val dao = container.db.facts()

    val confirmed: StateFlow<List<Fact>> = observe(FactState.CONFIRMED)
    val suggested: StateFlow<List<Fact>> = observe(FactState.SUGGESTED)

    private fun observe(state: String) =
        dao.observeByState(state).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun confirm(f: Fact) = launch { dao.setState(f.id, FactState.CONFIRMED) }

    /** Solo el texto: confirmarlo mientras se editaba no se deshace. */
    fun edit(f: Fact, text: String) = launch { dao.setText(f.id, text.trim()) }

    /** Lo añadido a mano ya está confirmado. */
    fun add(text: String) = launch {
        dao.insert(Fact(text = text.trim(), state = FactState.CONFIRMED, createdAt = System.currentTimeMillis()))
    }

    fun delete(f: Fact) = launch { dao.delete(f.id) }

    /** "Deshacer": falla si entretanto se borró su conversación (FK); entonces no se recupera. */
    fun restore(f: Fact) = launch { runCatching { dao.insert(f) } }

    /** En appScope, como en Tareas: al cambiar de pestaña viewModelScope se cancela y el borrado se perdería. */
    private fun launch(block: suspend () -> Unit) {
        container.appScope.launch { block() }
    }
}

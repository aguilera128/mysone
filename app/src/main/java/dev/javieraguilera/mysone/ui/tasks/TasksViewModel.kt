package dev.javieraguilera.mysone.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.javieraguilera.mysone.AppContainer
import dev.javieraguilera.mysone.data.TaskItem
import dev.javieraguilera.mysone.data.TaskState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(private val container: AppContainer) : ViewModel() {
    private val dao = container.db.tasks()

    /** Las que tienen aviso primero, por hora; luego el resto. */
    val pending: StateFlow<List<TaskItem>> = dao.observeByState(TaskState.OPEN)
        .map { list -> list.sortedWith(compareBy(nullsLast()) { it.remindAt }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val suggested: StateFlow<List<TaskItem>> = observe(TaskState.SUGGESTED)
    val done: StateFlow<List<TaskItem>> = observe(TaskState.DONE)

    private fun observe(state: String) =
        dao.observeByState(state).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun accept(t: TaskItem) = save(t.id) { it.copy(state = TaskState.OPEN) }

    fun dismiss(t: TaskItem) = save(t.id) { it.copy(state = TaskState.DISMISSED) }

    fun setDone(t: TaskItem, done: Boolean) = save(t.id) { it.copy(state = if (done) TaskState.DONE else TaskState.OPEN) }

    /**
     * [t] es la tarea al abrir el diálogo. Si no se ha tocado el aviso, vale el de ahora: un "Posponer 1 h" desde la
     * notificación mientras se editaba no se pierde (ni un "Hecho", que [save] parte de la fila actual).
     */
    fun edit(t: TaskItem, text: String, remindAt: Long?) = save(t.id) { now ->
        val remind = if (remindAt == t.remindAt) now.remindAt else remindAt
        now.copy(text = text.trim(), remindAt = remind, notifiedAt = if (remind == now.remindAt) now.notifiedAt else null)
    }

    fun create(text: String, remindAt: Long?) {
        viewModelScope.launch {
            val t = TaskItem(text = text.trim(), state = TaskState.OPEN, remindAt = remindAt, createdAt = System.currentTimeMillis())
            val id = dao.insert(t)
            container.reminders.schedule(t.copy(id = id))
        }
    }

    fun delete(t: TaskItem) {
        viewModelScope.launch {
            container.reminders.cancel(t.id)
            dao.delete(t.id)
        }
    }

    /** "Deshacer" tras borrar. Falla si entretanto se borró su conversación (FK); entonces no se recupera. */
    fun restore(t: TaskItem) {
        viewModelScope.launch {
            runCatching { dao.insert(t) }.onSuccess { container.reminders.schedule(t) }
        }
    }

    /**
     * Sobre la fila de ahora y no sobre la copia que tiene la pantalla: lo que la notificación ("Hecho", "Posponer")
     * o Conversación cambien entretanto no se deshace. Si ya no existe, nada.
     */
    private fun save(id: Long, change: (TaskItem) -> TaskItem) {
        viewModelScope.launch {
            val t = dao.get(id)?.let(change) ?: return@launch
            dao.update(t)
            // El programador solo pone alarma si está OPEN y la hora es futura.
            container.reminders.cancel(t.id)
            container.reminders.schedule(t)
        }
    }
}

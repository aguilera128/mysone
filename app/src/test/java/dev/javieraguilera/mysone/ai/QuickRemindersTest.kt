package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.data.Segment
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRemindersTest {
    @Test fun `detecta peticiones de recordatorio`() {
        listOf(
            "Recuérdame mañana a las diez llamar al taller",
            "oye recuerdame comprar pan",
            "RECUÉRDAME lo del dentista",
            "recuérdamelo luego",
            "Recuérdale a Marta que traiga las llaves",
            "Recuerda que el lunes hay reunión",
            "Avísame cuando salga el tren",
            "avisame a las cinco",
            "avísale a Pablo",
            "No me dejes olvidar el regalo",
            "que no se me olvide pagar el seguro",
            "No te olvides de la compra",
            "no se olvide usted del recibo",
            "Apúntame una cita el jueves",
            "apuntalo en la lista",
            "Recuérdame", // con la tilde como carácter combinante
        ).forEach { assertTrue(it, QuickReminders.matches(it)) }
    }

    @Test fun `no salta con frases parecidas`() {
        listOf(
            "¿Recuerdas cuando fuimos a Lisboa?",
            "No me acuerdo de nada",
            "Eso fue un recuerdo bonito",
            "Te he dejado un aviso en la nevera",
            "No me olvido de ti",
            "Ya lo he apuntado",
            "Los recordatorios del móvil no funcionan",
            "Hay que recordarlo todo",
            "",
        ).forEach { assertFalse(it, QuickReminders.matches(it)) }
    }

    @Test fun `varios recuérdame seguidos van en una llamada y uno de hace más de 3 min en otra`() {
        fun seg(id: Long, sec: Long) = Segment(id, 1, sec * 1000, sec * 1000 + 3000, "Recuérdame algo")
        // Una tele con tres «recuerda» en un minuto: basta el último, cuya ventana recoge los otros dos.
        assertEquals(listOf(3L), QuickReminders.covering(listOf(seg(1, 0), seg(2, 20), seg(3, 60))).map { it.id })
        // La cola de transcripción con retraso suelta de golpe dos de hace 10 min: cada uno con su ventana.
        assertEquals(listOf(1L, 3L), QuickReminders.covering(listOf(seg(3, 600), seg(1, 0), seg(2, 500))).map { it.id })
        assertEquals(emptyList<Segment>(), QuickReminders.covering(emptyList()))
    }

    @Test fun `entrada del recordatorio rápido usa el momento en que se dijo`() {
        val madrid = ZoneId.of("Europe/Madrid")
        val said = ZonedDateTime.of(2026, 10, 15, 18, 43, 2, 0, madrid).toInstant().toEpochMilli()
        val text = reminderInput(said, listOf(Segment(1, 1, said, said + 3000, "Recuérdame mañana llamar")), emptyList(), madrid)
        assertEquals(
            """
            <momento>
            dicho: jueves 15 de octubre de 2026, 18:43 (2026-10-15T18:43)
            zona: Europe/Madrid
            </momento>
            <tareas_registradas>
            (ninguna)
            </tareas_registradas>
            <transcripcion>
            [18:43:02] Recuérdame mañana llamar
            </transcripcion>
            """.trimIndent(),
            text,
        )
    }
}

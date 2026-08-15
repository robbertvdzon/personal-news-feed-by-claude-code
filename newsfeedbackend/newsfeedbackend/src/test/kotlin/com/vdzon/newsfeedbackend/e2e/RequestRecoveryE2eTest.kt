package com.vdzon.newsfeedbackend.e2e

import com.vdzon.newsfeedbackend.request.NewsRequest
import com.vdzon.newsfeedbackend.request.RequestService
import com.vdzon.newsfeedbackend.request.RequestStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Het opstartherstel van vastgelopen verzoeken:
 * [com.vdzon.newsfeedbackend.request.domain.RequestServiceImpl.resetStuck],
 * bij serverstart aangeroepen door
 * [com.vdzon.newsfeedbackend.request.domain.RequestBootstrap].
 *
 * De begintoestanden worden bewust rechtstreeks met `requestService.upsert(...)`
 * gezet en niet met een écht lopend verzoek dat op een latch wacht: zo'n
 * verzoek zou door de orkestrator alsnog op DONE gezet kunnen worden terwijl
 * de test asserteert. Het resultaat wordt via `GET /api/requests` teruggelezen
 * (niet via de repository), zodat ook de serialisatie meeloopt.
 *
 * `resetStuck()` loopt over álle gebruikers in de database — dus ook over
 * gebruikers van eerder gedraaide tests in deze klasse. Geen enkele test
 * asserteert daarom op de totale lijst of het totale aantal verzoeken; er
 * wordt altijd op de eigen gebruiker(s) en de eigen request-id's gefilterd.
 */
class RequestRecoveryE2eTest : E2eTestBase() {

    @Autowired
    private lateinit var requestService: RequestService

    // ---- helpers -------------------------------------------------------

    /**
     * Registreert een gebruiker en wacht tot `UserRegisteredListener` de twee
     * vaste verzoeken heeft aangemaakt (patroon uit [FixedRequestsE2eTest]).
     * Dat wachten is hier niet alleen voor geval 3 nodig: `ensureFixedRequests`
     * eindigt in een `repo.save(...)` die eerst alle rijen van de gebruiker
     * verwijdert, dus zonder dit anker kan die async stap een net geseed
     * verzoek weer wegvagen.
     */
    private fun registerUserWithFixedRequests(prefix: String = "recovery"): TestUser {
        val user = registerUser(prefix)
        await { getJson("/api/requests", user.token).size() == 2 }
        return user
    }

    /** Zet een verzoek in een begintoestand; geeft het (UUID-vormige) id terug. */
    private fun seedRequest(
        user: TestUser,
        status: RequestStatus,
        subject: String,
        completedAt: Instant? = null
    ): String {
        val id = UUID.randomUUID().toString()
        requestService.upsert(
            user.username,
            NewsRequest(
                id = id,
                subject = subject,
                status = status,
                createdAt = Instant.now(),
                completedAt = completedAt
            )
        )
        return id
    }

    private fun requestById(user: TestUser, id: String): JsonNode =
        getJson("/api/requests", user.token).values().first { it.path("id").asString() == id }

    private fun statusOf(user: TestUser, id: String): String =
        requestById(user, id).path("status").asString()

    /** De `completedAt` uit de HTTP-respons, of null als het veld leeg/afwezig is. */
    private fun completedAtOf(user: TestUser, id: String): String? =
        requestById(user, id).path("completedAt")
            .takeIf { !it.isNull && !it.isMissingNode }
            ?.asString()
            ?.takeIf { it.isNotBlank() }

    private fun hourlyId(user: TestUser) = "hourly-update-${user.username}"
    private fun dailyId(user: TestUser) = "daily-summary-${user.username}"

    // ---- de vijf gevallen ----------------------------------------------

    @Test
    fun `een vastgelopen PROCESSING-verzoek wordt bij opstartherstel FAILED met een gevulde completedAt`() {
        val user = registerUserWithFixedRequests()
        val id = seedRequest(user, RequestStatus.PROCESSING, "Halverwege afgebroken verzoek")

        // Beginsituatie via HTTP vastleggen: nog geen completedAt. Daarmee
        // bewijst de assertie na de reset dat de waarde echt door resetStuck
        // is gezet, en niet al bij het seeden bestond.
        assertEquals("PROCESSING", statusOf(user, id))
        assertNull(completedAtOf(user, id), "een lopend verzoek hoort nog geen completedAt te hebben")

        val voorReset = Instant.now().minusSeconds(1)
        requestService.resetStuck()

        assertEquals("FAILED", statusOf(user, id))
        val completedAt = completedAtOf(user, id)
            ?: fail("resetStuck hoort completedAt te vullen (Instant.now())")
        assertTrue(
            !Instant.parse(completedAt).isBefore(voorReset),
            "completedAt ($completedAt) hoort van de reset te komen, niet van eerder"
        )
    }

    @Test
    fun `een verzoek dat nog in PENDING stond wordt bij opstartherstel ook FAILED`() {
        val user = registerUserWithFixedRequests()
        val id = seedRequest(user, RequestStatus.PENDING, "Nooit opgepakt verzoek")

        assertEquals("PENDING", statusOf(user, id))

        requestService.resetStuck()

        // specs/backend-functional-spec.md §6.6: bij serverstart worden ALLE
        // verzoeken met status PENDING of PROCESSING gereset naar FAILED —
        // dus ook een verzoek dat nog nooit is opgepakt. Wijzigt dit gedrag,
        // dan hoort dat een bewuste keuze te zijn (spec én test aanpassen).
        assertEquals("FAILED", statusOf(user, id))
        assertNotNull(completedAtOf(user, id), "ook een gereset PENDING-verzoek krijgt een completedAt")
    }

    @Test
    fun `afgeronde en geannuleerde verzoeken blijven bij opstartherstel ongemoeid`() {
        val user = registerUserWithFixedRequests()
        val doneAt = Instant.now().minusSeconds(3600)
        val cancelledAt = Instant.now().minusSeconds(1800)
        val doneId = seedRequest(user, RequestStatus.DONE, "Netjes afgerond verzoek", doneAt)
        val cancelledId = seedRequest(user, RequestStatus.CANCELLED, "Door de gebruiker gestopt verzoek", cancelledAt)

        val doneCompletedAt = completedAtOf(user, doneId)
        val cancelledCompletedAt = completedAtOf(user, cancelledId)
        assertNotNull(doneCompletedAt)
        assertNotNull(cancelledCompletedAt)

        requestService.resetStuck()

        assertEquals("DONE", statusOf(user, doneId))
        assertEquals("CANCELLED", statusOf(user, cancelledId))
        assertEquals(doneCompletedAt, completedAtOf(user, doneId), "completedAt van een DONE-verzoek is gewijzigd")
        assertEquals(
            cancelledCompletedAt,
            completedAtOf(user, cancelledId),
            "completedAt van een CANCELLED-verzoek is gewijzigd"
        )

        // De twee vaste verzoeken staan na registratie al op DONE (met een lege
        // completedAt, zie RequestServiceImpl.ensureFixedRequests) en horen dus
        // ook onaangeroerd te blijven.
        assertEquals("DONE", statusOf(user, hourlyId(user)))
        assertEquals("DONE", statusOf(user, dailyId(user)))
        assertNull(completedAtOf(user, dailyId(user)), "het vaste dagelijkse verzoek hoort geen completedAt te krijgen")
    }

    @Test
    fun `het opstartherstel reset vastgelopen verzoeken van alle gebruikers, niet alleen van de aanroeper`() {
        val eerste = registerUserWithFixedRequests("recovery-a")
        val tweede = registerUserWithFixedRequests("recovery-b")
        val eersteId = seedRequest(eerste, RequestStatus.PROCESSING, "Verzoek van gebruiker A")
        val tweedeId = seedRequest(tweede, RequestStatus.PROCESSING, "Verzoek van gebruiker B")

        requestService.resetStuck()

        // Bewust gedrag: resetStuck loopt via auth.listUsernames() over álle
        // gebruikers. Het is startup-herstel na een herstart (RequestBootstrap),
        // geen gebruikersactie — er is dus geen aanroeper wiens verzoeken
        // "van hem" zijn en de rest niet.
        assertEquals("FAILED", statusOf(eerste, eersteId))
        assertEquals("FAILED", statusOf(tweede, tweedeId))
        assertNotNull(completedAtOf(tweede, tweedeId))
    }

    @Test
    fun `een tweede opstartherstel direct erna verandert niets meer`() {
        val user = registerUserWithFixedRequests()
        val processingId = seedRequest(user, RequestStatus.PROCESSING, "Halverwege afgebroken verzoek")
        val pendingId = seedRequest(user, RequestStatus.PENDING, "Nooit opgepakt verzoek")

        requestService.resetStuck()
        val processingCompletedAt = completedAtOf(user, processingId)
        val pendingCompletedAt = completedAtOf(user, pendingId)
        assertNotNull(processingCompletedAt)
        assertNotNull(pendingCompletedAt)

        requestService.resetStuck()

        assertEquals("FAILED", statusOf(user, processingId))
        assertEquals("FAILED", statusOf(user, pendingId))
        assertEquals(
            processingCompletedAt,
            completedAtOf(user, processingId),
            "de tweede reset heeft completedAt opnieuw gezet"
        )
        assertEquals(
            pendingCompletedAt,
            completedAtOf(user, pendingId),
            "de tweede reset heeft completedAt opnieuw gezet"
        )
    }
}

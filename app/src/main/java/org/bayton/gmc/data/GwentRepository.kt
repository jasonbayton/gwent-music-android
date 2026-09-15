package org.bayton.gmc.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.bayton.gmc.data.api.ParitorApi
import org.bayton.gmc.data.auth.ReauthenticationRequired
import org.bayton.gmc.data.auth.Session
import org.bayton.gmc.data.model.validatedList
import org.bayton.gmc.ui.home.AttentionRow
import org.bayton.gmc.ui.home.NextLesson
import org.bayton.gmc.ui.home.Overview
import org.bayton.gmc.ui.home.TermProgress
import org.bayton.gmc.ui.home.UpcomingLesson
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GwentRepository @Inject constructor(
    private val api: ParitorApi,
    private val session: Session,
) {
    // Keyed by the UNIQUE contactActivityItem (one delivery); an activity may have several items.
    private val msgByItem = java.util.concurrent.ConcurrentHashMap<String, org.bayton.gmc.data.model.SecureMessage>()
    private val bodyByItem = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val readItemIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Locally mark a message (by its unique item id) read. */
    fun markRead(itemId: String) { readItemIds += itemId }

    /** Drop all in-memory account state (used on disconnect, so a new sign-in starts clean). */
    fun clearCaches() {
        msgByItem.clear(); bodyByItem.clear(); readItemIds.clear(); teacherNames.clear()
    }

    /** Attempt the spec's item update and verify persistence; the portal's write path is unconfirmed. */
    suspend fun markReadRemote(itemId: String): Boolean = ReadReceiptWriter(api).markRead(itemId)

    /** Resolve a message by its unique item id, re-fetching the list if the cache was cleared. */
    suspend fun messageByItem(box: String, itemId: String): org.bayton.gmc.data.model.SecureMessage? {
        msgByItem[itemId]?.let { return it }
        runCatching { messages(sent = box == "sent") }
        return msgByItem[itemId]
    }

    /** The message body lives behind a detail call keyed by the activity id (list has only ids). */
    suspend fun messageBody(m: org.bayton.gmc.data.model.SecureMessage): String? {
        val account = session.snapshot() ?: return null
        m.contactActivityItem?.let { bodyByItem[it] }?.let { return it }
        val id = m.contactActivity ?: return m.plainContent?.let { clean(it) }
        val act = runCatching { api.contactActivity(id).model }.getOrNull()
            ?: return m.plainContent?.let { clean(it) }
        var body = (act.plainContent?.takeIf { it.isNotBlank() } ?: act.htmlContent ?: m.plainContent ?: "")
        // Substitute merge fields the way the portal does: tags (on the activity) zip values (on the item).
        val tags = act.substitutionTags?.split("|")?.map { it.trim() }.orEmpty()
        if (tags.isNotEmpty() && !m.contactActivityItem.isNullOrBlank()) {
            val values = runCatching { api.contactActivityItem(m.contactActivityItem).model?.substitutionValues }
                .getOrNull()?.split("|").orEmpty()
            tags.forEachIndexed { i, tag -> if (tag.isNotEmpty()) body = body.replace("{$tag}", values.getOrNull(i)?.trim() ?: "") }
        }
        val rendered = clean(body)
        return session.ifCurrent(account) {
            m.contactActivityItem?.let { bodyByItem[it] = rendered }
            rendered
        }
    }
    private fun clean(s: String) = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|h[1-6])>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("\\r\\n?"), "\n")                        // normalise CRLF -> LF
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("[ \\t]+\\n"), "\n")                     // trim trailing spaces per line
        .replace(Regex("\\n{3,}"), "\n\n")                      // at most one blank line
        .trim()


    /** Resolve the parent's ContactID from their email (or B2C oid). Caches in Session. */
    suspend fun resolveContact(email: String?, oid: String?): String? {
        // Only reuse the cached ContactID when it belongs to THIS identity (email), so a different
        // user signing in never inherits the previous account's pivot.
        if (session.contactId != null && !email.isNullOrBlank() && session.email == email) {
            val cid = session.contactId!!
            session.activate(cid, email)
            return cid
        }
        session.clear()
        clearCaches()
        // Resolve the ContactID by email first, then fall back to the Schooble-ID (oid) lookup, a
        // different endpoint that can resolve when the email lookup is unavailable or rejected.
        // Validate each envelope so a success=false or aborted reply is a failure, not an empty
        // "no account" match. Return null (genuinely no matching contact) only when every attempted
        // lookup completed and came back empty. If any unresolved path errored and nothing matched,
        // including a mixed empty-plus-error result, surface the error so sign-in offers a retry: an
        // error can mask a real match, and a false "no account" for an account that exists is the
        // worse failure.
        var lastError: Throwable? = null
        var c: String? = null
        if (!email.isNullOrBlank()) c =
            try { api.contactByEmail(email).validatedList().firstOrNull()?.contactID }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { lastError = e; null }
        if (c == null && !oid.isNullOrBlank()) c =
            try { api.contactBySchoobleContactId(oid).validatedList().firstOrNull()?.contactID }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { lastError = e; null }
        if (c == null && lastError != null) throw lastError!!   // any unresolved lookup errored: retryable, not "no account"
        if (c != null) session.activate(c, email)
        return c
    }

    /** True when there is no signed-in contact to load for; the dashboard shows a reconnect prompt. */
    fun requiresReauth(): Boolean = session.contactId == null

    /** Header greeting from the local time, so the ViewModel can render it before any network call. */
    fun homeGreeting(): String {
        val h = LocalDateTime.now().hour
        return when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
    }
    /** Header date line, rendered immediately alongside the greeting. */
    fun homeDateLine(): String = "${LocalDate.now().format(DATE_LINE)} · ${term()}"

    /**
     * Fast primary module: who the account is for and the soonest upcoming lessons. Uses a narrow
     * FORWARD-only diary window - next/nextUp only ever use lessons after now, so this stays quick
     * and does not carry the wide back-window the term-progress dots need.
     */
    suspend fun homeOverview(): Overview {
        val cid = session.contactId ?: throw ReauthenticationRequired()
        val today = LocalDate.now()
        val diary = validatedDiary(api.diary(cid, today.toString(), today.plusMonths(4).toString()))
        val pupilNames = runCatching { api.relatedPupils(cid).modelList.mapNotNull { it.firstName?.trim()?.ifBlank { null } } }
            .getOrDefault(emptyList())

        val now = LocalDateTime.now()
        val parsed = diary.mapNotNull { d -> d.startDateTime?.let { parse(it)?.let { dt -> dt to d } } }.sortedBy { it.first }
        val upcoming = parsed.filter { it.first.isAfter(now) }

        // Decide multi-pupil from BOTH the related-pupils list and the diary, so a transient
        // relatedPupils failure can't collapse a genuinely two-child account to a single card.
        val diaryChildren = upcoming.mapNotNull { it.second.firstName?.trim()?.ifBlank { null } }.distinct()
        val names = pupilNames.ifEmpty { diaryChildren }
        val multi = maxOf(pupilNames.size, diaryChildren.size) > 1

        // Single-pupil accounts keep the one detailed next-lesson card.
        val next = if (multi) null else upcoming.firstOrNull()?.let { (dt, d) ->
            val (subject, teacher) = DiaryText.subjectAndTeacher(d.subject)
            NextLesson(
                subject = subject,
                location = d.location, room = d.subLocation ?: d.venueRoom,
                dateLabel = dt.format(DAY), timeLabel = dt.format(TIME),
                relative = relative(now, dt), teacher = teacher, teacherContactId = d.teacherID,
                startIso = d.startDateTime,
            )
        }
        // Multiple pupils get a compact "Next up" list, each row labelled with the child.
        val nextUp = if (multi) upcoming.take(5).map { (dt, d) ->
            val (subject, teacher) = DiaryText.subjectAndTeacher(d.subject)
            UpcomingLesson(
                child = d.firstName?.trim().orEmpty(),
                subject = subject, teacher = teacher, location = d.location,
                dateLabel = dt.format(DOW), timeLabel = dt.format(TIME), relative = relative(now, dt),
                teacherContactId = d.teacherID, startIso = d.startDateTime,
            )
        } else emptyList()

        return Overview(who = HomeCompute.who(names), next = next, nextUp = nextUp)
    }

    /** Recompute a cached overview's date/time/relative labels against the current clock, so a snapshot
     *  restored a day later shows "today" rather than the "tomorrow" it was saved with. */
    fun relabelOverview(o: Overview): Overview {
        val now = LocalDateTime.now()
        fun next(n: NextLesson): NextLesson {
            val dt = n.startIso?.let { parse(it) } ?: return n
            return n.copy(dateLabel = dt.format(DAY), timeLabel = dt.format(TIME), relative = relative(now, dt))
        }
        fun up(u: UpcomingLesson): UpcomingLesson {
            val dt = u.startIso?.let { parse(it) } ?: return u
            return u.copy(dateLabel = dt.format(DOW), timeLabel = dt.format(TIME), relative = relative(now, dt))
        }
        return o.copy(next = o.next?.let(::next), nextUp = o.nextUp.map(::up))
    }

    /**
     * Term-progress module: a dot per planned lesson in the current term, filled for those already
     * gone.
     *
     * The old approach fetched one blanket window (today-150 .. today+4mo) so it could see the whole
     * current term. That ~9-month range is exactly the request that times out when the Paritor Diary
     * endpoint is slow, which is why the dots disappear on a bad day while the narrow next-up query
     * still loads. Instead: identify the current term from a NARROW probe (a webapp-sized query that
     * keeps working when the endpoint is struggling), resolve that term's real span through the light
     * AcademicYearTerm endpoint (a single record, not a date-range scan), then read exactly that one
     * term. The resulting diary query is ~one term wide, so it behaves like the queries that succeed.
     */
    suspend fun termProgress(): TermProgress {
        val cid = session.contactId ?: throw ReauthenticationRequired()
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val empty = TermProgress(0, 0)

        // Narrow probe just to name the current term (its next upcoming, else most recent, lesson).
        val probe = validatedDiary(api.diary(cid, today.minusDays(31).toString(), today.plusDays(45).toString()))
        val term = AcademicTerms.currentTerm(probe, now)
        // No current term, or ad-hoc/holiday tuition (null term id): no progress dots apply.
        if (term == null || term.term.isNullOrBlank()) return empty

        // Resolve the term's real bounds (light lookup; throws if unavailable), then read the whole
        // term - EVERY planned lesson - so the dot count and the completed tally are accurate. Pad the
        // span by a fortnight each side in case the term record's nominal dates sit just inside the
        // first/last lesson; the term-id filter keeps any neighbouring term's lessons out, so padding
        // can only ever recover a stray lesson of THIS term, never inflate the count. Still ~one term
        // wide, so it behaves like the narrow queries that keep working when the endpoint is slow.
        val bounds = historyTermDates(term)
        val termDiary = validatedDiary(api.diary(cid, bounds.start.minusDays(14).toString(), bounds.end.plusDays(15).toString()))
        val entries = termDiary.filter { it.academicYear == term.academicYear && it.academicYearTerm == term.term }
        return TermProgress(
            total = entries.size,
            completed = entries.count { AcademicTerms.parse(it.startDateTime)?.isBefore(now) == true },
        )
    }

    /** Messages module: the received-inbox summary shown as the Home attention banner. */
    suspend fun homeMessages(): List<AttentionRow> {
        val cid = session.contactId ?: throw ReauthenticationRequired()
        val received = api.messagesReceived(cid, take = 25).validatedList()
        val unread = received.count { it.viewedDate.isNullOrBlank() }
        val latestSubjects = received.take(2).joinToString(" · ") { it.subject?.trim().orEmpty() }.ifBlank { "Inbox" }
        return if (received.isNotEmpty())
            listOf(AttentionRow("mail", "Messages", latestSubjects, unread, "messages")) else emptyList()
    }

    private fun parse(s: String) = runCatching { LocalDateTime.parse(s.take(19)) }.getOrNull()
    private fun relative(now: LocalDateTime, dt: LocalDateTime): String {
        val d = ChronoUnit.DAYS.between(now.toLocalDate(), dt.toLocalDate())
        return when { d <= 0L -> "today"; d == 1L -> "tomorrow"; d < 7L -> "in $d days"; else -> "in ${d / 7} week${if (d / 7 > 1) "s" else ""}" }
    }
    private fun term(): String {
        val m = LocalDate.now().monthValue
        return when { m in 9..12 -> "Autumn term"; m in 1..3 -> "Spring term"; else -> "Summer term" }
    }
    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
        val DOW: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val DATE_LINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    }

    // Messages
    suspend fun messages(sent: Boolean): List<org.bayton.gmc.data.model.SecureMessage> {
        val account = session.snapshot() ?: return emptyList()
        val cid = session.contactId ?: return emptyList()
        val raw = if (sent) api.messagesSent(cid, take = 50).validatedList()
        else api.messagesReceived(cid, take = 50, includeRead = true).validatedList()
        // apply locally-remembered read state on top of the server data
        return session.ifCurrent(account) {
            val list = raw.map { m ->
                if (!m.contactActivityItem.isNullOrBlank() && m.contactActivityItem in readItemIds && m.viewedDate.isNullOrBlank())
                    m.copy(viewedDate = "read") else m
            }
            list.forEach { m -> m.contactActivityItem?.let { msgByItem[it] = m } }
            list
        } ?: emptyList()
    }

    /** Send a secure message. The sender is the signed-in parent (userID); the recipient contact is
     *  required (a message with no recipient is not delivered). Line breaks are carried as <br> in the
     *  HTML body so the portal renders them. */
    suspend fun sendMessage(subject: String, body: String, toContactId: String, toEmail: String? = null): Boolean {
        val cid = session.contactId ?: return false
        if (toContactId.isBlank()) return false
        // The client generates the activity id and links each recipient item to it (the item FK
        // requires the parent activity to exist under the same id).
        val activityId = java.util.UUID.randomUUID().toString()
        val model = org.bayton.gmc.data.model.ContactActivityModel(
            contactActivity = activityId,
            userID = cid,
            subject = subject,
            plainContent = body,
            htmlContent = body.escapeHtml().replace("\n", "<br>"),
            contactActivityType = 11,
            contactActivityItemModels = listOf(
                org.bayton.gmc.data.model.ContactActivityItemModel(
                    contactActivity = activityId,
                    contactActivityItem = java.util.UUID.randomUUID().toString(),
                    contactID = toContactId,
                    toAddress = toEmail?.ifBlank { null },
                ),
            ),
        )
        return runCatching { api.sendStandardMail(model).success }.getOrDefault(false)
    }
    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // Schedule
    // Wide enough back to cover the whole current term (a term is <= ~4 months) so its past lessons
    // are available, and far enough ahead to catch a next term the vendor has already published.
    suspend fun schedule(fromDaysBack: Long = 150, toMonthsAhead: Long = 6): List<org.bayton.gmc.data.model.DiaryEntry> {
        val cid = session.contactId ?: return emptyList()
        val today = java.time.LocalDate.now()
        return validatedDiary(api.diary(cid, today.minusDays(fromDaysBack).toString(), today.plusMonths(toMonthsAhead).toString()))
    }

    /** Resolve only a term discovered in this account's diary, through the normal authenticated API. */
    suspend fun historyTermDates(key: TermKey): HistoryTermBounds {
        val id = key.term?.takeIf { it.isNotBlank() }
            ?: throw org.bayton.gmc.data.model.ServiceResponseException("Term dates are unavailable for these lessons.")
        val reply = api.academicTerm(id)
        val model = reply.model
        val start = model?.fromDate?.let { AcademicTerms.parse(it)?.toLocalDate() }
        val end = model?.toDate?.let { AcademicTerms.parse(it)?.toLocalDate() }
        if (!reply.success || !reply.internetConnection || reply.abort || model?.academicYearTerm != id ||
            model.academicYear != key.academicYear || start == null || end == null || end < start ||
            start.year < 1 || end > start.plusYears(2)) {
            throw org.bayton.gmc.data.model.ServiceResponseException("The service could not provide valid term dates.")
        }
        return HistoryTermBounds(start, end)
    }

    /**
     * A single bounded diary window (inclusive dates). The Schedule agenda loads several of these
     * narrow windows instead of one ~10-month query, so a slow Diary endpoint degrades to a partial
     * agenda rather than failing the whole page; History paging uses it too.
     */
    suspend fun diaryBetween(start: LocalDate, end: LocalDate): List<org.bayton.gmc.data.model.DiaryEntry> {
        val cid = session.contactId ?: return emptyList()
        require(!end.isBefore(start))
        // UptoDate may be interpreted as midnight/exclusive. Include the next day in the transport
        // request; callers filter back to the logical inclusive range before merging.
        return validatedDiary(api.diary(cid, start.toString(), end.plusDays(1).toString()))
    }

    /** One bounded History page. The caller owns the date cursor and keeps successful pages. */
    suspend fun scheduleHistory(start: LocalDate, end: LocalDate): List<org.bayton.gmc.data.model.DiaryEntry> =
        diaryBetween(start, end)

    private fun validatedDiary(envelope: org.bayton.gmc.data.model.Envelope<org.bayton.gmc.data.model.DiaryEntry>): List<org.bayton.gmc.data.model.DiaryEntry> {
        val entries = envelope.validatedList(requireComplete = true)
        if (entries.any { it.startDateTime?.let(::parse) == null })
            throw org.bayton.gmc.data.model.ServiceResponseException("The service returned an incomplete lesson schedule. Please try again.")
        return entries.sortedBy { it.startDateTime }
    }

    // Pupils
    suspend fun pupils(): List<org.bayton.gmc.data.model.RelatedPupil> {
        val cid = session.contactId ?: return emptyList()
        return api.relatedPupils(cid).validatedList()
    }

    // Pupil activities
    private val teacherNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun teacherName(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val account = session.snapshot() ?: return null
        teacherNames[id]?.let { return it }
        val c = runCatching { api.contactById(id).model }.getOrNull() ?: return null
        val name = listOfNotNull(c.firstName?.trim(), c.lastName?.trim()).joinToString(" ")
            .ifBlank { c.contactName?.trim().orEmpty() }
        return session.ifCurrent(account) {
            if (name.isNotBlank()) teacherNames[id] = name
            name.ifBlank { null }
        }
    }

    // Let failures propagate so the ViewModel can distinguish an error (with retry) from a genuine empty list.
    suspend fun pupilActivities(pupilId: String): List<org.bayton.gmc.ui.pupils.PupilActivity> {
        val rows = api.tuitionApplicants(pupilId).modelList
        // Suffix with the source index so the row key is unique even when the API omits an id.
        return rows.mapIndexed { index, r ->
            val (activity, venue) = PupilActivityText.splitNarrative(r.narrative, r.venueContactName)
            val stable = r.tuitionApplicant?.takeIf { it.isNotBlank() } ?: "${r.applicationRef.orEmpty()}-${r.startDate.orEmpty()}"
            org.bayton.gmc.ui.pupils.PupilActivity(
                id = "$stable#$index",
                ref = r.applicationRef?.trim()?.takeUnless { it.equals("Progressed", true) }.orEmpty(),
                progressed = r.applicationRef?.trim().equals("Progressed", true) || !r.progressedFrom.isNullOrBlank(),
                activity = activity,
                venue = venue,
                teacher = teacherName(r.teacherID),
                statusLabel = PupilActivityText.statusLabel(r.status),
                accepted = r.status?.trim().equals("A", true),
                startDate = r.startDate,
                academicYear = PupilActivityText.academicYearOf(r.startDate),
                hireRequired = r.hireRequired,
            )
        }.sortedByDescending { it.startDate ?: "" }
    }

    /** narrative is "Activity at Venue"; fall back to the venue name field when it isn't. */

    // Reports
    suspend fun reports(): List<org.bayton.gmc.data.model.ReportItem> {
        val cid = session.contactId ?: return emptyList()
        return api.reports(cid).modelList
    }

    // Billing
    suspend fun debtor(): org.bayton.gmc.data.model.Debtor? {
        val account = session.snapshot() ?: return null
        val email = session.email ?: return null
        val d = api.debtorByEmail(email).validatedList().firstOrNull()
        return session.ifCurrent(account) {
            d?.debtor?.let { session.debtorId = it }
            d
        }
    }
    private suspend fun debtorId(): String? = session.debtorId ?: debtor()?.debtor
    suspend fun wallet(): List<org.bayton.gmc.data.model.WalletItem> {
        val d = debtorId() ?: return emptyList()
        return api.wallet(d).modelList
    }

    // Account
    // Let failures propagate so the Account screen can tell a refresh failure (keep cache + freshness)
    // from a genuinely empty account.
    suspend fun account(): org.bayton.gmc.data.model.Contact? {
        val email = session.email ?: return null
        return api.contactByEmail(email).validatedList().firstOrNull()
    }
    fun signedInEmail(): String? = session.email

    /** Teachers from the diary, with the teacher's contact id (a valid message recipient). */
    suspend fun teachers(): List<org.bayton.gmc.ui.teacher.Teacher> = schedule().mapNotNull { d ->
        val id = d.teacherID?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val name = DiaryText.subjectAndTeacher(d.subject).second?.ifBlank { null } ?: "Teacher"
        org.bayton.gmc.ui.teacher.Teacher(id, name)
    }.distinctBy { it.contactId }

    /** Everyone the parent can start a message to: diary teachers plus anyone who has messaged them
     *  (e.g. office/newsletter senders), deduplicated by contact id (teachers listed first). */
    suspend fun messageRecipients(): List<org.bayton.gmc.ui.teacher.Recipient> {
        // A partial directory is useful, but two failed sources must reach the UI's retry state.
        // Cancellation is control flow, never an empty or partially successful directory.
        suspend fun <T> source(block: suspend () -> T): Result<T> {
            currentCoroutineContext().ensureActive()
            return try {
                Result.success(block())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
        val teacherResult = source { teachers() }
        val senderResult = source { messages(sent = false) }
        currentCoroutineContext().ensureActive()
        if (teacherResult.isFailure && senderResult.isFailure) {
            throw IllegalStateException("Unable to load message recipients. Please try again.", teacherResult.exceptionOrNull())
                .apply { addSuppressed(senderResult.exceptionOrNull()!!) }
        }
        val teachers = teacherResult.getOrDefault(emptyList())
            .map { org.bayton.gmc.ui.teacher.Recipient(it.contactId, it.name, "Instrumental teacher") }
        val senders = senderResult.getOrDefault(emptyList()).mapNotNull { m ->
            val id = m.fromContactID?.trim()?.ifBlank { null } ?: return@mapNotNull null
            val name = listOfNotNull(m.firstName?.trim(), m.lastName?.trim()).joinToString(" ").ifBlank { "Gwent Music" }
            org.bayton.gmc.ui.teacher.Recipient(id, name, "Previous sender")
        }
        return (teachers + senders).distinctBy { it.contactId }
    }

}

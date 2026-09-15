package org.bayton.gmc.data.api

import org.bayton.gmc.data.model.*
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Paritor Xperios API - the parent-relevant, spike-verified operations. The OkHttp interceptor
 * adds `Authorization: Bearer <token>` and the `ApiKey` header (see NetworkModule).
 * Identity chain: token email/oid -> Contact -> ContactID -> everything.
 */
interface ParitorApi {

    // Identity
    @GET("api/Contact/GetContactModelListByEmail")
    suspend fun contactByEmail(@Query("Email") email: String): Envelope<Contact>

    @GET("api/Contact/GetContactModelListBySchoobleContactID")
    suspend fun contactBySchoobleContactId(@Query("SchoobleContactID") oid: String): Envelope<Contact>

    @GET("api/Contact/SelectRelatedPupils")
    suspend fun relatedPupils(@Query("ContactID") contactId: String): Envelope<RelatedPupil>

    @GET("api/Contact/GetContactModel")
    suspend fun contactById(@Query("ContactID") contactId: String): EnvelopeOne<Contact>

    // Pupil activities (enrolments and applications)
    @GET("api/TuitionApplicant/GetTuitionApplicantModelListByPupilID")
    suspend fun tuitionApplicants(@Query("PupilID") pupilId: String): Envelope<TuitionApplicant>

    // Schedule
    @GET("api/AcademicYearTerm/GetAcademicYearTermModel")
    suspend fun academicTerm(@Query("AcademicYearTerm") term: String): EnvelopeOne<AcademicTermDates>

    @GET("api/Diary/SelectPupilRecordsByContactDate")
    suspend fun diary(
        @Query("ContactID") contactId: String,
        @Query("StartDate") startDate: String,
        @Query("UptoDate") uptoDate: String,
    ): Envelope<DiaryEntry>

    // Messages
    @GET("api/ContactActivity/SelectEmailTextSecureMessagesReceived")
    suspend fun messagesReceived(
        @Query("ContactID") contactId: String,
        @Query("Take") take: Int = 25,
        @Query("Offset") offset: Int = 0,
        @Query("IncludeRead") includeRead: Boolean = true,
    ): Envelope<SecureMessage>

    @GET("api/ContactActivity/SelectEmailTextSecureMessagesSent")
    suspend fun messagesSent(
        @Query("ContactID") contactId: String,
        @Query("Take") take: Int = 25,
        @Query("Offset") offset: Int = 0,
    ): Envelope<SecureMessage>

    @GET("api/ContactActivity/GetContactActivityModel")
    suspend fun contactActivity(@Query("ContactActivity") id: String): EnvelopeOne<ContactActivityDetail>

    @GET("api/ContactActivityItem/GetContactActivityItemModel")
    suspend fun contactActivityItem(@Query("ContactActivityItem") id: String): EnvelopeOne<ContactActivityItemDetail>

    // Raw round-trip for marking read: fetch the full item, set viewedDate, POST it back unchanged.
    @GET("api/ContactActivityItem/GetContactActivityItemModel")
    suspend fun contactActivityItemRaw(@Query("ContactActivityItem") id: String): JsonObject

    @retrofit2.http.POST("api/ContactActivityItem/UpdateContactActivityItemModel")
    suspend fun updateContactActivityItem(@retrofit2.http.Body body: JsonObject): JsonObject

    @POST("api/ContactActivity/SendStandardMail")
    suspend fun sendStandardMail(
        @Body body: ContactActivityModel,
        @Query("ServiceType") serviceType: String = "",
    ): OperationResult

    // Reports
    @GET("api/ReportPupil/SelectByPupilOrDebtor")
    suspend fun reports(
        @Query("ContactID") contactId: String,
        @Query("ReportPeriod") reportPeriod: String = "",
    ): Envelope<ReportItem>
    @GET("api/Debtor/GetDebtorModelListByEmail")
    suspend fun debtorByEmail(@Query("Email") email: String): Envelope<Debtor>

    @GET("api/DLInvoice/SelectDashboardWalletByDebtor")
    suspend fun wallet(@Query("Debtor") debtor: String): Envelope<WalletItem>

    // Health (key only)
    @GET("api/ConnectionManager/CheckConnection")
    suspend fun checkConnection(): Boolean
}

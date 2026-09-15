package org.bayton.gmc.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required

/** Every Paritor response is this envelope. */
@Serializable
data class Envelope<T>(
    @Required val modelList: List<T> = emptyList(),
    val success: Boolean = false,
    val resultInformation: String? = null,
    val internetConnection: Boolean = true,
    val abort: Boolean = false,
    val nextLink: String? = null,
)

/** The service replied (HTTP 200) but the response itself is not usable - a service problem, distinct
 *  from the device being offline, so freshness can say "Gwent Music is having trouble" not "Offline". */
class ServiceResponseException(message: String) : java.io.IOException(message)

/** HTTP success is not application success. A partial diary must not look like removed lessons. */
fun <T> Envelope<T>.validatedList(requireComplete: Boolean = false): List<T> {
    if (!success || abort || !internetConnection || (requireComplete && !nextLink.isNullOrBlank()))
        throw ServiceResponseException("The service could not provide a complete response. Please try again.")
    return modelList
}

@Serializable
data class EnvelopeOne<T>(
    val model: T? = null,
    val success: Boolean = false,
    val internetConnection: Boolean = true,
    val abort: Boolean = false,
)

/** Write acknowledgements need not carry the list required by list-reading endpoints. */
@Serializable
data class OperationResult(val success: Boolean = false)

/** Authoritative date bounds for a discovered term, not inferred from a partial diary page. */
@Serializable
data class AcademicTermDates(
    val academicYearTerm: String? = null,
    val academicYear: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
)

@Serializable
data class ContactActivityDetail(
    val subject: String? = null,
    val plainContent: String? = null,
    val htmlContent: String? = null,
    val fromAddress: String? = null,
    val dateStamp: String? = null,
    val substitutionTags: String? = null,   // pipe-delimited token names, e.g. "DebtorName|Subject"
)

@Serializable
data class ContactActivityItemDetail(
    val substitutionValues: String? = null, // pipe-delimited values matching the activity's tags
)

@Serializable
data class Contact(
    val contactID: String? = null,
    val contactName: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val town: String? = null,
    val county: String? = null,
    val postCode: String? = null,
    val mobile1: String? = null,
    val landLine1: String? = null,
    val email: String? = null,
)

@Serializable
data class RelatedPupil(
    val pupilID: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val relationshipTypeDsc: String? = null,
)

@Serializable
data class DiaryEntry(
    val diaryID: String? = null,
    val startDateTime: String? = null,
    val duration: Int? = null,
    val activityRateDuration: Int? = null,
    val subject: String? = null,
    val firstName: String? = null,   // the pupil this lesson is for
    val lastName: String? = null,
    val teacherID: String? = null,   // the teacher's contact id (a valid message recipient)
    val location: String? = null,
    val subLocation: String? = null,
    val venueRoom: String? = null,
    val virtual: Boolean = false,
    val academicYear: String? = null,      // academic-year id (groups terms into a year)
    val academicYearTerm: String? = null,  // term id; null for ad-hoc/holiday tuition
    val sessionNo: Int? = null,            // lesson number within its plan
    val description: String? = null,
    val taskResult: String? = null,
)

@Serializable
data class SecureMessage(
    val subject: String? = null,
    val contactActivity: String? = null,        // activity id -> fetch body via GetContactActivityModel
    val contactActivityItem: String? = null,    // item id (not the body)
    val plainContent: String? = null,           // present on some list responses (e.g. mock)
    val contactActivityType: Int? = null,
    val fromContactID: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val dateStamp: String? = null,
    val viewedDate: String? = null,             // null => unread
)


@Serializable
data class Debtor(
    val debtor: String? = null,
    val accountNo: String? = null,
    val outstandingBalance: Double? = null,
    val scheduledPayments: Double? = null,
    val ddAmount: Double? = null,
    val ddNextPaymentDate: String? = null,
    val lastPaymentDate: String? = null,
)

@Serializable
data class ReportItem(
    val reportPupilID: String? = null,
    val title: String? = null,
    val reportName: String? = null,
    val pupilName: String? = null,
    val reportPeriod: String? = null,
    val publishedDate: String? = null,
    val dateStamp: String? = null,
)



@Serializable
data class WalletItem(
    val outstandingDebt: Double? = null,
    val nextInstalment: Double? = null,
    val dueDate: String? = null,
    val paidValue: Double? = null,
    val transValue: Double? = null,
    val transDate: String? = null,
    val cardType: String? = null,
    val last4Digits: String? = null,
    val expires: String? = null,
    val invoiceHeading: String? = null,
    val academicYearNo: Int? = null,
)

/** A pupil's tuition application/enrolment (TuitionApplicant/GetTuitionApplicantModelListByPupilID).
 *  Historical + active enrolments and pending applications, one row each. */
@Serializable
data class TuitionApplicant(
    val tuitionApplicant: String? = null,   // unique id
    val pupilID: String? = null,
    val applicationRef: String? = null,     // "DEMO-BOOKING-001" or "Progressed"
    val status: String? = null,             // single-letter: A=Accepted, etc.
    val narrative: String? = null,          // "Brass at Example Secondary School" (activity at venue)
    val startDate: String? = null,
    val finishDate: String? = null,
    val acceptedDate: String? = null,
    val hireRequired: Boolean = false,
    val progressedFrom: String? = null,     // set => carried over from a prior year
    val teacherID: String? = null,
    val venueContactName: String? = null,
)

/** One recipient delivery on an outgoing activity. contactActivity links it to the parent activity;
 *  contactActivityItem is this delivery's own id; contactID is the recipient contact. */
@Serializable
data class ContactActivityItemModel(
    val contactActivity: String? = null,
    val contactActivityItem: String? = null,
    val contactID: String? = null,
    val toAddress: String? = null,
)

/** Body for ContactActivity/SendStandardMail, matched to the live model:
 *  the client generates contactActivity (the activity id) and sets it on each item so the item FK
 *  resolves; userID is the SENDER (maps to tblContactActivity.ContactID); recipients go in
 *  contactActivityItemModels; a secure message is type 11. */
@Serializable
data class ContactActivityModel(
    val contactActivity: String? = null,
    val userID: String? = null,
    val subject: String? = null,
    val plainContent: String? = null,
    val htmlContent: String? = null,
    val contactActivityType: Int = 11,
    val contactActivityItemModels: List<ContactActivityItemModel> = emptyList(),
)

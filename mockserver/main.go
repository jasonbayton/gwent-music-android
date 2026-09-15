// Seeded mock of the Paritor Xperios API - just the parent-relevant endpoints the app uses.
// Envelope + field shapes mirror the OpenAPI v2 spec. Run: `go run .` (listens on :8099).
// The Android debug build points at http://10.0.2.2:8099/ (emulator loopback).
// All people, venues, references and message examples are synthetic demo data.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"
)

const contactID = "mock-contact-0001"

type env struct {
	ModelList          any     `json:"modelList"`
	Success            bool    `json:"success"`
	ResultInformation  string  `json:"resultInformation"`
	InternetConnection bool    `json:"internetConnection"`
	Abort              bool    `json:"abort"`
	NextLink           *string `json:"nextLink"`
}

func ok(w http.ResponseWriter, list any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(env{ModelList: list, Success: true, InternetConnection: true})
}

// next 13 weekly brass lessons, Thursdays 08:00 from the coming Thursday
func nextWeekday(from time.Time, wd time.Weekday, hour int) time.Time {
	d := from
	for d.Weekday() != wd || d.Before(from) {
		d = d.AddDate(0, 0, 1)
	}
	return time.Date(d.Year(), d.Month(), d.Day(), hour, 0, 0, 0, time.Local)
}

func diary() []map[string]any {
	now := time.Now()
	out := []map[string]any{}
	// Alex's weekly brass, Thursdays 08:00. Subject carries a "- TE" teacher-initials tag to exercise stripping.
	mStart := nextWeekday(now, time.Thursday, 8)
	for i := -2; i < 11; i++ {
		s := mStart.AddDate(0, 0, 7*i)
		out = append(out, map[string]any{
			"diaryID":       fmt.Sprintf("diary-m-%02d", i+2),
			"startDateTime": s.Format("2006-01-02T15:04:05"),
			"duration":      30, "activityRateDuration": 30,
			"subject":   "Brass - TE at Example Secondary School with Taylor Example",
			"firstName": "Alex", "lastName": "Sample", "teacherID": "mock-teacher-one",
			"location": "Example Secondary School", "subLocation": "Room X2", "venueRoom": "X2",
			"virtual": false, "academicYearTerm": "Autumn",
		})
	}
	// A second pupil's lessons (Sam, strings, Wednesdays 16:00) to exercise the multi-pupil dashboard and schedule labels.
	if os.Getenv("EXTRA_PUPIL") == "1" {
		sStart := nextWeekday(now, time.Wednesday, 16)
		for i := -1; i < 11; i++ {
			s := sStart.AddDate(0, 0, 7*i)
			out = append(out, map[string]any{
				"diaryID":       fmt.Sprintf("diary-s-%02d", i+1),
				"startDateTime": s.Format("2006-01-02T15:04:05"),
				"duration":      20, "activityRateDuration": 20,
				"subject":   "Strings - CE at Example Studio with Casey Example",
				"firstName": "Sam", "lastName": "Sample", "teacherID": "mock-teacher-two",
				"location": "Example Studio", "subLocation": "Room 4", "venueRoom": "4",
				"virtual": false, "academicYearTerm": "Autumn",
			})
		}
	}
	return out
}

func slug(s string) string {
	b := strings.Builder{}
	for _, r := range strings.ToLower(s) {
		if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' {
			b.WriteRune(r)
		} else if r == ' ' {
			b.WriteRune('-')
		}
	}
	return b.String()
}

// The real API's message list carries only IDs; the body is fetched via GetContactActivityModel.
func msg(subject, from, dateISO string, read bool) map[string]any {
	m := map[string]any{
		"subject": subject, "firstName": from, "lastName": "",
		"contactActivity": "act-" + slug(subject), "contactActivityItem": "item-" + slug(subject),
		"contactActivityType": 11, "fromContactID": "sender-" + slug(from), "dateStamp": dateISO,
	}
	if read {
		m["viewedDate"] = dateISO
	} else {
		m["viewedDate"] = nil
	}
	return m
}

func main() {
	addr := flag.String("addr", ":8099", "listen address")
	flag.Parse()
	log.Printf("gmc mock listening on %s (contactID=%s)", *addr, contactID)
	log.Fatal(http.ListenAndServe(*addr, logging(newMux())))
}

// Tests exercise the same handlers as the development server without binding a port.
func newMux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/AcademicYearTerm/GetAcademicYearTermModel", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Query().Get("AcademicYearTerm") != "Autumn" {
			_ = json.NewEncoder(w).Encode(map[string]any{"success": false})
			return
		}
		// Cover the entire relative-date fixture, including all returned pages.
		now := time.Now()
		_ = json.NewEncoder(w).Encode(map[string]any{"success": true, "internetConnection": true,
			"model": map[string]any{"academicYearTerm": "Autumn", "academicYear": nil,
				"fromDate": now.AddDate(0, -1, 0).Format("2006-01-02T00:00:00"),
				"toDate":   now.AddDate(0, 4, 0).Format("2006-01-02T00:00:00")}})
	})
	mux.HandleFunc("/api/ConnectionManager/CheckConnection", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte("true"))
	})
	contact := []map[string]any{{"contactID": contactID, "firstName": "Sample", "lastName": "Parent", "addressLine1": "2 Example Street"}}
	mux.HandleFunc("/api/Contact/GetContactModelListByEmail", func(w http.ResponseWriter, r *http.Request) { ok(w, contact) })
	mux.HandleFunc("/api/Contact/GetContactModelListBySchoobleContactID", func(w http.ResponseWriter, r *http.Request) { ok(w, contact) })
	mux.HandleFunc("/api/Contact/SelectRelatedPupils", func(w http.ResponseWriter, r *http.Request) {
		pupils := []map[string]any{{"pupilID": "mock-pupil-one", "firstName": "Alex", "lastName": "Sample", "relationshipTypeDsc": "Father"}}
		if os.Getenv("EXTRA_PUPIL") == "1" { // toggle a second pupil to exercise the multi-pupil headline
			pupils = append(pupils, map[string]any{"pupilID": "mock-pupil-two", "firstName": "Sam", "lastName": "Sample", "relationshipTypeDsc": "Father"})
		}
		ok(w, pupils)
	})
	// teacher (and any contact) resolved by id - used to name activity teachers
	mux.HandleFunc("/api/Contact/GetContactModel", func(w http.ResponseWriter, r *http.Request) {
		names := map[string][2]string{
			"mock-teacher-one": {"Taylor", "Example"},
			"mock-teacher-two": {"Casey", "Example"},
		}
		n, okName := names[r.URL.Query().Get("ContactID")]
		if !okName {
			n = [2]string{"Gwent Music", "Teacher"}
		}
		model := map[string]any{"firstName": n[0], "lastName": n[1]}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"model": model, "success": true, "internetConnection": true})
	})
	// pupil activities: historical + active enrolments (TuitionApplicant), one row each
	mux.HandleFunc("/api/TuitionApplicant/GetTuitionApplicantModelListByPupilID", func(w http.ResponseWriter, r *http.Request) {
		pid := r.URL.Query().Get("PupilID")
		if pid == "mock-pupil-two" {
			ok(w, []map[string]any{
				{"tuitionApplicant": "ta-s1", "pupilID": pid, "applicationRef": "DEMO-BOOKING-001", "status": "A", "narrative": "Violin at Example Secondary School", "startDate": "2026-09-01T00:00:00", "acceptedDate": "2026-07-01T12:00:00", "hireRequired": true, "teacherID": "mock-teacher-two"},
			})
			return
		}
		ok(w, []map[string]any{
			{"tuitionApplicant": "ta-1", "pupilID": pid, "applicationRef": "DEMO-BOOKING-002", "status": "A", "narrative": "Brass at Example Secondary School", "startDate": "2026-09-01T00:00:00", "acceptedDate": "2026-07-01T12:00:00", "hireRequired": false, "progressedFrom": "ta-3", "teacherID": "mock-teacher-one"},
			{"tuitionApplicant": "ta-2", "pupilID": pid, "applicationRef": "DEMO-BOOKING-003", "status": "A", "narrative": "Brass at Example Holiday School", "startDate": "2026-08-01T12:00:00", "acceptedDate": "2026-07-01T12:00:00", "hireRequired": false, "teacherID": "mock-teacher-one"},
			{"tuitionApplicant": "ta-3", "pupilID": pid, "applicationRef": "Progressed", "status": "A", "narrative": "Brass at Example Primary School", "startDate": "2025-09-01T12:00:00", "acceptedDate": "2025-08-01T12:00:00", "hireRequired": false, "progressedFrom": "ta-4", "teacherID": "mock-teacher-two"},
			{"tuitionApplicant": "ta-4", "pupilID": pid, "applicationRef": "Progressed", "status": "A", "narrative": "Brass at Example Primary School", "startDate": "2024-09-01T12:00:00", "acceptedDate": "2024-07-01T12:00:00", "hireRequired": false, "progressedFrom": "ta-5", "teacherID": "mock-teacher-one"},
			{"tuitionApplicant": "ta-5", "pupilID": pid, "applicationRef": "DEMO-BOOKING-004", "status": "A", "narrative": "Brass at Example Primary School", "startDate": "2023-09-01T12:00:00", "acceptedDate": "2023-08-01T12:00:00", "hireRequired": false, "teacherID": "mock-teacher-one"},
		})
	})
	mux.HandleFunc("/api/Contact/SelectDashBoardManifest", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{{"type": 1, "count": 2}, {"type": 3, "count": 1}, {"type": 4, "count": 1}})
	})
	mux.HandleFunc("/api/Diary/SelectPupilRecordsByContactDate", func(w http.ResponseWriter, r *http.Request) { ok(w, diary()) })
	mux.HandleFunc("/api/ContactActivity/SelectEmailTextSecureMessagesReceived", func(w http.ResponseWriter, r *http.Request) {
		list := []map[string]any{}
		if os.Getenv("EXTRA_MSG") == "1" { // toggle a newly-arrived message for testing dedup
			list = append(list, msg("Lesson update: room change", "Taylor Example", "2026-09-04T08:15:00", false))
		}
		list = append(list,
			msg("Autumn Timetable", "Office Example", "2026-09-02T09:31:00", false),
			msg("Trumpet", "Tutor Example", "2026-08-23T14:13:00", false),
			msg("Cylchlythyr Cerdd Gwent - Rhifyn Rhieni", "Office Example", "2026-08-17T09:31:00", true),
		)
		ok(w, list)
	})
	mux.HandleFunc("/api/ContactActivity/SelectEmailTextSecureMessagesSent", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{msg("Todays lesson", "You", "2026-09-01T17:21:00", true)})
	})

	// billing
	mux.HandleFunc("/api/Debtor/GetDebtorModelListByEmail", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{{"debtor": "mock-debtor-0001", "accountNo": "DEMO-ACCOUNT-001", "outstandingBalance": 173.50, "scheduledPayments": 173.50, "lastPaymentDate": time.Now().AddDate(0, -1, 0).Format("2006-01-02T15:04:05")}})
	})
	mux.HandleFunc("/api/DLInvoice/SelectDashboardWalletByDebtor", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{{"outstandingDebt": 173.50, "nextInstalment": 43.38, "dueDate": time.Now().AddDate(0, 0, 21).Format("2006-01-02T15:04:05"), "paidValue": 43.38, "transValue": 173.50, "transDate": time.Now().AddDate(0, -1, 0).Format("2006-01-02T15:04:05"), "cardType": "Visa", "last4Digits": "4242", "expires": "12/28", "invoiceHeading": "Autumn term tuition"}})
	})
	mux.HandleFunc("/api/DLInvoice/SelectByDebtorAcademicYear", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{
			{"invoiceNo": "DEMO-INVOICE-005", "invoiceDate": time.Now().AddDate(0, 0, -20).Format("2006-01-02T15:04:05"), "grossAmount": 135.0, "outstanding": 0.0, "status": "Paid"},
			{"invoiceNo": "DEMO-INVOICE-006", "invoiceDate": time.Now().AddDate(0, 0, -2).Format("2006-01-02T15:04:05"), "grossAmount": 45.0, "outstanding": 45.0, "status": "Due"},
		})
	})
	mux.HandleFunc("/api/PaymentSchedule/GetPaymentScheduleModelListByDebtor", func(w http.ResponseWriter, r *http.Request) {
		out := []map[string]any{}
		for i := 0; i < 4; i++ {
			d := time.Now().AddDate(0, i, 0)
			out = append(out, map[string]any{"dueDate": d.Format("2006-01-02T15:04:05"), "amount": 45.0, "method": "Direct Debit", "status": map[bool]string{true: "Paid", false: "Scheduled"}[i == 0], "paid": i == 0})
		}
		ok(w, out)
	})
	// reports
	mux.HandleFunc("/api/ReportPupil/SelectByPupilOrDebtor", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{
			{"title": "Summer term report", "pupilName": "Alex", "reportPeriod": "Summer 2026", "publishedDate": time.Now().AddDate(0, -2, 0).Format("2006-01-02T15:04:05")},
			{"title": "Spring term report", "pupilName": "Alex", "reportPeriod": "Spring 2026", "publishedDate": time.Now().AddDate(0, -6, 0).Format("2006-01-02T15:04:05")},
		})
	})
	// message body by activity id (mirrors the real API: list has ids, body is a detail call)
	mux.HandleFunc("/api/ContactActivity/GetContactActivityModel", func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("ContactActivity")
		model := map[string]any{
			"contactActivity":  id,
			"plainContent":     "",
			"htmlContent":      "Dear {DebtorName},<br><br>We can confirm {OnBehalfOf} lessons for {Subject} have now been scheduled.<br><br>Kind regards,<br>Gwent Music",
			"substitutionTags": "DebtorName|OnBehalfOf|Subject",
			"fromAddress":      "teacher.one@example.test",
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"model": model, "success": true, "internetConnection": true})
	})
	mux.HandleFunc("/api/ContactActivityItem/GetContactActivityItemModel", func(w http.ResponseWriter, r *http.Request) {
		model := map[string]any{"substitutionValues": "Sample Parent|Alex|Brass"}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"model": model, "success": true, "internetConnection": true})
	})

	// send (write) - accept and echo success
	mux.HandleFunc("/api/ContactActivity/SendStandardMail", func(w http.ResponseWriter, r *http.Request) {
		ok(w, []map[string]any{{"subject": "sent", "success": true}})
	})

	return mux
}

func logging(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("%s %s", r.Method, r.URL.Path)
		h.ServeHTTP(w, r)
	})
}

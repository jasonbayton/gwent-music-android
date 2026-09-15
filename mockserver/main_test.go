package main

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

// The seeded mock is not a provider contract oracle, but its own payloads must stay usable.
func TestReadEnvelopes(t *testing.T) {
	for _, path := range []string{
		"/api/Contact/GetContactModelListByEmail",
		"/api/Contact/GetContactModelListBySchoobleContactID",
		"/api/Contact/SelectRelatedPupils",
		"/api/Diary/SelectPupilRecordsByContactDate",
		"/api/ContactActivity/SelectEmailTextSecureMessagesReceived",
	} {
		t.Run(path, func(t *testing.T) {
			r := httptest.NewRecorder()
			newMux().ServeHTTP(r, httptest.NewRequest("GET", path, nil))
			var body struct {
				Success  bool             `json:"success"`
				Internet bool             `json:"internetConnection"`
				Rows     []map[string]any `json:"modelList"`
			}
			if err := json.Unmarshal(r.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if r.Code != 200 || !body.Success || !body.Internet || len(body.Rows) == 0 {
				t.Fatalf("unusable fixture: HTTP %d, %#v", r.Code, body)
			}
		})
	}
}

func TestMessageReadState(t *testing.T) {
	for _, read := range []bool{true, false} {
		m := msg("Example subject", "Example sender", "2026-09-09T10:00:00", read)
		if (m["viewedDate"] != nil) != read {
			t.Fatal("read flag disagrees with viewedDate")
		}
		if m["contactActivity"] == m["contactActivityItem"] {
			t.Fatal("activity and delivery IDs must differ")
		}
	}
}

func TestExtraPupilFixture(t *testing.T) {
	t.Setenv("EXTRA_PUPIL", "0")
	single := len(diary())
	t.Setenv("EXTRA_PUPIL", "1")
	rows := diary()
	if len(rows) <= single {
		t.Fatal("second pupil did not add lessons")
	}
	seen := map[any]bool{}
	for _, row := range rows {
		if seen[row["diaryID"]] {
			t.Fatal("duplicate diary ID")
		}
		seen[row["diaryID"]] = true
		if row["startDateTime"] == nil || row["academicYearTerm"] == nil {
			t.Fatal("incomplete lesson")
		}
	}
}

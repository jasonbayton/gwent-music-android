"""Print Gradle's HTML timing tables in CI logs without uploading a build scan."""

from html.parser import HTMLParser
from pathlib import Path


class TimingTables(HTMLParser):
    def __init__(self):
        super().__init__()
        self.cell = None
        self.row = []

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.row = []
        elif tag in ("td", "th"):
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self.cell is not None:
            self.row.append(" ".join("".join(self.cell).split()))
            self.cell = None
        elif tag == "tr" and self.row:
            print(" | ".join(self.row))


if __name__ == "__main__":
    profiles = sorted(Path("build/reports/profile").glob("profile-*.html"))
    if profiles:
        TimingTables().feed(profiles[-1].read_text(encoding="utf-8"))
    else:
        print("No Gradle timing report was generated; check the build step for errors.")

#!/usr/bin/env python3
"""Check slussen/docs for the three AGENTS.md section 2 project rules.

Usage:
    python tools/check_project_rules.py              # check docs/, exit 1 on a finding
    python tools/check_project_rules.py --self-test  # plant violations in memory and in a temporary
                                                     # copy of docs/, check that they are found

Rules (the linter in tools/lint_specs.py cannot check them):
    ACCOUNT       a bare "account": not part of a glossary compound such as
                  Bank Account, Budget Account or Retired Account.
    NUMBER        a duration or count with no Operator Setting, default or
                  outside source in its sentence.
    TERM_CASE     a multi-word glossary term (or "operator") not in Title Case.
    CODE_NOTATION an entity or attribute name in prose without code notation.
    AVOID_WORD    a glossary Avoid word in an entity or attribute name, or in
                  the prose of entity_model.md (spec_lint.py does not lint that
                  file; bootstrap plan Phase 3 tooling gap).

Scope: the specification and the ADRs, i.e. every .md, .puml and .html file
under docs/ except docs/archive/, docs/reviews/ and the plans (docs/*_plan.md).
Plans and reviews are transient and quote violations on purpose.

Text inside code notation, fenced code blocks, HTML comments and link targets
is never checked. Every exception the check allows is listed below in
EXCEPTIONS, each with its reason.
"""
import bisect
import html
import os
import re
import sys

# ---------------------------------------------------------------------------
# Exceptions. Each is (rule, file, context, reason): a finding of that rule in
# a file matching the file expression is allowed when the context expression
# matches the finding's context. The context is the matched text with up to 40
# characters on either side (for ACCOUNT: the 40 characters before it and the
# match, ending there; for AVOID_WORD in a name: the name with spaces for
# underscores; for NUMBER: the whole sentence). Keep them narrow.
# ---------------------------------------------------------------------------
EXCEPTIONS = [
    ("ACCOUNT", r"", r"(?i)\bhealth savings accounts?$",
     "a financial and legal term (HSA), not a bare account: Operator ruling 2026-10-15"),
    ("TERM_CASE", r"", r"\b[Ff]ile upload window\b",
     "the Operator Setting name, lowercase by S-01; it is not the glossary term Upload Window"),
    ("TERM_CASE", r"(entity_model|requirements)\.md$",
     r"\b(?:Already Present|already-present) decision no longer holds\b",
     "a clause saying that a Review Decision no longer holds, not the Transaction Status name"),
    ("TERM_CASE", r"UC-006-upload-to-the-budget-app\.md$",
     r"\brecords the outcome Unknown in the Sent Record\b",
     "the Sent Record outcome value Unknown (an attribute value), not the Transaction Status Outcome Unknown"),
    ("CODE_NOTATION", r"UC-010-adjust-settings\.md$", r"^(?:[A-Z]+ )*TRANSFER$",
     "default Bank Transfer Descriptions: literal text as Banks write it, not the TRANSFER entity"),
    ("AVOID_WORD", r"entity_model\.md$", r"^(?:[a-z]+ )*bank transaction id$",
     "a foreign key to BANK_TRANSACTION, named [<role>_]<entity>_id as the entity-model skill does "
     "(e.g. settled_by_bank_transaction_id); not a Stable Reference"),
]

# Numbers that are never a duration or count Slussen chooses.
NUMBER_EXEMPT_VALUES = {
    "0": "none at all: an NFR target or 'switched off', never a chosen quantity",
    "100%": "all of them: an NFR target, never a chosen quantity",
}

# Words in a sentence that name a setting, a default or an outside source.
NUMBER_MARKERS = [
    r"\bdefault\b",                    # "8 hours by default"
    r"\bOperator Settings?\b",
    r"\ba number (?:I|the Operator) can change\b",
    r"\bset by\b",                     # "set by Android"
    r"\b(?:YNAB|PSD2|Android|RFC|ISO|EU)\b",
    r"\bC-0\d\d\b",                    # a Constraint, which names its source
    r"\bconventionally\b",             # "banks and card issuers conventionally show" (O-P2-08)
    r"\bData Provider's own\b",        # "the window of the Data Provider's own limit": an outside source
]

DOCS_EXCLUDED_DIRS = ("archive", "reviews")
SPEC_EXTENSIONS = (".md", ".puml", ".html")

MASK = "\x00"


class Finding:
    def __init__(self, rule, path, line, text, message):
        self.rule = rule
        self.path = path
        self.line = line
        self.text = text
        self.message = message

    def __str__(self):
        return "{}:{}: {}: {} [{}]".format(self.path, self.line, self.rule, self.message, self.text)


# ---------------------------------------------------------------------------
# Units: the checkable pieces of text, with their line numbers.
# ---------------------------------------------------------------------------
class Unit:
    """A paragraph, list item, heading or table cell; text joined over wrapped lines."""

    def __init__(self, path, pieces, column="", row=None):
        self.path = path
        self.column = column            # table column header, lowercase, "" outside tables
        self.row = row                  # dict header -> cell text, for table cells
        self.text = ""
        self.starts = []
        self.lines = []
        for number, piece in pieces:
            if self.text:
                self.text += " "
            self.starts.append(len(self.text))
            self.lines.append(number)
            self.text += piece
        self.masked = mask(self.text)

    def line_at(self, offset):
        return self.lines[max(0, bisect.bisect_right(self.starts, offset) - 1)]


def mask(text):
    """Blank out code notation, link targets and HTML comments, keeping offsets."""
    def blank(match):
        return MASK * len(match.group(0))
    text = re.sub(r"<!--.*?-->", blank, text)
    text = re.sub(r"(`+)(?:(?!\1).)+?\1", blank, text)
    text = re.sub(r"\]\([^)]*\)", lambda m: "]" + MASK * (len(m.group(0)) - 1), text)
    text = re.sub(r"https?://\S+", blank, text)
    return text


def table_cells(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def markdown_units(path, lines):
    units = []
    paragraph = []
    header = None
    fenced = False
    comment = False

    def flush():
        if paragraph:
            units.append(Unit(path, list(paragraph)))
            del paragraph[:]

    for number, raw in enumerate(lines, 1):
        line = raw.rstrip()
        stripped = line.strip()
        if stripped.startswith("```"):
            flush()
            fenced = not fenced
            continue
        if fenced:
            continue
        if comment or stripped.startswith("<!--"):
            flush()
            comment = "-->" not in stripped
            continue
        if stripped.startswith("|"):
            flush()
            cells = table_cells(stripped)
            if header is None:
                header = [c.lower() for c in cells]
                continue
            if all(re.fullmatch(r":?-+:?", c) for c in cells if c):
                continue
            row = dict(zip(header, cells))
            for index, cell in enumerate(cells):
                column = header[index] if index < len(header) else ""
                units.append(Unit(path, [(number, cell)], column, row))
            continue
        header = None
        if not stripped:
            flush()
            continue
        if stripped.startswith("#"):
            flush()
            units.append(Unit(path, [(number, stripped)], "heading"))
            continue
        item = re.match(r"(?:[-*+]|\d+\.)\s+", stripped)
        if item:
            flush()
            stripped = stripped[item.end():]   # the list marker is not prose
        paragraph.append((number, stripped))
    flush()
    return units


def puml_units(path, lines):
    """Only the quoted labels of a PlantUML diagram are prose."""
    units = []
    for number, line in enumerate(lines, 1):
        for match in re.finditer(r'"([^"]*)"', line):
            units.append(Unit(path, [(number, match.group(1).replace("\\n", " "))]))
    return units


BLOCK_TAGS = (r"(?:p|div|li|ul|ol|td|th|tr|table|h[1-6]|br|section|article|header|footer|nav|main|button|"
              r"label|option|title|figcaption|dt|dd)")


def html_units(path, lines):
    """Text between block-level tags; <code> becomes code notation."""
    text = "\n".join(lines)

    def keep_newlines(match):
        return "\n" * match.group(0).count("\n")
    text = re.sub(r"(?is)<(script|style)\b.*?</\1>", keep_newlines, text)
    text = re.sub(r"(?s)<!--.*?-->", keep_newlines, text)
    text = re.sub(r"(?is)<code\b[^>]*>(.*?)</code>", lambda m: "`" + m.group(1) + "`", text)
    text = re.sub(r"(?is)</?" + BLOCK_TAGS + r"\b[^>]*>", lambda m: "\x01" + keep_newlines(m), text)
    text = re.sub(r"(?s)<[^>]*>", keep_newlines, text)
    units = []
    number = 1
    for block in text.split("\x01"):
        pieces = []
        for offset, piece in enumerate(block.split("\n")):
            if piece.strip():
                pieces.append((number + offset, html.unescape(piece.strip())))
        number += block.count("\n")
        if pieces:
            units.append(Unit(path, pieces))
    return units


def units_for(path, lines):
    if path.endswith(".puml"):
        return puml_units(path, lines)
    if path.endswith(".html"):
        return html_units(path, lines)
    return markdown_units(path, lines)


# ---------------------------------------------------------------------------
# The glossary and the entity model.
# ---------------------------------------------------------------------------
def load_glossary(lines):
    """[(term, [avoid words])] from the glossary table."""
    entries = []
    header = None
    for line in lines:
        if not line.strip().startswith("|"):
            header = None
            continue
        cells = table_cells(line)
        if header is None:
            header = [c.lower() for c in cells]
            continue
        if all(re.fullmatch(r":?-+:?", c) for c in cells if c) or not cells[0]:
            continue
        avoid = []
        if "avoid" in header and header.index("avoid") < len(cells):
            avoid = [a.strip() for a in cells[header.index("avoid")].split(",") if a.strip()]
        entries.append((cells[0], avoid))
    return entries


def load_entity_names(lines):
    """(entity names, [(line, name, kind)]) from entity_model.md."""
    entities = set()
    names = []
    header = None
    for number, line in enumerate(lines, 1):
        match = re.match(r"###\s+([A-Z][A-Z0-9_]*)\s*$", line)
        if match:
            entities.add(match.group(1))
            names.append((number, match.group(1), "entity"))
            continue
        if not line.strip().startswith("|"):
            header = None
            continue
        cells = table_cells(line)
        if header is None:
            header = [c.lower() for c in cells]
            continue
        if header[0] == "attribute" and not re.fullmatch(r":?-+:?", cells[0]):
            names.append((number, cells[0], "attribute"))
    return entities, names


def word_pattern(word, plural=False):
    """As spec_lint.py: whole words, optional plural, case-insensitive."""
    left = r"(?<![\w/-])" if word[0].isalnum() else ""
    right = r"(?![\w/-])" if word[-1].isalnum() else ""
    suffix = r"(?:e?s)?" if plural and word[-1].isalnum() else ""
    return re.compile(left + re.escape(word) + suffix + right, re.IGNORECASE)


# ---------------------------------------------------------------------------
# Rules.
# ---------------------------------------------------------------------------
def excepted(rule, path, context):
    return any(r == rule and re.search(f, path) and re.search(p, context) for r, f, p, _ in EXCEPTIONS)


def around(unit, start, end):
    return unit.text[max(0, start - 40):end + 40]


def is_glossary_avoid(unit):
    return unit.path.endswith("glossary.md") and unit.column == "avoid"


def is_entity_format(unit):
    """The entity model's own format: headings, attribute names, types, lengths, validation."""
    return unit.path.endswith("entity_model.md") and (
        unit.column in ("heading", "attribute", "data type", "length/precision", "validation rules"))


def check_account(unit, glossary):
    findings = []
    if is_glossary_avoid(unit):
        return findings
    compounds = [term for term, _ in glossary if re.search(r"\bAccount\b", term)]
    covered = []
    for term in compounds:
        pattern = re.compile(r"(?<![\w-])" + r"\s+".join(re.escape(w) for w in term.split())
                             + r"(?:s|'s|s')?(?![\w-])", re.IGNORECASE)
        covered += [(m.start(), m.end()) for m in pattern.finditer(unit.masked)]
    for match in re.finditer(r"(?<![\w-])accounts?(?![\w])", unit.masked, re.IGNORECASE):
        if any(s <= match.start() and match.end() <= e for s, e in covered):
            continue
        context = around(unit, match.start(), match.end())
        # The context ends at the match, so an exception cannot cover a bare "account" next to it.
        if excepted("ACCOUNT", unit.path, unit.text[max(0, match.start() - 40):match.end()]):
            continue
        findings.append(Finding("ACCOUNT", unit.path, unit.line_at(match.start()), context.strip(),
                                "bare '" + match.group(0) + "': write Bank Account, Budget Account "
                                "or a glossary compound"))
    return findings


def sentences(unit):
    """(start, end) of each sentence of the masked text."""
    text = unit.masked
    bounds = []
    start = 0
    for match in re.finditer(r"(?<!\be\.g)(?<!\bi\.e)(?<!\betc)[.!?](?=\s+[A-Z(`\x00\"*]|\s*$)", text):
        bounds.append((start, match.end()))
        start = match.end()
    if start < len(text):
        bounds.append((start, len(text)))
    return bounds


NUMBER = re.compile(
    r"(?<![\w.\-/\u00a7#:\x00])"    # not part of an identifier, version, path or section
    r"(\d+(?:[.,]\d+)*%?)"
    r"(?![\w\-/]|\.\d|\(|\x00)")      # not followed by more identifier, a version part or a call


# A number right after one of these names a thing (a version, a step, a
# section, a standard), or right after code notation; it is not a quantity.
NAMING_WORDS = (r"(?:\u00a7|Art\.|version|[Pp]hases?|steps?|lines?|API|Android|JDK|JVM|JUnit|ISO|RFC|"
                r"Gradle|Kotlin)")


def is_identifier_context(text, start):
    before = text[:start]
    return bool(re.search(r"(?<!\w)" + NAMING_WORDS + r"\s*(?:\d+\s*(?:,|and|to|or)\s*)*$", before)
                or re.search(MASK + r"\s*$", before))


# A number is a duration or count when a time or size unit follows it, when a
# limit word comes before it, or when a plural noun follows it ("4 reads",
# "9 Bank Accounts"). Storage and format sizes are outside the rule
# (AGENTS.md section 2: "not storage sizes such as the field lengths").
TIME_UNITS = r"(?:milli)?seconds?|minutes?|hours?|days?|weeks?|months?|years?|ms|min|h|MB|KB|GB"
STORAGE_UNITS = r"characters?|bits?|bytes?|digits?"
LIMIT_WORDS = (r"(?:at most|at least|up to|no more than|more than|fewer than|less than|within|after|"
               r"every|maximum of|minimum of|limit of)")
NOT_PLURAL_NOUNS = ("this", "thus", "plus", "less", "unless", "does")
# A duration written in words ("eight hours"); spelled counts ("four accesses",
# "one Bank Account") are too common in ordinary prose to check.
SPELLED_DURATION = re.compile(r"\b(two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|"
                              r"thirty|forty|sixty|ninety)(?=[\s-]+(?:" + TIME_UNITS + r")\b)", re.IGNORECASE)


def is_duration_or_count(sentence, match):
    value = match.group(1)
    before = sentence[:match.start()]
    after = sentence[match.end():]
    if re.match(r"\s*(?:" + STORAGE_UNITS + r")\b", after):
        return False
    if value.endswith("%") or re.match(r"\s*(?:" + TIME_UNITS + r")\b", after):
        return True
    if re.search(r"\b" + LIMIT_WORDS + r"\s*$", before, re.IGNORECASE):
        return True
    plural = re.match(r"\s+(?:[A-Z][\w-]*\s+)*([A-Za-z]{4,}s)\b", after)
    return bool(plural) and plural.group(1).lower() not in NOT_PLURAL_NOUNS


def check_numbers(unit):
    findings = []
    if unit.column in ("heading", "id", "attribute", "data type", "length/precision", "priority",
                       "status", "validation rules"):
        return findings
    sourced_row = bool(unit.row and unit.row.get("source", "").strip())
    for start, end in sentences(unit):
        sentence = unit.masked[start:end]
        candidates = []
        for match in NUMBER.finditer(sentence):
            if match.group(1) in NUMBER_EXEMPT_VALUES or is_identifier_context(sentence, match.start()):
                continue
            if is_duration_or_count(sentence, match):
                candidates.append(match)
        candidates += list(SPELLED_DURATION.finditer(sentence))
        if not candidates or sourced_row:
            continue
        if any(re.search(m, sentence) for m in NUMBER_MARKERS):
            continue
        original = unit.text[start:end].strip()
        if excepted("NUMBER", unit.path, original):
            continue
        for match in candidates:
            offset = start + match.start()
            findings.append(Finding("NUMBER", unit.path, unit.line_at(offset), original,
                                    "'" + match.group(1) + "' has no Operator Setting, default or "
                                    "outside source in its sentence"))
    return findings


def term_pattern(term):
    words = [re.escape(w) for w in term.split()]
    return re.compile(r"(?<![\w-])" + r"[\s-]+".join(words) + r"(?:s|es|'s|s')?(?![\w])", re.IGNORECASE)


# Single-word glossary terms are ordinary English too (bank, budget, fetch,
# upload, import, transfer, source); only these are checked in lowercase.
SINGLE_WORD_TERMS_CHECKED = ["Operator"]


def check_term_case(unit, glossary):
    findings = []
    if is_glossary_avoid(unit) or is_entity_format(unit):
        return findings
    terms = [t for t, _ in glossary if " " in t.strip() or t in SINGLE_WORD_TERMS_CHECKED]
    matches = [(m, t) for t in terms for m in term_pattern(t).finditer(unit.masked)]
    matches.sort(key=lambda mt: (mt[0].start(), -len(mt[0].group(0))))
    end = 0
    for match, term in matches:     # leftmost-longest: "Budget Account balances" is not "Account Balance"
        if match.start() < end:
            continue
        end = match.end()
        found = re.split(r"[\s-]+", match.group(0))
        if all(f[:1] == e[:1] for f, e in zip(found, re.split(r"[\s-]+", term))):
            continue
        context = around(unit, match.start(), match.end())
        if excepted("TERM_CASE", unit.path, context):
            continue
        findings.append(Finding("TERM_CASE", unit.path, unit.line_at(match.start()), context.strip(),
                                "'" + match.group(0) + "' is the glossary term '" + term
                                + "': write it in Title Case"))
    return findings


IDENTIFIER = re.compile(r"(?<![\w.\-/])([A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+)(?![\w\-/])")


def check_code_notation(unit, entities):
    findings = []
    if is_entity_format(unit) or is_glossary_avoid(unit):
        return findings
    for match in IDENTIFIER.finditer(unit.masked):
        context = around(unit, match.start(), match.end())
        if excepted("CODE_NOTATION", unit.path, context):
            continue
        findings.append(Finding("CODE_NOTATION", unit.path, unit.line_at(match.start()), context.strip(),
                                "'" + match.group(1) + "' looks like an entity or attribute name: "
                                "write it in code notation"))
    for match in re.finditer(r"(?<![\w.])([A-Z][A-Z0-9]+)(?![\w])", unit.masked):
        if match.group(1) in entities:
            context = around(unit, match.start(), match.end())
            if excepted("CODE_NOTATION", unit.path, context):
                continue
            findings.append(Finding("CODE_NOTATION", unit.path, unit.line_at(match.start()),
                                    context.strip(), "entity '" + match.group(1) + "' in prose: "
                                    "write it in code notation"))
    return findings


def check_avoid_words(path, units, names, glossary):
    """Glossary Avoid words in entity and attribute names and in entity_model.md prose."""
    findings = []
    avoided = [(term, word, word_pattern(word, plural=True)) for term, avoid in glossary for word in avoid]
    for number, name, kind in names:
        spoken = name.replace("_", " ")
        for term, word, pattern in avoided:
            if pattern.search(spoken) and not excepted("AVOID_WORD", path, spoken):
                findings.append(Finding("AVOID_WORD", path, number, name, kind + " name contains '" + word
                                        + "', which the glossary says to avoid; use '" + term + "'"))
    for unit in units:
        if unit.column in ("heading", "attribute", "data type", "length/precision", "validation rules"):
            continue
        for term, word, pattern in avoided:
            for match in pattern.finditer(unit.masked):
                context = around(unit, match.start(), match.end())
                if excepted("AVOID_WORD", path, context):
                    continue
                findings.append(Finding("AVOID_WORD", path, unit.line_at(match.start()), context.strip(),
                                        "'" + match.group(0) + "' is a word the glossary says to avoid; "
                                        "use '" + term + "'"))
    return findings


def check_documents(documents, glossary_lines, entity_lines):
    """documents: [(path, lines)]. Returns the findings."""
    glossary = load_glossary(glossary_lines)
    entities, names = load_entity_names(entity_lines)
    findings = []
    for path, lines in documents:
        units = units_for(path, lines)
        for unit in units:
            findings += check_account(unit, glossary)
            findings += check_numbers(unit)
            findings += check_term_case(unit, glossary)
            findings += check_code_notation(unit, entities)
        if path.endswith("entity_model.md"):
            findings += check_avoid_words(path, units, names, glossary)
    return findings


# ---------------------------------------------------------------------------
# Running against docs/.
# ---------------------------------------------------------------------------
def read_lines(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read().splitlines()


def spec_paths(docs_dir):
    paths = []
    for root, dirs, files in os.walk(docs_dir):
        if os.path.normpath(root) == os.path.normpath(docs_dir):
            dirs[:] = [d for d in dirs if d not in DOCS_EXCLUDED_DIRS]
        dirs.sort()
        for name in sorted(files):
            if not name.endswith(SPEC_EXTENSIONS):
                continue
            if os.path.normpath(root) == os.path.normpath(docs_dir) and name.endswith("_plan.md"):
                continue
            paths.append(os.path.join(root, name))
    return paths


def find(docs_dir):
    """(number of files, findings) for a docs directory."""
    repo_root = os.path.dirname(os.path.normpath(docs_dir))
    documents = [(os.path.relpath(p, repo_root).replace("\\", "/"), read_lines(p))
                 for p in spec_paths(docs_dir)]
    return len(documents), check_documents(documents, read_lines(os.path.join(docs_dir, "glossary.md")),
                                           read_lines(os.path.join(docs_dir, "entity_model.md")))


def run(docs_dir):
    count, findings = find(docs_dir)
    for finding in findings:
        print(finding)
    print("check_project_rules: {} file(s) checked, {} finding(s)".format(count, len(findings)))
    return 1 if findings else 0


# ---------------------------------------------------------------------------
# Self-test: violations are planted in in-memory text and in a temporary copy
# of docs/, never in docs/ itself.
# ---------------------------------------------------------------------------
TEST_GLOSSARY = """\
# Glossary

| Term | Definition | Avoid |
|---|---|---|
| Operator | The one person who runs Slussen. | end user |
| Bank Account | One place at a Bank. | source account |
| Budget Account | One place in a Budget. | ledger account |
| Account Mapping | The link from one Bank Account to one Budget Account. | binding |
| Account Balance | The balance of a Bank Account. | |
| Upload Plan | Slussen's proposal of what it will write. | batch |
| Upload Window | The date range for Upload. | age gate |
| Decision-Critical Data | The data decisions depend on. | |
| Matching Date Window | For YNAB it is 10 days, a rule of YNAB itself (C-009). | |
| Stable Reference | An identifier of a Bank Transaction. | transaction id |
""".splitlines()

TEST_ENTITIES = """\
# Entity Model

### BANK_ACCOUNT

One of the Operator's Bank Accounts.

| Attribute | Description | Data Type | Length/Precision | Validation Rules |
|---|---|---|---|---|
| id | Unique identifier | Long | 19 | Primary Key, Sequence |
| budget_account_id | Budget Account that receives the transactions | Long | 19 | Foreign Key (BUDGET.id) |

### BUDGET
""".splitlines()

CLEAN_DOC = """\
# UC-001: Clean

The Operator maps each Bank Account to a Budget Account through an Account Mapping.
Slussen waits 8 hours by default, a number the Operator can change.
YNAB matches within 10 days (C-009). The Upload Plan lists `BANK_ACCOUNT` and
`budget_account_id`, and the file upload window applies to Transaction Files.
It shows the Budget Account balances and Decision-Critical Data.
A US health savings account and two Health Savings Accounts are Bank Accounts.
Requests are counted over the preceding 24 hours, the window of the Data Provider's own limit.

```
BANK_ACCOUNT account 99 hours
```

- Retries 0 times when off; see [UC-009](UC-009-manage-account-mappings.md), step 3 and ADR-002 \u00a74.

| ID | Title | Description | Source |
|---|---|---|---|
| C-001 | Outside Limit | A Bank allows at most 4 reads in any 24 hours. | Regulation 2018/389 |
""".splitlines()

PLANTED = [
    ("ACCOUNT", "Slussen reads the account once."),
    ("ACCOUNT", "The credit card\naccount is paid."),
    ("ACCOUNT", "A US health savings account is read, and the account is shown."),
    ("ACCOUNT", "The account of a Health Savings Account holder is read."),
    ("ACCOUNT", "Slussen reads the savings account."),
    ("NUMBER", "Slussen waits 8 hours before it fetches again."),
    ("NUMBER", "At most 3 attempts are made."),
    ("NUMBER", "Slussen warns at 80% of the size."),
    ("NUMBER", "Slussen waits eight hours."),
    ("NUMBER", "The Data Provider keeps its own list for 24 hours."),
    ("TERM_CASE", "The operator reviews it."),
    ("TERM_CASE", "Slussen shows the upload plan."),
    ("TERM_CASE", "Each Bank account is shown."),
    ("TERM_CASE", "Damage to decision-critical data is shown."),
    ("CODE_NOTATION", "The BANK_ACCOUNT row is stored."),
    ("CODE_NOTATION", "Slussen fills budget_account_id."),
    ("CODE_NOTATION", "One BUDGET holds them."),
]

# The phase gate: on a copy of the real docs/, each of these appended to a use
# case must add exactly one finding of its rule.
PLANTED_IN_DOCS = [
    ("ACCOUNT", "Slussen shows the account."),
    ("NUMBER", "Slussen keeps the last 20 Fetches."),
    ("TERM_CASE", "Slussen shows the bank transaction."),
]


def self_test(docs_dir):
    import shutil
    import tempfile
    failures = []
    glossary = TEST_GLOSSARY
    entities = TEST_ENTITIES
    cases = 0

    def expect(name, findings, rules):
        found = sorted(f.rule for f in findings)
        if found != sorted(rules):
            failures.append("{} gave {}, expected {}".format(name, [str(f) for f in findings] or "nothing",
                                                             sorted(rules) or "nothing"))

    expect("clean text", check_documents([("docs/use_cases/UC-001-clean.md", CLEAN_DOC),
                                          ("docs/glossary.md", glossary)], glossary, entities), [])
    for rule, text in PLANTED:
        cases += 1
        expect("planted " + repr(text), check_documents(
            [("docs/use_cases/UC-001-planted.md", ["# UC-001", ""] + text.splitlines())], glossary, entities),
            [rule])
        if excepted(rule, "docs/use_cases/UC-001-planted.md", text):
            failures.append("an exception covers the planted " + rule + " " + repr(text))
    # Avoid words in entity_model.md: a planted entity name and a word in its prose.
    cases += 1
    model = entities + ["", "### BINDING", "", "A batch of rows.", ""]
    expect("planted Avoid words", check_documents([("docs/entity_model.md", model)], glossary, model),
           ["AVOID_WORD", "AVOID_WORD"])
    # The foreign-key exception covers names ending in bank_transaction_id only.
    cases += 1
    last_row = max(i for i, line in enumerate(entities) if line.startswith("| budget_account_id"))
    model = (entities[:last_row + 1]
             + ["| settled_by_bank_transaction_id | Bank Transaction | Long | 19 | Optional |",
                "| settled_by_transaction_id | Bank Transaction | Long | 19 | Optional |",
                "| bank_transaction_id_copy | Bank Transaction | Long | 19 | Optional |"]
             + entities[last_row + 1:])
    found = [f for f in check_documents([("docs/entity_model.md", model)], glossary, model)]
    expect("foreign-key exception", found, ["AVOID_WORD", "AVOID_WORD"])
    if sorted(f.text for f in found) != ["bank_transaction_id_copy", "settled_by_transaction_id"]:
        failures.append("foreign-key exception flagged " + repr(sorted(f.text for f in found)))
    # HTML mockups: tags stripped, <code> is code notation.
    cases += 1
    page = ["<html><body>", "<p>The operator sees the <code>BANK_ACCOUNT</code> row.</p>",
            "<p>Waits 5 minutes.</p>", "</body></html>"]
    expect("planted HTML", check_documents([("docs/mockups/screen.html", page)], glossary, entities),
           ["NUMBER", "TERM_CASE"])
    # The gate: a temporary copy of the real docs/.
    with tempfile.TemporaryDirectory() as temp:
        copy = os.path.join(temp, "docs")
        shutil.copytree(docs_dir, copy, ignore=shutil.ignore_patterns(*DOCS_EXCLUDED_DIRS))
        _, baseline = find(copy)
        target = os.path.join(copy, "use_cases", "UC-008-check-import-status.md")
        with open(target, encoding="utf-8", newline="") as handle:
            original = handle.read()
        for rule, text in PLANTED_IN_DOCS:
            cases += 1
            with open(target, "w", encoding="utf-8", newline="") as handle:
                handle.write(original + "\r\n" + text + "\r\n")
            _, planted = find(copy)
            known = set(str(f) for f in baseline)
            expect("planted in a copy of docs/ " + repr(text),
                   [f for f in planted if str(f) not in known], [rule])
    for failure in failures:
        print("FAIL: " + failure)
    print("check_project_rules self-test: {} planted case(s) and a clean text, {}".format(
        cases, "FAILED" if failures else "passed"))
    return 1 if failures else 0


def main():
    sys.stdout.reconfigure(errors="backslashreplace")
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    docs_dir = os.path.join(repo_root, "docs")
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test(docs_dir))
    if sys.argv[1:]:
        print(__doc__)
        sys.exit(2)
    sys.exit(run(docs_dir))


if __name__ == "__main__":
    main()

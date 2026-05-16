#!/usr/bin/env python3
"""Parse de finale Claude-output naar een JSON-outcome met `phase`.

Wordt aangeroepen vanuit runner.sh voor refiner / reviewer / tester. Krijgt
de laatste assistant-tekst (de `.result` van het laatste `result`-event in
/tmp/claude.log.jsonl) op stdin en print één JSON-object op stdout, of een
lege string als parsing volledig faalt.

Robuustheid is essentieel omdat sterkere modellen (level 10 = mid+ / premium+)
hun output anders structureren dan de cheap-modellen waar deze flow op
getest is. Specifieke afwijkingen die we hier opvangen:

  * Markdown-codeblocks rond de JSON (```json … ```).
  * Smart/curly quotes (U+201C/U+201D/U+2018/U+2019) i.p.v. ASCII " '.
  * JS-style comments (// regel en /* blok */) in de JSON.
  * Trailing commas in objects/arrays.
  * Onontsnapte dubbele quotes binnen string-values (regex-redding).
  * Helemaal geen curly braces — alleen `phase: X` in de prose (laatste
    redmiddel via regex).

CLI:
    parse-outcome.py --role <refiner|reviewer|tester> [< stdin]

Stdout: one-line JSON-object, of "" als alles faalde.
Stderr: korte diagnostiek (welke pass slaagde, of waarom alles faalde).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Optional


# Phases die we per rol accepteren. Onbekende waardes → degradatie via
# de aanroepende shell (die past z'n eigen default toe — 'awaiting-po'
# voor refiner, 'reviewed-changes' voor reviewer, etc.).
ALLOWED_PHASES = {
    "refiner":  {"refined", "awaiting-po"},
    "reviewer": {"reviewed-ok", "reviewed-changes"},
    "tester":   {"tested-ok", "tested-fail"},
}


# ── Pre-processing helpers ──────────────────────────────────────────────


_SMART_QUOTES = str.maketrans({
    "“": '"', "”": '"',     # “ ”
    "‘": "'", "’": "'",     # ‘ ’
    "«": '"', "»": '"',     # « »
    "–": "-", "—": "-",     # – —
})


def _normalize(text: str) -> str:
    """Vervang smart-quotes/dashes door ASCII en strip ```-fences."""
    text = text.translate(_SMART_QUOTES)
    text = re.sub(r"```(?:json|JSON)?\s*", "", text)
    text = re.sub(r"```", "", text)
    return text


def _strip_json_comments(s: str) -> str:
    """Verwijder // … en /* … */ commentaar uit een JSON-achtige string.

    json.loads accepteert die niet, maar Claude voegt ze soms toe als
    'uitleg'. We doen het niet kwote-bewust (commentaar binnen een
    string zou ook gestript worden) — dat is in de praktijk geen
    probleem en houdt de code overzichtelijk.
    """
    s = re.sub(r"//[^\n]*", "", s)
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.DOTALL)
    return s


def _strip_trailing_commas(s: str) -> str:
    """`{"a": 1,}` → `{"a": 1}`. Idem voor arrays."""
    s = re.sub(r",(\s*[}\]])", r"\1", s)
    return s


def _try_load(candidate: str) -> Optional[dict]:
    """json.loads met meerdere repair-passes. Returnt dict of None."""
    repairs = (
        lambda x: x,
        _strip_trailing_commas,
        _strip_json_comments,
        lambda x: _strip_trailing_commas(_strip_json_comments(x)),
    )
    for repair in repairs:
        try:
            obj = json.loads(repair(candidate))
        except json.JSONDecodeError:
            continue
        if isinstance(obj, dict):
            return obj
    return None


# ── Pass 1: balanced { … } blocks waar "phase" in zit ───────────────────


def _find_phase_blocks(text: str) -> tuple[list[dict], list[str]]:
    """Loop door alle balanced { … }-blokken in `text`. Returns
    (strict_objects, lenient_strings):
      - strict_objects: succesvol naar dict geparsed mét 'phase'.
      - lenient_strings: blok bevat 'phase' maar parse mislukte zelfs
        na alle repairs — voor de regex-redding hieronder.
    """
    strict: list[dict] = []
    lenient: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text[i] != "{":
            i += 1
            continue
        depth = 0
        for j in range(i, n):
            ch = text[j]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    candidate = text[i:j+1]
                    obj = _try_load(candidate)
                    if obj is not None and "phase" in obj:
                        strict.append(obj)
                    elif '"phase"' in candidate or "'phase'" in candidate:
                        lenient.append(candidate)
                    i = j
                    break
        else:
            # geen sluit-haak meer gevonden — stop helemaal
            break
        i += 1
    return strict, lenient


# ── Pass 2: regex-redding op een blok dat bijna-JSON is ─────────────────


_QUESTIONS_RE = re.compile(
    r'"questions"\s*:\s*\[(?P<body>.*?)\]',
    re.DOTALL,
)
_PHASE_KEY_RE = re.compile(
    # 'phase' kan met of zonder quotes; waarde idem (`phase: refined` of
    # `"phase": "refined"`). Trim trailing whitespace/punctuatie aan de
    # waarde-kant zodat `phase: refined.` ook `refined` matcht.
    r'["\']?phase["\']?\s*[:=]\s*["\']?([a-z][a-z-]*[a-z])["\']?',
)


def _regex_extract(block: str) -> Optional[dict]:
    """Trek 'phase' (en eventueel 'questions') uit een quasi-JSON-blok
    waar json.loads op faalt. Tolerant tegen onontsnapte quotes."""
    m = _PHASE_KEY_RE.search(block)
    if not m:
        return None
    out: dict = {"phase": m.group(1)}
    qm = _QUESTIONS_RE.search(block)
    if qm:
        raw = qm.group("body").strip()
        # Strip de buitenste quote van het eerste en laatste item zodat
        # de split op `","` schone vragen oplevert.
        if raw.startswith('"'):
            raw = raw[1:]
        if raw.endswith('"'):
            raw = raw[:-1]
        parts = re.split(r'"\s*,\s*"', raw)
        questions = []
        for p in parts:
            p = p.strip().rstrip(",").strip()
            if p:
                questions.append(p.replace('\\"', '"'))
        if questions:
            out["questions"] = questions
    return out


# ── Pass 3: laatste redmiddel — `phase: X` ergens in de prose ───────────


def _bare_phase_scan(text: str, allowed: set[str]) -> Optional[dict]:
    """Zoek `phase: X` of `phase = X` of `**phase**: X` in losse tekst,
    zelfs zonder curly braces. Alleen vertrouwen als X in `allowed`
    zit — anders hebben we false-positives van de uitleg-prose."""
    # Verzamel alle matches en pak de laatste — dat is meestal de
    # 'echte' beslissing als de model er meerdere noemt.
    last_match: Optional[str] = None
    for m in _PHASE_KEY_RE.finditer(text):
        if m.group(1) in allowed:
            last_match = m.group(1)
    if last_match:
        return {"phase": last_match}
    return None


# ── Main ─────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", required=True,
                        choices=sorted(ALLOWED_PHASES.keys()))
    args = parser.parse_args()

    raw = sys.stdin.read()
    if not raw.strip():
        print("[parse-outcome] lege input", file=sys.stderr)
        return 0

    text = _normalize(raw)
    allowed = ALLOWED_PHASES[args.role]

    # Pass 1: strict JSON.
    strict, lenient = _find_phase_blocks(text)
    if strict:
        # Pak het laatste object met een toegestane phase. Anders het
        # laatste überhaupt — de aanroeper degradeert dan zelf.
        chosen = None
        for obj in strict:
            if obj.get("phase") in allowed:
                chosen = obj
        if chosen is None:
            chosen = strict[-1]
        print(json.dumps(chosen, ensure_ascii=False))
        print(f"[parse-outcome] pass=strict role={args.role} "
              f"phase={chosen.get('phase')!r}", file=sys.stderr)
        return 0

    # Pass 2: regex-redding op lenient blokken (broken-JSON met phase).
    if lenient:
        recovered = _regex_extract(lenient[-1])
        if recovered:
            print(json.dumps(recovered, ensure_ascii=False))
            print(f"[parse-outcome] pass=lenient role={args.role} "
                  f"phase={recovered.get('phase')!r}", file=sys.stderr)
            return 0

    # Pass 3: bare `phase: X` ergens in de tekst.
    bare = _bare_phase_scan(text, allowed)
    if bare:
        print(json.dumps(bare, ensure_ascii=False))
        print(f"[parse-outcome] pass=bare role={args.role} "
              f"phase={bare.get('phase')!r}", file=sys.stderr)
        return 0

    # Niets gevonden — laat de aanroeper z'n fallback toepassen.
    print("[parse-outcome] geen phase gevonden — alle 3 passes faalden",
          file=sys.stderr)
    # Dump een korte snippet zodat de operator ziet wat 't model wél schreef.
    snippet = text.strip()
    if len(snippet) > 600:
        snippet = snippet[:300] + " … " + snippet[-300:]
    print(f"[parse-outcome] raw-snippet: {snippet!r}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Ponovljiv uvoz domenske konfiguracije (obrasci, stupci, sifrarnici, demo zapisi)
kroz POSTOJECE API endpointe aplikacije. Ne dodaje nista u backend.

Podjela rada: AI pise ovu skriptu, COVJEK je pokrece i vraca ispis.

Koristenje:
    python uvoz_seed.py seed/grc.json                  # uvoz na http://localhost:8080
    python uvoz_seed.py seed/grc.json --base http://localhost:8080
    python uvoz_seed.py seed/grc.json --check          # samo validacija seeda, bez HTTP-a
    python uvoz_seed.py seed/grc.json --user admin --password admin

Idempotentno: uparuje po nazivu (grupa/obrazac/sifrarnik) i po kljucu stupca.
Postojece se nadopunjuje, nista se ne brise. Demo zapisi se uvoze SAMO ako su
svi ciljani obrasci prazni (inace bi drugo pokretanje vratilo namjerno obrisane
zapise ili duplicirao postojece).
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #

class Api:
    def __init__(self, base, token=None):
        self.base = base.rstrip("/")
        self.token = token

    def _call(self, method, path, tenant=None, body=None):
        url = self.base + path
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if tenant is not None:
            req.add_header("TenantID", str(tenant))
        try:
            with urllib.request.urlopen(req) as resp:
                raw = resp.read()
                parsed = json.loads(raw) if raw else None
                return resp.status, parsed
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                parsed = json.loads(raw)
            except Exception:
                parsed = {"raw": raw.decode("utf-8", "replace")}
            return e.code, parsed
        except urllib.error.URLError as e:
            print("  GRESKA: ne mogu se spojiti na %s (%s)" % (url, e))
            sys.exit(2)

    def get(self, path, tenant=None):
        return self._call("GET", path, tenant=tenant)

    def post(self, path, body, tenant=None):
        return self._call("POST", path, tenant=tenant, body=body)

    def put(self, path, body, tenant=None):
        return self._call("PUT", path, tenant=tenant, body=body)


def die(status, payload, what):
    msg = payload.get("message") if isinstance(payload, dict) else payload
    print("  GRESKA (%s) na '%s': %s" % (status, what, msg))
    sys.exit(1)


# --------------------------------------------------------------------------- #
# Validacija seeda (pre-flight, bez HTTP-a)
# --------------------------------------------------------------------------- #

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
FORMULA_REF_RE = re.compile(r"\[([^\]]+)\]")


def validate_seed(seed):
    errors = []

    codebook_codes = {}          # code sifrarnika -> set sifri stavki
    for cb in seed.get("codebooks", []):
        item_codes = set()
        for it in cb.get("items", []):
            if it["code"] in item_codes:
                errors.append("sifrarnik '%s': duplicirana sifra stavke '%s'" % (cb["code"], it["code"]))
            item_codes.add(it["code"])
        codebook_codes[cb["code"]] = item_codes

    template_keys = set(t["key"] for t in seed.get("templates", []))
    schema = {}                  # template key -> {col key -> col def}

    for tpl in seed.get("templates", []):
        cols = {}
        for col in tpl["columns"]:
            key = col["key"]
            if key in cols:
                errors.append("obrazac '%s': dupliciran kljuc stupca '%s'" % (tpl["key"], key))
            cols[key] = col
            ctype = col["type"]
            if ctype == "codebook":
                if col.get("codebook") not in codebook_codes:
                    errors.append("obrazac '%s', stupac '%s': sifrarnik '%s' ne postoji"
                                  % (tpl["key"], key, col.get("codebook")))
            elif ctype == "reference":
                if col.get("reference") not in template_keys:
                    errors.append("obrazac '%s', stupac '%s': ciljni obrazac '%s' ne postoji"
                                  % (tpl["key"], key, col.get("reference")))
            elif ctype == "formula":
                for ref in FORMULA_REF_RE.findall(col.get("formula", "")):
                    if ref not in [c["key"] for c in tpl["columns"]]:
                        errors.append("obrazac '%s', formula '%s': stupac '[%s]' ne postoji u istom obrascu"
                                      % (tpl["key"], key, ref))
        schema[tpl["key"]] = cols

    # sample rows
    rows = seed.get("sampleRows", {})
    row_keys = {}                # template key -> set _key
    for tk, rlist in rows.items():
        ks = set()
        for r in rlist:
            rk = r.get("_key")
            if rk in ks:
                errors.append("zapisi '%s': dupliciran _key '%s'" % (tk, rk))
            ks.add(rk)
        row_keys[tk] = ks

    for tk, rlist in rows.items():
        cols = schema.get(tk, {})
        for r in rlist:
            for field, val in r.items():
                if field.startswith("_"):
                    continue
                col = cols.get(field)
                if col is None:
                    errors.append("zapis '%s/%s': nepoznat stupac '%s'" % (tk, r.get("_key"), field))
                    continue
                ctype = col["type"]
                if ctype == "codebook":
                    allowed = codebook_codes.get(col.get("codebook"), set())
                    if val not in allowed:
                        errors.append("zapis '%s/%s': '%s'='%s' nije sifra u sifrarniku '%s'"
                                      % (tk, r.get("_key"), field, val, col.get("codebook")))
                elif ctype == "reference":
                    target = col.get("reference")
                    if val not in row_keys.get(target, set()):
                        errors.append("zapis '%s/%s': veza '%s'='%s' ne pokazuje na _key u '%s'"
                                      % (tk, r.get("_key"), field, val, target))
                elif ctype == "date":
                    if not DATE_RE.match(str(val)):
                        errors.append("zapis '%s/%s': datum '%s'='%s' nije YYYY-MM-DD"
                                      % (tk, r.get("_key"), field, val))
                elif ctype == "number":
                    if not isinstance(val, (int, float)):
                        errors.append("zapis '%s/%s': broj '%s'='%s' nije numeric"
                                      % (tk, r.get("_key"), field, val))
                elif ctype == "formula":
                    errors.append("zapis '%s/%s': stupac '%s' je formula - vrijednost racuna server, ne salji je"
                                  % (tk, r.get("_key"), field))
    return errors


# --------------------------------------------------------------------------- #
# Uvoz
# --------------------------------------------------------------------------- #

def _empty_options():
    """Pun ColumnOptions objekt s defaultima - backend (Jackson 3, record s dva
    konstruktora) NE cita parcijalni objekt, pa se uvijek salje svih 16 polja,
    isto kao frontend (spread EMPTY_COLUMN_OPTIONS)."""
    return {
        "autoIncrement": False, "numberMode": None, "min": None, "max": None,
        "numberFormat": None, "pattern": None, "dateMode": None, "pickerMode": None,
        "multiple": False, "allowNewValues": False, "formula": None,
        "buttonLabel": None, "buttonAction": None, "targetTemplateId": None,
        "displayColumnKey": None, "onTargetDelete": None,
    }


def column_body(col, codebook_id_map, template_id_map):
    """Pretvori seed stupac u CreateColumnDefinitionRequest tijelo."""
    ctype = col["type"]
    options = _empty_options()
    body = {
        "columnKey": col["key"],
        "columnType": ctype,
        "label": col.get("label", ""),
        "required": bool(col.get("required", False)),
        "unique": bool(col.get("unique", False)),
        "options": options,
    }
    if ctype == "codebook":
        body["codebookId"] = codebook_id_map[col["codebook"]]
        options["pickerMode"] = "dropdown"
    elif ctype == "number":
        options["numberMode"] = col.get("numberMode", "float")
        if "min" in col:
            options["min"] = col["min"]
        if "max" in col:
            options["max"] = col["max"]
    elif ctype == "formula":
        options["formula"] = col["formula"]
    elif ctype == "reference":
        options["targetTemplateId"] = template_id_map[col["reference"]]
        options["displayColumnKey"] = col.get("display", "naziv")
        options["onTargetDelete"] = col.get("onTargetDelete", "restrict")
    elif ctype == "button":
        options["buttonLabel"] = col.get("buttonLabel", col.get("label", "Akcija"))
        options["buttonAction"] = col["buttonAction"]
    return body


def run_import(api, seed, tenant_login_needed=True):
    # 1) grupa (firma) ---------------------------------------------------------
    group_name = seed["group"]["name"]
    status, companies = api.get("/api/companies")
    if status != 200:
        die(status, companies, "GET /api/companies")
    company_id = next((c["id"] for c in companies if c["name"] == group_name), None)
    if company_id is None:
        status, created = api.post("/api/companies", {"name": group_name})
        if status not in (200, 201):
            die(status, created, "POST /api/companies")
        company_id = created["id"]
        print("  grupa: stvorena '%s' (id=%s)" % (group_name, company_id))
    else:
        print("  grupa: postoji '%s' (id=%s)" % (group_name, company_id))

    # 2) sifrarnici ------------------------------------------------------------
    # postojeci po dosegu: GLOBAL (bez zaglavlja), TENANT (sa zaglavljem grupe)
    existing = {}   # name -> id
    for scope, tenant in (("GLOBAL", None), ("TENANT", company_id)):
        status, lst = api.get("/api/codebooks?scope=" + scope, tenant=tenant)
        if status == 200:
            for cb in lst:
                existing[cb["name"]] = cb["id"]

    codebook_id_map = {}   # seed code -> id
    made_cb = 0
    for cb in seed.get("codebooks", []):
        scope = cb["scope"]
        tenant = company_id if scope == "TENANT" else None
        cb_id = existing.get(cb["name"])
        if cb_id is None:
            status, created = api.post("/api/codebooks", {"name": cb["name"], "scope": scope}, tenant=tenant)
            if status not in (200, 201):
                die(status, created, "POST /api/codebooks '%s'" % cb["name"])
            cb_id = created["id"]
            made_cb += 1
        # stavke: PUT je puna zamjena, pa uparujemo po sifri da postojeca stavka dobije svoj
        # id (update u mjestu) umjesto da bude obrisana i ponovno stvorena. Bez toga bi drugo
        # pokretanje pokusalo obrisati stavku koja je vec upisana u zapise -> 409. Postojece
        # stavke kojih u seedu nema se CUVAJU (ne brisemo nista).
        status, current = api.get("/api/codebooks/%s/items" % cb_id, tenant=tenant)
        current = current if status == 200 and isinstance(current, list) else []
        by_code = {c["code"]: c for c in current}
        seen = set()
        items = []
        for it in cb.get("items", []):
            entry = {"code": it["code"], "name": it["name"]}
            match = by_code.get(it["code"])
            if match:
                entry["id"] = match["id"]
            items.append(entry)
            seen.add(it["code"])
        for c in current:
            if c["code"] not in seen:
                items.append({"id": c["id"], "code": c["code"], "name": c["name"],
                              "active": c.get("active", True)})
        status, saved = api.put("/api/codebooks/%s/items" % cb_id, {"items": items}, tenant=tenant)
        if status not in (200, 201):
            die(status, saved, "PUT /api/codebooks/%s/items '%s'" % (cb_id, cb["name"]))
        codebook_id_map[cb["code"]] = cb_id
    print("  sifrarnici: %s novih, %s ukupno poravnato" % (made_cb, len(seed.get("codebooks", []))))

    # 3) obrasci ---------------------------------------------------------------
    status, templates = api.get("/api/templates", tenant=company_id)
    if status != 200:
        die(status, templates, "GET /api/templates")
    template_id_map = {}   # seed key -> id
    name_to_id = {t["name"]: t["id"] for t in templates}
    made_t = 0
    for tpl in seed.get("templates", []):
        t_id = name_to_id.get(tpl["name"])
        if t_id is None:
            status, created = api.post("/api/templates", {"name": tpl["name"]}, tenant=company_id)
            if status not in (200, 201):
                die(status, created, "POST /api/templates '%s'" % tpl["name"])
            t_id = created["id"]
            made_t += 1
        template_id_map[tpl["key"]] = t_id
        # Upitnik: retke definira admin (ovaj uvoz), korisnik samo bira odgovor. Backend to
        # provodi; endpoint postoji od V11 (ako uvoz padne s 404, backend nije restartan).
        if tpl.get("questionnaire"):
            status, resp = api.put("/api/templates/%s/questionnaire?value=true" % t_id, None,
                                   tenant=company_id)
            if status not in (200, 201):
                die(status, resp, "PUT /api/templates/%s/questionnaire (treba restart backenda za V11)" % t_id)
    print("  obrasci: %s novih, %s ukupno" % (made_t, len(seed.get("templates", []))))

    # 4) stupci - PROLAZ 1: sve osim veza (ukljucujuci formule) -----------------
    # 5) stupci - PROLAZ 2: veze (reference), kad svi obrasci vec postoje
    made_c = 0
    for phase, want_reference in (("ne-veze", False), ("veze", True)):
        for tpl in seed.get("templates", []):
            t_id = template_id_map[tpl["key"]]
            status, existing_cols = api.get("/api/templates/%s/columns" % t_id, tenant=company_id)
            if status != 200:
                die(status, existing_cols, "GET columns %s" % tpl["key"])
            have = set(c["columnKey"] for c in existing_cols)
            for col in tpl["columns"]:
                is_ref = col["type"] == "reference"
                if is_ref != want_reference:
                    continue
                if col["key"] in have:
                    continue
                body = column_body(col, codebook_id_map, template_id_map)
                status, created = api.post("/api/templates/%s/columns" % t_id, body, tenant=company_id)
                if status not in (200, 201):
                    die(status, created, "POST column %s/%s" % (tpl["key"], col["key"]))
                made_c += 1
    print("  stupci: %s novih deklarirano" % made_c)

    # 6) demo zapisi - u SVAKI obrazac koji je PRAZAN (per-form). Popunjen se preskace, da
    #    drugo pokretanje ne duplicira ni ne vraca namjerno obrisane zapise. Reference se
    #    razrjesavaju unutar istog pokretanja (obrasci-ciljevi idu prvi u sampleRowsOrder);
    #    ako cilj u ovom pokretanju nije uvezen (vec je bio popunjen), to se polje izostavi.
    order = seed.get("sampleRowsOrder", list(seed.get("sampleRows", {}).keys()))
    schema = {t["key"]: {c["key"]: c for c in t["columns"]} for t in seed["templates"]}
    row_id_map = {tk: {} for tk in order}   # template key -> _key -> created id
    made_r = 0
    skipped = []
    for tk in order:
        t_id = template_id_map[tk]
        status, page = api.get("/api/templates/%s/surveys?page=0" % t_id, tenant=company_id)
        if status != 200:
            die(status, page, "GET surveys %s" % tk)
        if page["totalElements"] > 0:
            skipped.append("%s(%s)" % (tk, page["totalElements"]))
            continue
        cols = schema[tk]
        for r in seed["sampleRows"].get(tk, []):
            data = {}
            for field, val in r.items():
                if field.startswith("_"):
                    continue
                col = cols[field]
                ctype = col["type"]
                if ctype == "reference":
                    resolved = row_id_map.get(col["reference"], {}).get(val)
                    if resolved is None:
                        print("    upozorenje: %s/%s veza '%s'='%s' nije razrijesena (cilj nije "
                              "uvezen u ovom pokretanju) - izostavljam" % (tk, r.get("_key"), field, val))
                        continue
                    data[field] = resolved
                elif ctype in ("formula", "button"):
                    continue                                   # nemaju vrijednost u zapisu
                else:
                    data[field] = val
            status, created = api.post("/api/templates/%s/surveys" % t_id, {"data": data}, tenant=company_id)
            if status not in (200, 201):
                die(status, created, "POST survey %s/%s" % (tk, r.get("_key")))
            row_id_map[tk][r["_key"]] = created["id"]
            made_r += 1
    msg = "  zapisi: %s demo zapisa uvezeno" % made_r
    if skipped:
        msg += " (preskoceni popunjeni obrasci: %s)" % ", ".join(skipped)
    print(msg)


# --------------------------------------------------------------------------- #
# main
# --------------------------------------------------------------------------- #

def main():
    ap = argparse.ArgumentParser(description="Uvoz GRC domene kroz API.")
    ap.add_argument("seed", help="putanja do seed JSON-a (npr. seed/grc.json)")
    ap.add_argument("--base", default="http://localhost:8080", help="base URL aplikacije")
    ap.add_argument("--user", default="admin", help="admin korisnicko ime")
    ap.add_argument("--password", default="admin", help="admin lozinka")
    ap.add_argument("--check", action="store_true", help="samo validacija seeda, bez HTTP-a")
    args = ap.parse_args()

    with open(args.seed, encoding="utf-8") as f:
        seed = json.load(f)

    print("== Validacija seeda '%s' ==" % args.seed)
    errors = validate_seed(seed)
    if errors:
        for e in errors:
            print("  PAD: " + e)
        print("Validacija: %s gresaka. Uvoz se ne pokrece." % len(errors))
        sys.exit(1)
    print("  OK - seed je konzistentan (%s sifrarnika, %s obrazaca)."
          % (len(seed.get("codebooks", [])), len(seed.get("templates", []))))
    if args.check:
        return

    print("== Prijava (%s) ==" % args.base)
    api = Api(args.base)
    status, resp = api.post("/api/auth/login", {"username": args.user, "password": args.password})
    if status != 200 or not resp or "token" not in resp:
        die(status, resp, "POST /api/auth/login")
    api.token = resp["token"]
    print("  prijavljen kao '%s' (uloga %s)" % (resp.get("username"), resp.get("role")))

    print("== Uvoz ==")
    run_import(api, seed)
    print("== Gotovo ==")


if __name__ == "__main__":
    main()

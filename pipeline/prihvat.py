#!/usr/bin/env python3
"""
Prijenosna provjera aplikacija podignutih skillom `app-skeleton`.

Radi ISKLJUCIVO preko HTTP-a, pa ne zna ni za Javu ni za Maven ni za Gradle ni za
imena razreda. Sve sto je specificno za jednu aplikaciju (putanje, nazivi polja,
nazivi parametara) stoji u JSON postavkama u mapi `projekti/`.

Tri skupine provjera:
  prihvat      fiksni prihvatni kriteriji iz faza skilla (uvijek isti pozivi)
  invarijante  tvrdnje koje moraju vrijediti za BILO KOJU shemu i bilo koje podatke;
               sheme i zapise izmislja generator, isti seed daje isti pokus
  izolacija    grupa A ne smije vidjeti nista od grupe B, ni na jednoj putanji

Pokretanje:
    python prihvat.py projekti/autoservis.json
    python prihvat.py projekti/autoservis.json --seed 42 --primjeraka 5
    python prihvat.py projekti/autoservis.json --samo invarijante
    python prihvat.py projekti/autoservis.json --provjeri-postavke   (bez mreze)

Izlazni kod: 0 ako je sve proslo, 1 ako je ista palo, 2 ako se pokus nije mogao
ni pripremiti (aplikacija ne radi, prijava ne prolazi, postavke krive).
"""

import argparse
import json
import random
import string
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

VERZIJA = "1.1"


# ---------------------------------------------------------------- HTTP klijent

class Odgovor:
    """Jedan HTTP odgovor, vec rasclanjen koliko se dalo."""

    def __init__(self, status, tekst):
        self.status = status
        self.tekst = tekst
        try:
            self.tijelo = json.loads(tekst) if tekst else None
        except (ValueError, TypeError):
            self.tijelo = None

    @property
    def poruka(self):
        """Poruka greske, ako je odgovor u dogovorenom obliku {"message": ...}."""
        if isinstance(self.tijelo, dict):
            for kljuc in ("message", "poruka", "error"):
                if kljuc in self.tijelo and isinstance(self.tijelo[kljuc], str):
                    return self.tijelo[kljuc]
        return self.tekst or ""

    def __repr__(self):
        return "%s %s" % (self.status, (self.tekst or "")[:120])


class Klijent:
    """Tanki omotac oko urllib-a. Namjerno bez ijedne vanjske biblioteke."""

    def __init__(self, base_url, timeout=20):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.broj_poziva = 0
        # 5xx je uvijek kvar posluzitelja, bez obzira sto je poziv trazio: ocekivana
        # odbijenica ima svoj status (400/403/404), a "Unexpected error" znaci da je
        # netko negdje pao na neocekivanom ulazu.
        self.petsto = []

    def zovi(self, metoda, putanja, tijelo=None, token=None, zaglavlja=None, upit=None):
        url = self.base_url + putanja
        if upit:
            parovi = [(k, str(v)) for k, v in upit if v is not None]
            if parovi:
                url += "?" + urllib.parse.urlencode(parovi)

        podaci = json.dumps(tijelo).encode("utf-8") if tijelo is not None else None
        zahtjev = urllib.request.Request(url, data=podaci, method=metoda)
        zahtjev.add_header("Content-Type", "application/json")
        zahtjev.add_header("Accept", "application/json")
        if token:
            zahtjev.add_header("Authorization", "Bearer " + token)
        for ime, vrijednost in (zaglavlja or {}).items():
            zahtjev.add_header(ime, str(vrijednost))

        self.broj_poziva += 1
        try:
            with urllib.request.urlopen(zahtjev, timeout=self.timeout) as odgovor:
                return Odgovor(odgovor.status, odgovor.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as greska:
            odgovor = Odgovor(greska.code, greska.read().decode("utf-8", "replace"))
            if odgovor.status >= 500:
                self.petsto.append("%s %s -> %s %s" % (metoda, putanja, odgovor.status,
                                                       odgovor.poruka[:60]))
            return odgovor
        except urllib.error.URLError as greska:
            raise SystemExit("Aplikacija ne odgovara na %s (%s).\n"
                             "Pokreni backend pa ponovi." % (self.base_url, greska.reason))


# ------------------------------------------------------------------- postavke

class Postavke:
    """
    Prijevod izmedu ove skripte i konkretne aplikacije.

    Sve sto se dvije aplikacije podignute istim skillom mogu razici stoji ovdje:
    naziv resursa grupe, segment putanje za zapise, nazivi polja u tijelu i nazivi
    parametara upita. U samim provjerama nema nijednog tvrdo upisanog naziva.
    """

    def __init__(self, podaci, izvor):
        self.izvor = izvor
        self.d = podaci
        self.naziv = podaci.get("naziv", izvor)
        self.base_url = self._obavezno("baseUrl")
        self.admin = self._obavezno("admin")
        self.grupe = self._obavezno("grupe")
        self.obrasci = self._obavezno("obrasci")
        self.stupci = self._obavezno("stupci")
        self.zapisi = self._obavezno("zapisi")
        self.korisnici = self._obavezno("korisnici")
        self.preskoci = set(podaci.get("preskoci", []))

    def _obavezno(self, kljuc):
        if kljuc not in self.d:
            raise SystemExit("Postavke %s nemaju obavezan kljuc \"%s\"." % (self.izvor, kljuc))
        return self.d[kljuc]

    # --- putanje

    def putanja_grupa(self, grupa_id=None):
        osnova = self.grupe["putanja"]
        return osnova if grupa_id is None else "%s/%s" % (osnova, grupa_id)

    def putanja_obrazac(self, obrazac_id=None):
        osnova = self.obrasci["putanja"]
        return osnova if obrazac_id is None else "%s/%s" % (osnova, obrazac_id)

    def putanja_stupci(self, obrazac_id, kljuc=None):
        osnova = self.stupci["putanja"].replace("{obrazac}", str(obrazac_id))
        return osnova if kljuc is None else "%s/%s" % (osnova, kljuc)

    def putanja_zapisi(self, obrazac_id, zapis_id=None):
        osnova = self.zapisi["putanja"].replace("{obrazac}", str(obrazac_id))
        return osnova if zapis_id is None else "%s/%s" % (osnova, zapis_id)

    # --- tijela zahtjeva

    def tijelo_grupe(self, naziv, kljuc):
        return self._popuni(self.grupe.get("tijelo", {"name": "{naziv}"}), naziv, kljuc)

    def tijelo_obrasca(self, naziv, kljuc):
        return self._popuni(self.obrasci.get("tijelo", {"name": "{naziv}"}), naziv, kljuc)

    def tijelo_korisnika(self, korisnicko_ime, lozinka, uloga, grupa_id):
        tijelo = self._popuni(self.korisnici.get("tijelo", {}), korisnicko_ime, korisnicko_ime)
        tijelo[self.korisnici.get("poljeIme", "username")] = korisnicko_ime
        tijelo[self.korisnici.get("poljeLozinka", "password")] = lozinka
        tijelo[self.korisnici.get("poljeUloga", "role")] = uloga
        tijelo[self.korisnici.get("poljeGrupa", "companyId")] = grupa_id
        return tijelo

    def tijelo_stupca(self, kljuc, tip):
        tijelo = dict(self.stupci.get("tijelo", {}))
        tijelo[self.stupci.get("poljeKljuc", "key")] = kljuc
        tijelo[self.stupci.get("poljeTip", "type")] = self.tip(tip)
        polje_natpis = self.stupci.get("poljeNatpis")
        if polje_natpis:
            tijelo[polje_natpis] = kljuc
        return tijelo

    def tijelo_zapisa(self, podaci):
        return {self.zapisi.get("poljeData", "data"): podaci}

    def _popuni(self, predlozak, naziv, kljuc):
        gotovo = {}
        for ime, vrijednost in predlozak.items():
            if isinstance(vrijednost, str):
                gotovo[ime] = vrijednost.replace("{naziv}", naziv).replace("{kljuc}", kljuc)
            else:
                gotovo[ime] = vrijednost
        return gotovo

    # --- citanje odgovora

    def tip(self, logicki):
        """Prijevod logickog tipa (tekst/broj/datum) u naziv koji aplikacija koristi."""
        return self.stupci.get("tipovi", {}).get(logicki, logicki)

    def data_iz(self, zapis):
        return (zapis or {}).get(self.zapisi.get("poljeData", "data")) or {}

    def kljuc_stupca(self, stupac):
        return (stupac or {}).get(self.stupci.get("poljeKljuc", "key"))

    def zaglavlje_grupe(self, grupa_id):
        ime = self.grupe.get("zaglavljeOdabira")
        return {ime: grupa_id} if ime else {}

    # --- parametri upita za popis zapisa

    def upit_popisa(self, stranica=None, velicina=None, sort_kljuc=None, uzlazno=None,
                    filter_kljuc=None, pretraga=None):
        u = self.zapisi.get("upit", {})
        parovi = []
        if stranica is not None and u.get("stranica"):
            parovi.append((u["stranica"], stranica))
        if velicina is not None and u.get("velicina"):
            parovi.append((u["velicina"], velicina))
        if sort_kljuc is not None and u.get("sortKljuc"):
            parovi.append((u["sortKljuc"], sort_kljuc))
            smjer = u.get("sortSmjer")
            if smjer and uzlazno is not None:
                parovi.append((smjer["parametar"], smjer["uzlazno"] if uzlazno else smjer["silazno"]))
        if filter_kljuc is not None and pretraga is not None:
            f = u.get("filter", {})
            if f.get("oblik") == "dva-parametra":
                parovi.append((f["parametarKljuc"], filter_kljuc))
                parovi.append((f["parametarVrijednost"], pretraga))
            elif f.get("oblik") == "kljuc-dvotocka-vrijednost":
                parovi.append((f["parametar"], "%s:%s" % (filter_kljuc, pretraga)))
            elif f.get("oblik") == "kljuc-operator-vrijednost":
                # Oblik `kljuc:operator:vrijednost` - filtar zamisljen kao popis
                # uvjeta spojenih s I, pa svaki uvjet nosi i svoj operator.
                parovi.append((f["parametar"], "%s:%s:%s" % (
                    filter_kljuc, f.get("operator", "contains"), pretraga)))
        return parovi

    @property
    def zna_filtrirati(self):
        """Opisuju li postavke uopce kako se ovoj aplikaciji salje filtar."""
        return bool(self.zapisi.get("upit", {}).get("filter"))

    @property
    def prima_velicinu(self):
        """Prima li aplikacija velicinu stranice kao parametar upita."""
        return bool(self.zapisi.get("upit", {}).get("velicina"))

    @property
    def zadana_velicina(self):
        """Koliko redaka stane na stranicu kad se velicina ne moze zadati."""
        return int(self.zapisi.get("upit", {}).get("zadanaVelicina", 25))


def ucitaj_postavke(putanja):
    try:
        with open(putanja, "r", encoding="utf-8") as f:
            return Postavke(json.load(f), putanja)
    except FileNotFoundError:
        raise SystemExit("Nema datoteke s postavkama: %s" % putanja)
    except ValueError as greska:
        raise SystemExit("Postavke %s nisu ispravan JSON: %s" % (putanja, greska))


# -------------------------------------------------------------------- zapisnik

ZELENO, CRVENO, ZUTO, SIVO, MASNO, KRAJ = (
    "\033[32m", "\033[31m", "\033[33m", "\033[90m", "\033[1m", "\033[0m")


class Zapisnik:
    """Skuplja rezultate i na kraju ih ispisuje kao izvjestaj."""

    def __init__(self, boje=True):
        self.stavke = []
        self.boje = boje

    def _b(self, tekst, boja):
        return "%s%s%s" % (boja, tekst, KRAJ) if self.boje else tekst

    def provjeri(self, sekcija, naziv, uvjet, detalj=""):
        uvjet = bool(uvjet)
        self.stavke.append({"sekcija": sekcija, "naziv": naziv, "prosao": uvjet,
                            "detalj": "" if uvjet else detalj})
        oznaka = self._b(" OK ", ZELENO) if uvjet else self._b("PAD ", CRVENO)
        print("  [%s] %s" % (oznaka, naziv))
        if not uvjet and detalj:
            print("         %s" % self._b(detalj, SIVO))
        return uvjet

    def preskoceno(self, sekcija, naziv, razlog):
        self.stavke.append({"sekcija": sekcija, "naziv": naziv, "prosao": None, "detalj": razlog})
        print("  [%s] %s" % (self._b("PRSK", ZUTO), naziv))
        print("         %s" % self._b(razlog, SIVO))

    def naslov(self, tekst):
        print("\n" + self._b(tekst.upper(), MASNO))

    @property
    def palo(self):
        return [s for s in self.stavke if s["prosao"] is False]

    def izvjestaj(self):
        proslo = len([s for s in self.stavke if s["prosao"] is True])
        presk = len([s for s in self.stavke if s["prosao"] is None])
        print("\n" + "-" * 70)
        print("PROSLO %d   PALO %d   PRESKOCENO %d" % (proslo, len(self.palo), presk))
        if self.palo:
            print("\nSto je palo (kandidati za parking lot):")
            for s in self.palo:
                print("  - [%s] %s" % (s["sekcija"], s["naziv"]))
                if s["detalj"]:
                    print("      %s" % s["detalj"])
        return self.palo


# ------------------------------------------------------------------ generator

SLOVA = string.ascii_lowercase
LOGICKI_TIPOVI = ["tekst", "broj", "datum"]


class Generator:
    """
    Izmislja sheme i zapise. Sve ide kroz jedan `random.Random(seed)`, pa isti
    seed daje potpuno isti pokus - bez toga se pad ne bi dao ponoviti.
    """

    def __init__(self, seed):
        self.seed = seed
        self.r = random.Random(seed)

    def kljuc(self, duljina=6):
        return "".join(self.r.choice(SLOVA) for _ in range(duljina))

    def shema(self, najmanje=2, najvise=6):
        koliko = self.r.randint(najmanje, najvise)
        kljucevi, stupci = set(), []
        while len(stupci) < koliko:
            k = self.kljuc()
            if k in kljucevi:
                continue
            kljucevi.add(k)
            stupci.append({"kljuc": k, "tip": self.r.choice(LOGICKI_TIPOVI)})
        return stupci

    def vrijednost(self, tip):
        if tip == "broj":
            return self.r.choice([0, 1, 7, 42, self.r.randint(-1000, 1000)])
        if tip == "datum":
            return (date(2020, 1, 1) + timedelta(days=self.r.randint(0, 3000))).isoformat()
        izbor = self.r.random()
        if izbor < 0.12:
            return ""                       # prazan tekst je dopustena vrijednost, ne greska
        if izbor < 0.22:
            return "  razmaci  "            # rubni slucaj za pretragu i poredak
        if izbor < 0.32:
            return "Ćšđž ČŠĐŽ"              # dijakritika mora prezivjeti put kroz bazu
        tekst = "".join(self.r.choice(SLOVA + " ") for _ in range(self.r.randint(1, 20))).strip()
        return tekst or "x"

    def zapis(self, shema, popuni_sve=True):
        podaci = {}
        for stupac in shema:
            if popuni_sve or self.r.random() < 0.7:
                podaci[stupac["kljuc"]] = self.vrijednost(stupac["tip"])
        return podaci


# -------------------------------------------------------------------- okolina

class Sudionik:
    """Jedan prijavljeni korisnik: token + zaglavlja koja svaki njegov poziv nosi."""

    def __init__(self, klijent, postavke, token, opis, grupa_id=None, zaglavlja=None):
        self.k = klijent
        self.p = postavke
        self.token = token
        self.opis = opis
        self.grupa_id = grupa_id
        self.zaglavlja = zaglavlja or {}
        self.okolina = None
        # Za provjere koje grupu trose: nakon brisanja se mora dati provjeriti da se
        # njezin korisnik vise ne moze prijaviti, a pospremanje mora znati sto je nestalo.
        self.korisnik_id = None
        self.prijava = None

    def zovi(self, metoda, putanja, tijelo=None, upit=None):
        return self.k.zovi(metoda, putanja, tijelo=tijelo, token=self.token,
                           zaglavlja=self.zaglavlja, upit=upit)

    # --- kratice koje se koriste u vise provjera

    def napravi_obrazac(self, naziv, kljuc):
        odgovor = self.zovi("POST", self.p.putanja_obrazac(), self.p.tijelo_obrasca(naziv, kljuc))
        if self.okolina is not None and odgovor.status < 300 and isinstance(odgovor.tijelo, dict):
            self.okolina.za_brisanje.append(("obrazac", (self, odgovor.tijelo.get("id"))))
        return odgovor

    def dodaj_stupac(self, obrazac_id, kljuc, tip):
        return self.zovi("POST", self.p.putanja_stupci(obrazac_id), self.p.tijelo_stupca(kljuc, tip))

    def dodaj_shemu(self, obrazac_id, shema):
        for stupac in shema:
            odgovor = self.dodaj_stupac(obrazac_id, stupac["kljuc"], stupac["tip"])
            if odgovor.status >= 300:
                return odgovor
        return None

    def napravi_zapis(self, obrazac_id, podaci):
        return self.zovi("POST", self.p.putanja_zapisi(obrazac_id), self.p.tijelo_zapisa(podaci))

    def dohvati_zapis(self, obrazac_id, zapis_id):
        return self.zovi("GET", self.p.putanja_zapisi(obrazac_id, zapis_id))

    def popis_zapisa(self, obrazac_id, **upit):
        return self.zovi("GET", self.p.putanja_zapisi(obrazac_id), upit=self.p.upit_popisa(**upit))


class Okolina:
    """
    Priprema pokusa: prijavi admina, napravi dvije svjeze grupe i po jednog
    korisnika u svakoj.

    Zasto svjeze grupe, a ne postojece: pokus ne smije ovisiti o tome sto je
    zateceno u bazi, a ni ostaviti trag u tudim podacima. Sve nastalo nosi
    prefiks `zzz-prihvat-` i brise se na kraju.
    """

    PREFIKS = "zzz-prihvat"

    def __init__(self, postavke, generator, zapisnik):
        self.p = postavke
        self.g = generator
        self.z = zapisnik
        # Seed sluzi da se isti pokus da PONOVITI; imena objekata se pritom ne smiju
        # ponoviti, jer bi drugo pokretanje s istim seedom palo na "ime je zauzeto".
        # Zato oznaka pokusa ide iz nezasijanog izvora.
        self.oznaka = "%05d" % random.SystemRandom().randrange(100000)
        self.k = Klijent(postavke.base_url)
        self.admin = None
        self.a = None
        self.b = None
        self.za_brisanje = []
        self.kljucevi_grupa = {}

    def ime(self, sufiks):
        """Par (naziv za ljude, kljuc za stroj) - kljuc prolazi i najstrozi uzorak."""
        return ("%s-%s-%s" % (self.PREFIKS, self.oznaka, sufiks),
                "%s_%s_%s" % (self.PREFIKS.replace("-", "_"), self.oznaka, sufiks.replace("-", "_")))

    def prijavi(self, korisnicko_ime, lozinka):
        odgovor = self.k.zovi("POST", self.p.d.get("prijavaPutanja", "/api/auth/login"),
                              {"username": korisnicko_ime, "password": lozinka})
        if odgovor.status != 200 or not isinstance(odgovor.tijelo, dict):
            return None, odgovor
        return odgovor.tijelo.get("token"), odgovor

    def pripremi(self):
        token, odgovor = self.prijavi(self.p.admin["username"], self.p.admin["password"])
        if not token:
            raise SystemExit("Prijava administratora nije uspjela (%s). Provjeri postavke %s."
                             % (odgovor, self.p.izvor))
        self.admin = Sudionik(self.k, self.p, token, "administrator")

        self.a = self._napravi_grupu("a")
        self.b = self._napravi_grupu("b")
        return self

    def _napravi_grupu(self, oznaka):
        # naziv smije biti sto god, ali kljuc mora proci uzorak koji neke aplikacije
        # traze za sifre ([a-zA-Z][a-zA-Z0-9_]*) - zato podvlake, ne crtice
        biljeg, kljuc = self.ime("grupa-%s" % oznaka)
        odgovor = self.admin.zovi("POST", self.p.putanja_grupa(), self.p.tijelo_grupe(biljeg, kljuc))
        if odgovor.status >= 300 or not isinstance(odgovor.tijelo, dict):
            raise SystemExit("Ne mogu napraviti grupu za pokus (%s).\n"
                             "Provjeri kljuc \"grupe\" u postavkama." % odgovor)
        grupa_id = odgovor.tijelo.get("id")
        # Kljuc se pamti jer ga brisanje zna traziti natrag kao potvrdu.
        self.kljucevi_grupa[grupa_id] = kljuc
        self.za_brisanje.append(("grupa", grupa_id))

        korisnik = "%s-korisnik" % biljeg
        lozinka = "prihvat-lozinka-123"
        stvaranje = self.admin.zovi("POST", self.p.korisnici["putanja"],
                                    self.p.tijelo_korisnika(korisnik, lozinka, "TENANT_ADMIN", grupa_id))
        if stvaranje.status >= 300:
            raise SystemExit("Ne mogu napraviti korisnika grupe (%s).\n"
                             "Provjeri kljuc \"korisnici\" u postavkama." % stvaranje)
        self.zapamti_korisnika(stvaranje)
        token, odgovor = self.prijavi(korisnik, lozinka)
        if not token:
            raise SystemExit("Novi korisnik se ne moze prijaviti (%s)." % odgovor)
        sudionik = Sudionik(self.k, self.p, token, "grupa %s" % oznaka, grupa_id,
                            self.p.zaglavlje_grupe(grupa_id))
        sudionik.okolina = self
        sudionik.prijava = (korisnik, lozinka)
        if isinstance(stvaranje.tijelo, dict):
            sudionik.korisnik_id = stvaranje.tijelo.get("id")
        return sudionik

    def dodatna_grupa(self, oznaka):
        """Jos jedna grupa, ista kao A i B - za provjeru koja grupu potrosi."""
        return self._napravi_grupu(oznaka)

    def admin_u_grupi(self, grupa_id):
        """
        Administrator kojem je odabrana bas ta grupa.

        Isti token, ali sa zaglavljem odabira: administratorov doseg se ne vidi iz
        tokena, jer on grupu ne nosi, nego iz onoga sto salje uz poziv.
        """
        return Sudionik(self.k, self.p, self.admin.token, "administrator (grupa %s)" % grupa_id,
                        grupa_id, self.p.zaglavlje_grupe(grupa_id))

    def zaboravi_grupu(self, sudionik):
        """
        Makne iz popisa za pospremanje sve sto je nestalo zajedno s grupom.

        Bez toga bi pospremanje brisalo vec obrisano i svaki bi pokus zavrsavao
        popisom "nije se dalo pospremiti" koji ne znaci nista.
        """
        ostatak = []
        for vrsta, ident in self.za_brisanje:
            if vrsta == "grupa" and ident == sudionik.grupa_id:
                continue
            if vrsta == "korisnik" and ident == sudionik.korisnik_id:
                continue
            if vrsta == "obrazac" and ident[0] is sudionik:
                continue
            ostatak.append((vrsta, ident))
        self.za_brisanje = ostatak

    def _koraci_brisanja(self, grupa_id):
        """
        Brisanje grupe zna imati vise od jednog koraka.

        Negdje je to jedan DELETE, a negdje se grupa prvo arhivira pa tek onda
        prazni - zato postavke smiju opisati niz koraka, ne samo jednu putanju.

        U putanji se zamjenjuju {id} i {kljuc}: potvrda koja se ne otipka
        slucajno cesto znaci prepisan kljuc grupe, pa ga korak mora moci
        poslati natrag.
        """
        opis = self.p.grupe.get("brisanje", self.p.putanja_grupa("{id}"))
        koraci = [{"metoda": "DELETE", "putanja": opis}] if isinstance(opis, str) else list(opis)
        kljuc = self.kljucevi_grupa.get(grupa_id, "")
        return [{"metoda": k.get("metoda", "DELETE"),
                 "putanja": k["putanja"].replace("{id}", str(grupa_id)).replace("{kljuc}", kljuc)}
                for k in koraci]

    def zapamti_korisnika(self, odgovor):
        if isinstance(odgovor.tijelo, dict) and odgovor.tijelo.get("id"):
            self.za_brisanje.append(("korisnik", odgovor.tijelo["id"]))

    def pospremi(self):
        """
        Brise sve sto je pokus napravio, korisnike prije grupa.

        Redoslijed nije kozmetika: grupa na koju jos pokazuje korisnik obicno se ne
        da obrisati, pa bi pokus za sobom ostavljao smece pri svakom pokretanju.
        """
        neuspjeli = []
        for vrsta, ident in reversed(self.za_brisanje):
            if vrsta != "obrazac":
                continue
            vlasnik, obrazac_id = ident
            odgovor = vlasnik.zovi("DELETE", self.p.putanja_obrazac(obrazac_id))
            if odgovor.status >= 300:
                neuspjeli.append("obrazac %s -> %s" % (obrazac_id, odgovor.status))
        for vrsta, ident in reversed(self.za_brisanje):
            if vrsta != "korisnik":
                continue
            odgovor = self.admin.zovi("DELETE", "%s/%s" % (self.p.korisnici["putanja"], ident))
            if odgovor.status >= 300:
                neuspjeli.append("korisnik %s -> %s" % (ident, odgovor.status))
        for vrsta, ident in reversed(self.za_brisanje):
            if vrsta != "grupa":
                continue
            for korak in self._koraci_brisanja(ident):
                odgovor = self.admin.zovi(korak["metoda"], korak["putanja"])
                if odgovor.status >= 300:
                    neuspjeli.append("%s -> %s %s"
                                     % (korak["putanja"], odgovor.status, odgovor.poruka[:80]))
                    break
        return neuspjeli


# ------------------------------------------------------------------- pomagala

def jednako(poslano, vraceno):
    """
    Usporedba vrijednosti kakvu ocekujemo od putovanja kroz JSON i bazu.

    Broj se smije vratiti kao 42 ili 42.0 - to je isti broj, samo drugi zapis.
    Sve ostalo mora doci natrag doslovno; "0" i 0 NISU isto i namjerno padaju.
    """
    if isinstance(poslano, bool) or isinstance(vraceno, bool):
        return poslano is vraceno
    if isinstance(poslano, (int, float)) and isinstance(vraceno, (int, float)):
        return abs(float(poslano) - float(vraceno)) < 1e-9
    return poslano == vraceno


def prazna(vrijednost):
    """Prazno polje: nepopunjeno, ne vrijednost."""
    return vrijednost is None or vrijednost == ""


def skrati(vrijednost, duljina=90):
    tekst = json.dumps(vrijednost, ensure_ascii=False) if not isinstance(vrijednost, str) else vrijednost
    return tekst if len(tekst) <= duljina else tekst[:duljina] + "..."


def sadrzaj_stranice(odgovor):
    """Popis zapisa iz odgovora, bez obzira je li straniciran ili obican niz."""
    if isinstance(odgovor.tijelo, list):
        return odgovor.tijelo
    if isinstance(odgovor.tijelo, dict):
        for kljuc in ("content", "sadrzaj", "items"):
            if isinstance(odgovor.tijelo.get(kljuc), list):
                return odgovor.tijelo[kljuc]
    return []


def ukupno_zapisa(odgovor):
    """
    Prijavljeni ukupan broj rezultata, ili None ako ga aplikacija ne salje.

    Nije isto sto i broj redaka na stranici: taj broj je aplikacijina TVRDNJA o
    tome koliko ih ukupno ima, pa se da usporediti sa stvarnim stanjem.
    """
    if isinstance(odgovor.tijelo, dict):
        for kljuc in ("totalElements", "ukupno", "total", "totalCount"):
            if isinstance(odgovor.tijelo.get(kljuc), int):
                return odgovor.tijelo[kljuc]
    return None


# --------------------------------------------------- 1. PRIHVATNI KRITERIJI

def sekcija_prihvat(ok, z):
    """
    Fiksne provjere: uvijek isti pozivi, uvijek isti ocekivani odgovori.

    Svaka odgovara jednom prihvatnom kriteriju iz faza skilla `app-skeleton`,
    pa se pad da procitati kao "faza N nije zadovoljena".
    """
    p, k = ok.p, ok.k
    z.naslov("1 - prihvatni kriteriji")

    # faza 2: bez identiteta nema pristupa
    bez_tokena = k.zovi("GET", p.putanja_obrazac())
    z.provjeri("prihvat", "poziv bez tokena vraca 401",
               bez_tokena.status == 401, "dobiveno %s" % bez_tokena)

    z.provjeri("prihvat", "greska je u obliku {\"message\": ...}",
               isinstance(bez_tokena.tijelo, dict) and bool(bez_tokena.poruka),
               "tijelo: %s" % skrati(bez_tokena.tekst))

    _, kriva = ok.prijavi(p.admin["username"], "sigurno-kriva-lozinka-999")
    z.provjeri("prihvat", "kriva lozinka vraca 401 (ne 403)",
               kriva.status == 401, "dobiveno %s" % kriva)

    _, nepoznat = ok.prijavi("nepostojeci-korisnik-zzz", "bilokakva-lozinka")
    z.provjeri("prihvat", "nepoznat korisnik vraca 401",
               nepoznat.status == 401, "dobiveno %s" % nepoznat)

    z.provjeri("prihvat", "poruka o neuspjeloj prijavi ne odaje postoji li korisnik",
               kriva.poruka == nepoznat.poruka,
               "kriva lozinka: %r, nepoznat korisnik: %r" % (kriva.poruka, nepoznat.poruka))

    nepostojeca = ok.admin.zovi("GET", "/api/putanja-koje-sigurno-nema")
    z.provjeri("prihvat", "nepostojeca putanja vraca 404, ne 500",
               nepostojeca.status == 404, "dobiveno %s" % nepostojeca)

    me = ok.a.zovi("GET", p.d.get("mePutanja", "/api/auth/me"))
    z.provjeri("prihvat", "prijavljen korisnik dohvaca svoj profil",
               me.status == 200, "dobiveno %s" % me)

    # faza 4: shema je ugovor
    obrazac = ok.a.napravi_obrazac(*ok.ime("ugovor"))
    if obrazac.status >= 300:
        z.preskoceno("prihvat", "shema kao ugovor", "obrazac se ne da napraviti: %s" % obrazac)
        return
    obrazac_id = obrazac.tijelo.get("id")
    ok.a.dodaj_stupac(obrazac_id, "tekstualni", "tekst")
    ok.a.dodaj_stupac(obrazac_id, "brojcani", "broj")

    # Tijelo bez neobaveznih polja: klijent koji ih ne salje ne smije dobiti 500.
    # Ovo je zamka Jacksona nad primitivnim boolean poljem - izostavljeno polje se
    # ne moze mapirati u `boolean`, pa zahtjev pukne prije ijedne validacije.
    golo = {p.stupci.get("poljeKljuc", "key"): "golo_polje",
            p.stupci.get("poljeTip", "type"): p.tip("tekst")}
    if p.stupci.get("poljeNatpis"):
        golo[p.stupci["poljeNatpis"]] = "Golo polje"
    odgovor_golo = ok.a.zovi("POST", p.putanja_stupci(obrazac_id), golo)
    z.provjeri("prihvat", "izostavljeno neobavezno polje ne rusi poziv (nije 5xx)",
               odgovor_golo.status < 500, "dobiveno %s" % odgovor_golo)

    nepoznat_kljuc = "kljuc_kojeg_nema_u_shemi"
    odbijeno = ok.a.napravi_zapis(obrazac_id, {"tekstualni": "ok", nepoznat_kljuc: "vrijednost"})
    z.provjeri("prihvat", "nedeklariran kljuc pri upisu vraca 400",
               odbijeno.status == 400, "dobiveno %s" % odbijeno)
    z.provjeri("prihvat", "poruka o nedeklariranom kljucu imenuje bas taj kljuc",
               nepoznat_kljuc in odbijeno.poruka, "poruka: %r" % skrati(odbijeno.poruka))

    krivi_tip = ok.a.napravi_zapis(obrazac_id, {"brojcani": "ovo nije broj"})
    z.provjeri("prihvat", "kriva vrsta vrijednosti vraca 400",
               krivi_tip.status == 400, "dobiveno %s" % krivi_tip)

    # 404 ima prednost pred 400: zapis ne postoji, a tijelo je usput i neispravno
    nepostojeci = ok.a.zovi("PUT", p.putanja_zapisi(obrazac_id, 999999999),
                            p.tijelo_zapisa({nepoznat_kljuc: "x"}))
    z.provjeri("prihvat", "izmjena nepostojeceg zapisa vraca 404, ne 400",
               nepostojeci.status == 404, "dobiveno %s" % nepostojeci)

    # faza 5: sortiranje po kljucu izvan sheme nije tiho zanemarivanje
    ok.a.napravi_zapis(obrazac_id, {"tekstualni": "a"})
    sort = ok.a.popis_zapisa(obrazac_id, sort_kljuc="kljuc_po_kojem_se_ne_moze_sortirati", uzlazno=True)
    if not p.zapisi.get("upit", {}).get("sortKljuc"):
        z.preskoceno("prihvat", "nepoznat kljuc za sortiranje vraca 400",
                     "postavke ne opisuju parametar za sortiranje")
    else:
        z.provjeri("prihvat", "nepoznat kljuc za sortiranje vraca 400",
                   sort.status == 400, "dobiveno %s (tiho zanemarivanje daje krivi poredak)" % sort)

    # faza 2: uloga bez prava na radnju je 403, ne 404 i ne 200
    korisnik = ok.ime("obicni")[0]
    lozinka = "prihvat-lozinka-123"
    stvoren = ok.admin.zovi("POST", p.korisnici["putanja"],
                            p.tijelo_korisnika(korisnik, lozinka, "USER", ok.a.grupa_id))
    if stvoren.status >= 300:
        z.preskoceno("prihvat", "obican korisnik ne smije stvarati korisnike",
                     "ne mogu napraviti korisnika uloge USER: %s" % stvoren)
    else:
        ok.zapamti_korisnika(stvoren)
        token, _ = ok.prijavi(korisnik, lozinka)
        obicni = Sudionik(k, p, token, "obicni korisnik", ok.a.grupa_id, p.zaglavlje_grupe(ok.a.grupa_id))
        pokusaj = obicni.zovi("POST", p.korisnici["putanja"],
                              p.tijelo_korisnika(ok.ime("podmetnut")[0], lozinka, "USER", ok.a.grupa_id))
        z.provjeri("prihvat", "obican korisnik ne smije stvarati korisnike (403)",
                   pokusaj.status == 403, "dobiveno %s" % pokusaj)


# ------------------------------------------------------- 2. INVARIJANTE

def sekcija_invarijante(ok, z, primjeraka):
    """
    Tvrdnje koje moraju vrijediti za BILO KOJU shemu i bilo koje podatke.

    Sheme i vrijednosti izmislja generator. To je bit cijelog alata: obican test
    provjerava slucaj koji je netko prepisao iz koda, a ovdje se tvrdnja pise iz
    ugovora (skilla) i pusta na podatke koje nitko nije birao.
    """
    p = ok.p
    z.naslov("2 - invarijante (generativno, seed %s)" % ok.g.seed)

    for i in range(primjeraka):
        shema = ok.g.shema()
        obrazac = ok.a.napravi_obrazac(*ok.ime("inv%d" % i))
        if obrazac.status >= 300:
            z.preskoceno("invarijante", "primjerak %d" % (i + 1), "obrazac: %s" % obrazac)
            continue
        obrazac_id = obrazac.tijelo.get("id")
        greska = ok.a.dodaj_shemu(obrazac_id, shema)
        if greska is not None:
            z.preskoceno("invarijante", "primjerak %d" % (i + 1),
                         "shema se ne da postaviti: %s" % greska)
            continue

        opis = "primjerak %d (%d stupaca: %s)" % (
            i + 1, len(shema), ", ".join("%s:%s" % (s["kljuc"], s["tip"]) for s in shema))
        print("  %s%s%s" % (SIVO, opis, KRAJ))

        _inv_spremi_procitaj(ok, z, obrazac_id, shema, i)
        _inv_prazno_ostaje_prazno(ok, z, obrazac_id, shema, i)
        _inv_nedeklariran_kljuc(ok, z, obrazac_id, shema, i)
        _inv_validacija_samo_na_upisu(ok, z, obrazac_id, shema, i)
        _inv_brisanje_stupca(ok, z, obrazac_id, shema, i)

    _inv_poredak(ok, z)
    _inv_stranicenje(ok, z)
    _inv_pretraga(ok, z)
    _inv_brisanje_grupe(ok, z)
    _inv_odabir_grupe(ok, z)


def _inv_spremi_procitaj(ok, z, obrazac_id, shema, i):
    """INVARIJANTA 1: sto je spremljeno, to se i procita - za bilo koju shemu."""
    podaci = ok.g.zapis(shema, popuni_sve=True)
    stvoren = ok.a.napravi_zapis(obrazac_id, podaci)
    if stvoren.status >= 300:
        z.provjeri("invarijante", "[%d] spremi = procitaj isto" % (i + 1), False,
                   "upis valjanog zapisa odbijen: %s | poslano %s" % (stvoren, skrati(podaci)))
        return
    zapis_id = stvoren.tijelo.get("id")
    procitano = ok.p.data_iz(ok.a.dohvati_zapis(obrazac_id, zapis_id).tijelo)

    razlike = []
    for kljuc, vrijednost in podaci.items():
        if not jednako(vrijednost, procitano.get(kljuc)):
            razlike.append("%s: poslano %r, vraceno %r" % (kljuc, vrijednost, procitano.get(kljuc)))
    z.provjeri("invarijante", "[%d] spremi = procitaj isto" % (i + 1), not razlike,
               " | ".join(razlike[:3]))


def _inv_prazno_ostaje_prazno(ok, z, obrazac_id, shema, i):
    """
    INVARIJANTA 2: polje koje korisnik nije popunio ne smije dobiti vrijednost.

    Ovo je provjera za bug koji tiho upise 0 ili false u polje koje je u sucelju
    stajalo kao "-": zapis se pri spremanju nacas napuni tipskim defaultima.
    """
    if len(shema) < 2:
        return
    popunjen, prazan = shema[0], shema[1:]
    podaci = {popunjen["kljuc"]: ok.g.vrijednost(popunjen["tip"])}
    stvoren = ok.a.napravi_zapis(obrazac_id, podaci)
    if stvoren.status >= 300:
        z.provjeri("invarijante", "[%d] prazno ostaje prazno" % (i + 1), False,
                   "zapis s dijelom polja odbijen: %s" % stvoren)
        return
    procitano = ok.p.data_iz(ok.a.dohvati_zapis(obrazac_id, stvoren.tijelo.get("id")).tijelo)

    izmisljeno = ["%s = %r" % (s["kljuc"], procitano.get(s["kljuc"]))
                  for s in prazan
                  if s["kljuc"] in procitano and not prazna(procitano.get(s["kljuc"]))]
    z.provjeri("invarijante", "[%d] prazno ostaje prazno" % (i + 1), not izmisljeno,
               "polja koja nisu poslana vratila su se popunjena: %s" % ", ".join(izmisljeno[:3]))


def _inv_nedeklariran_kljuc(ok, z, obrazac_id, shema, i):
    """INVARIJANTA 3: ono cega nema u shemi ne smije uci u zapis."""
    kljucevi = {s["kljuc"] for s in shema}
    strani = ok.g.kljuc(8)
    while strani in kljucevi:
        strani = ok.g.kljuc(8)
    podaci = ok.g.zapis(shema, popuni_sve=False)
    podaci[strani] = "vrijednost koja nema svoj stupac"
    odgovor = ok.a.napravi_zapis(obrazac_id, podaci)
    z.provjeri("invarijante", "[%d] nedeklariran kljuc -> 400 i imenuje kljuc" % (i + 1),
               odgovor.status == 400 and strani in odgovor.poruka,
               "kljuc %r -> %s" % (strani, odgovor))


def _inv_validacija_samo_na_upisu(ok, z, obrazac_id, shema, i):
    """
    INVARIJANTA 4: izmjena sheme ne smije oboriti citanje starijih zapisa.

    Zapis nastao prije nego je stupac dodan i dalje se mora dati procitati -
    inace bi svaka promjena sheme unatrag pokvarila povijest.
    """
    podaci = ok.g.zapis(shema, popuni_sve=False)
    stvoren = ok.a.napravi_zapis(obrazac_id, podaci)
    if stvoren.status >= 300:
        return
    zapis_id = stvoren.tijelo.get("id")
    novi = ok.a.dodaj_stupac(obrazac_id, "naknadni_" + ok.g.kljuc(4), "tekst")
    if novi.status >= 300:
        z.preskoceno("invarijante", "[%d] validacija samo na upisu" % (i + 1),
                     "stupac se ne da naknadno dodati: %s" % novi)
        return
    procitano = ok.a.dohvati_zapis(obrazac_id, zapis_id)
    z.provjeri("invarijante", "[%d] stariji zapis se cita i nakon izmjene sheme" % (i + 1),
               procitano.status == 200, "dobiveno %s" % procitano)


def _inv_brisanje_stupca(ok, z, obrazac_id, shema, i):
    """
    INVARIJANTA 5: brisanje stupca mice deklaraciju I vrijednosti, u jednom potezu.

    Ako ostane samo jedno od toga, dobiva se ili stupac koji se vidi a ne postoji,
    ili vrijednost koju nijedna deklaracija ne pokriva - oboje se poslije tesko vadi.
    """
    zrtva = shema[-1]["kljuc"]
    podaci = {s["kljuc"]: ok.g.vrijednost(s["tip"]) for s in shema}
    stvoren = ok.a.napravi_zapis(obrazac_id, podaci)
    if stvoren.status >= 300:
        return
    zapis_id = stvoren.tijelo.get("id")

    brisanje = ok.a.zovi("DELETE", ok.p.putanja_stupci(obrazac_id, zrtva))
    if brisanje.status >= 300:
        z.provjeri("invarijante", "[%d] brisanje stupca uspije" % (i + 1), False,
                   "%s -> %s" % (zrtva, brisanje))
        return

    stupci = ok.a.zovi("GET", ok.p.putanja_stupci(obrazac_id))
    preostali = [ok.p.kljuc_stupca(s) for s in sadrzaj_stranice(stupci)]
    z.provjeri("invarijante", "[%d] obrisan stupac nestaje iz sheme" % (i + 1),
               zrtva not in preostali, "shema jos ima %r" % zrtva)

    procitano = ok.p.data_iz(ok.a.dohvati_zapis(obrazac_id, zapis_id).tijelo)
    z.provjeri("invarijante", "[%d] obrisan stupac nestaje i iz zapisa" % (i + 1),
               zrtva not in procitano,
               "zapis jos drzi %r = %r" % (zrtva, procitano.get(zrtva)))


def _inv_poredak(ok, z):
    """
    INVARIJANTA 6: poredak je totalan, obrnut u drugom smjeru, prazno na kraju.

    Dvije klasicne zamke: broj spremljen u JSON se sortira kao TEKST (pa 100 dode
    prije 9), a prazan tekst nije isto sto i nepostojeca vrijednost (pa `NULLS LAST`
    ne pokriva "" i prazni zapisi isplivaju na vrh).
    """
    p = ok.p
    if not p.zapisi.get("upit", {}).get("sortKljuc"):
        z.preskoceno("invarijante", "poredak", "postavke ne opisuju parametar za sortiranje")
        return

    obrazac = ok.a.napravi_obrazac(*ok.ime("poredak"))
    if obrazac.status >= 300:
        z.preskoceno("invarijante", "poredak", "obrazac: %s" % obrazac)
        return
    obrazac_id = obrazac.tijelo.get("id")
    ok.a.dodaj_stupac(obrazac_id, "broj", "broj")
    ok.a.dodaj_stupac(obrazac_id, "tekst", "tekst")

    brojevi = [3, 9, 20, 100]
    for broj in brojevi:
        ok.a.napravi_zapis(obrazac_id, {"broj": broj, "tekst": "b%d" % broj})
    for tekst in ["banana", "ananas", ""]:
        ok.a.napravi_zapis(obrazac_id, {"tekst": tekst})
    ok.a.napravi_zapis(obrazac_id, {"broj": 1})          # tekst uopce nije poslan

    uzlazno = ok.a.popis_zapisa(obrazac_id, sort_kljuc="broj", uzlazno=True, velicina=50)
    vrijednosti = [p.data_iz(zapis).get("broj") for zapis in sadrzaj_stranice(uzlazno)]
    samo_brojevi = [v for v in vrijednosti if isinstance(v, (int, float)) and not isinstance(v, bool)]
    z.provjeri("invarijante", "brojcani stupac se sortira kao broj, ne kao tekst",
               samo_brojevi == sorted(samo_brojevi),
               "dobiveni redoslijed: %s (tekstualni poredak stavlja 100 prije 9)" % samo_brojevi)

    silazno = ok.a.popis_zapisa(obrazac_id, sort_kljuc="broj", uzlazno=False, velicina=50)
    obrnute = [p.data_iz(zapis).get("broj") for zapis in sadrzaj_stranice(silazno)]
    obrnute_brojevi = [v for v in obrnute if isinstance(v, (int, float)) and not isinstance(v, bool)]
    z.provjeri("invarijante", "silazni poredak je tocno obrnuti uzlazni",
               obrnute_brojevi == list(reversed(samo_brojevi)),
               "uzlazno %s, silazno %s" % (samo_brojevi, obrnute_brojevi))

    for smjer, naziv in ((True, "uzlazno"), (False, "silazno")):
        odgovor = ok.a.popis_zapisa(obrazac_id, sort_kljuc="tekst", uzlazno=smjer, velicina=50)
        redom = [p.data_iz(zapis).get("tekst") for zapis in sadrzaj_stranice(odgovor)]
        prvo_prazno = next((i for i, v in enumerate(redom) if prazna(v)), len(redom))
        ima_punih_poslije = any(not prazna(v) for v in redom[prvo_prazno:])
        z.provjeri("invarijante", "prazne vrijednosti idu na kraj (%s)" % naziv,
                   not ima_punih_poslije,
                   "redoslijed: %s" % skrati(redom))


def _inv_stranicenje(ok, z):
    """
    INVARIJANTA 7: stranice se zbrajaju u cjelinu, bez rupa i bez duplikata.

    Ovo je provjera tvrdnje "pretraga i straniceje idu na posluzitelj": ako se
    filtrira u pregledniku, zbroj stranica se ne poklopi s ukupnim brojem.
    """
    p = ok.p
    obrazac = ok.a.napravi_obrazac(*ok.ime("stranice"))
    if obrazac.status >= 300:
        z.preskoceno("invarijante", "stranicenje", "obrazac: %s" % obrazac)
        return
    obrazac_id = obrazac.tijelo.get("id")
    ok.a.dodaj_stupac(obrazac_id, "redni", "broj")

    velicina = 4 if p.prima_velicinu else p.zadana_velicina
    koliko = velicina + 3                      # svjesno vise od jedne stranice
    ocekivani = set()
    for redni in range(koliko):
        stvoren = ok.a.napravi_zapis(obrazac_id, {"redni": redni})
        if stvoren.status < 300:
            ocekivani.add(stvoren.tijelo.get("id"))

    vidjeni, duplikati, stranica = [], [], 0
    while stranica < 20:
        odgovor = ok.a.popis_zapisa(obrazac_id, stranica=stranica,
                                    velicina=velicina if p.prima_velicinu else None)
        redci = sadrzaj_stranice(odgovor)
        if not redci:
            break
        for zapis in redci:
            ident = zapis.get("id")
            if ident in vidjeni:
                duplikati.append(ident)
            vidjeni.append(ident)
        stranica += 1

    z.provjeri("invarijante", "sve stranice zajedno daju sve zapise",
               set(vidjeni) == ocekivani,
               "napravljeno %d, prosetano %d, nedostaje %d" %
               (len(ocekivani), len(set(vidjeni)), len(ocekivani - set(vidjeni))))
    z.provjeri("invarijante", "nijedan zapis se ne pojavljuje na dvije stranice",
               not duplikati, "dvaput vidjeni: %s" % duplikati[:5])

    prva = ok.a.popis_zapisa(obrazac_id, stranica=0, velicina=velicina if p.prima_velicinu else None)
    prijavljeno = ukupno_zapisa(prva)
    if prijavljeno is not None:
        z.provjeri("invarijante", "prijavljeni ukupan broj odgovara stvarnom",
                   prijavljeno == len(ocekivani),
                   "prijavljeno %s, stvarno %d" % (prijavljeno, len(ocekivani)))


def _inv_pretraga(ok, z):
    """
    INVARIJANTA 8: pretraga se izvodi na POSLUZITELJU, nad svim zapisima.

    Preglednik koji filtrira dohvacenu stranicu izgleda tocno isto dok podataka
    ima malo: prvih pedeset zapisa sadrzi trazeno, filtar radi, svi su sretni.
    Lazi tek kad pogodak nije medju dohvacenima - a tada ne javi gresku nego
    "nema rezultata", sto korisnik procita kao istinu o svojim podacima.

    Zato provjera namjerno stavi iglu IZVAN prve stranice i prvo dokaze da je
    ondje (bez filtra je nema na prvoj stranici), pa tek onda trazi. Bez tog
    preduvjeta nalaz ne bi dokazivao nista.

    Cetiri tvrdnje:
      1. pogodak izvan prve stranice se nadje;
      2. u filtriranom popisu nema zapisa koji ne odgovaraju uvjetu;
      3. prijavljeni ukupan broj prati filtar (inace stranicenje racuna po
         nefiltriranom skupu i zadnje stranice ostanu prazne);
      4. kljuc po kojem se ne smije filtrirati vraca 400, ne tiho zanemarivanje
         - inace pogreska u imenu stupca vrati 200 i puni popis, sto izgleda
         kao ispravan odgovor na drugo pitanje.
    """
    p = ok.p
    if not p.zna_filtrirati:
        z.preskoceno("invarijante", "pretraga",
                     "postavke ne opisuju filtar (zapisi.upit.filter)")
        return

    obrazac = ok.a.napravi_obrazac(*ok.ime("pretraga"))
    if obrazac.status >= 300:
        z.preskoceno("invarijante", "pretraga", "obrazac: %s" % obrazac)
        return
    obrazac_id = obrazac.tijelo.get("id")
    ok.a.dodaj_stupac(obrazac_id, "tekst", "tekst")

    velicina = 4 if p.prima_velicinu else p.zadana_velicina
    velicina_upita = velicina if p.prima_velicinu else None
    igla = "igla-%s" % ok.oznaka
    koliko = velicina * 2 + 1                  # igla je zadnja, dakle dvije stranice dalje
    igla_id = None
    for redni in range(koliko):
        posljednji = redni == koliko - 1
        stvoren = ok.a.napravi_zapis(
            obrazac_id, {"tekst": igla if posljednji else "sijeno %03d" % redni})
        if posljednji and stvoren.status < 300:
            igla_id = stvoren.tijelo.get("id")
    if igla_id is None:
        z.preskoceno("invarijante", "pretraga", "zapis s iglom se nije dao napraviti")
        return

    bez_filtra = ok.a.popis_zapisa(obrazac_id, stranica=0, velicina=velicina_upita)
    na_prvoj = [zapis.get("id") for zapis in sadrzaj_stranice(bez_filtra)]
    if igla_id in na_prvoj:
        z.preskoceno("invarijante", "pretraga",
                     "igla je i bez filtra na prvoj stranici (%d redaka) - "
                     "aplikacija ocito ne stranici, pa nalaz ne bi dokazivao nista" % len(na_prvoj))
        return

    nadjeno = ok.a.popis_zapisa(obrazac_id, stranica=0, velicina=velicina_upita,
                                filter_kljuc="tekst", pretraga=igla)
    redci = sadrzaj_stranice(nadjeno)
    z.provjeri("invarijante", "pretraga nalazi pogodak koji nije na prvoj stranici",
               igla_id in [zapis.get("id") for zapis in redci],
               "trazeno %r, status %s, vraceno %d redaka: %s" %
               (igla, nadjeno.status, len(redci),
                skrati([p.data_iz(zapis).get("tekst") for zapis in redci])))

    vrijednosti = [p.data_iz(zapis).get("tekst") for zapis in redci]
    z.provjeri("invarijante", "filtrirani popis ne sadrzi zapise koji ne odgovaraju",
               bool(redci) and all(igla in (v or "") for v in vrijednosti),
               "vraceno: %s" % skrati(vrijednosti))

    prijavljeno = ukupno_zapisa(nadjeno)
    if prijavljeno is None:
        z.preskoceno("invarijante", "ukupan broj prati filtar",
                     "odgovor ne prijavljuje ukupan broj")
    else:
        z.provjeri("invarijante", "prijavljeni ukupan broj prati filtar",
                   prijavljeno == 1,
                   "prijavljeno %s, a uvjetu odgovara tocno 1 od %d zapisa" % (prijavljeno, koliko))

    nepoznat = ok.a.popis_zapisa(obrazac_id, stranica=0, velicina=velicina_upita,
                                 filter_kljuc="nema_me_u_shemi", pretraga=igla)
    z.provjeri("invarijante", "filtar po kljucu izvan sheme vraca 400, ne tiho zanemarivanje",
               nepoznat.status == 400,
               "status %s, vraceno %d redaka" % (nepoznat.status, len(sadrzaj_stranice(nepoznat))))


def _inv_odabir_grupe(ok, z):
    """
    INVARIJANTA 10: administratorov odabir grupe vrijedi i IZVAN popisa.

    Popisi grupu obicno nose kao parametar upita, a dohvat jednog retka, izmjena i
    brisanje ne nose nista - tada doseg odreduje token, koji administratoru dopusta
    sve. Posljedica je ekran koji pokazuje podatke jedne grupe dok traka tvrdi da je
    u drugoj, i koji se pritom da UREDIVATI.

    Provjera izolacije ovo ne moze uhvatiti: ona pusta dvije obicne grupe jednu na
    drugu, a ondje je sve ispravno 404. Kvar vidi samo administrator.
    """
    p = ok.p
    naziv = "odabir grupe vrijedi i izvan popisa"
    if not p.grupe.get("zaglavljeOdabira"):
        z.provjeri("invarijante", naziv, False,
                   "postavke ne opisuju kako administrator bira grupu (grupe.zaglavljeOdabira); "
                   "ako aplikacija taj mehanizam nema, to je nalaz, a ne nedostatak postavki")
        return

    obrazac = ok.b.napravi_obrazac(*ok.ime("odabir"))
    if obrazac.status >= 300:
        z.preskoceno("invarijante", naziv, "priprema obrasca grupe B: %s" % obrazac)
        return
    obrazac_id = obrazac.tijelo.get("id")
    ok.b.dodaj_stupac(obrazac_id, "tekst", "tekst")
    zapis = ok.b.napravi_zapis(obrazac_id, {"tekst": "tajna grupe b"})
    if zapis.status >= 300:
        z.preskoceno("invarijante", naziv, "priprema zapisa grupe B: %s" % zapis)
        return
    zapis_id = zapis.tijelo.get("id")

    u_a = ok.admin_u_grupi(ok.a.grupa_id)
    u_b = ok.admin_u_grupi(ok.b.grupa_id)

    # Preduvjet: s odabranom grupom B administrator njezin obrazac VIDI. Bez toga
    # bi svaki 404 nize prolazio i kad je aplikacija samo neupotrebljiva.
    vidi_svoje = u_b.zovi("GET", p.putanja_obrazac(obrazac_id))
    if vidi_svoje.status != 200:
        z.preskoceno("invarijante", naziv,
                     "administrator s odabranom grupom B ne vidi njezin obrazac (%s), "
                     "pa se odabir ne moze ni ocijeniti" % vidi_svoje)
        return

    pokusaji = [
        ("GET    obrazac", "GET", p.putanja_obrazac(obrazac_id), None),
        ("GET    zapis", "GET", p.putanja_zapisi(obrazac_id, zapis_id), None),
        ("PUT    zapis", "PUT", p.putanja_zapisi(obrazac_id, zapis_id),
         p.tijelo_zapisa({"tekst": "preoteto"})),
        ("DELETE zapis", "DELETE", p.putanja_zapisi(obrazac_id, zapis_id), None),
    ]
    for opis, metoda, putanja, tijelo in pokusaji:
        odgovor = u_a.zovi(metoda, putanja, tijelo)
        z.provjeri("invarijante", "odabrana grupa A, %s grupe B -> 404" % opis,
                   odgovor.status == 404,
                   "dobiveno %s (odabir grupe ne stize do te putanje)" % odgovor.status)

    popis = u_a.zovi("GET", p.putanja_obrazac())
    z.provjeri("invarijante", "odabrana grupa A, tudi obrazac nije u popisu",
               not [o for o in sadrzaj_stranice(popis) if o.get("id") == obrazac_id],
               "popis s odabranom grupom A sadrzi obrazac grupe B")

    netaknut = u_b.zovi("GET", p.putanja_zapisi(obrazac_id, zapis_id))
    z.provjeri("invarijante", "nakon tih pokusaja zapis grupe B je netaknut",
               netaknut.status == 200 and p.data_iz(netaknut.tijelo).get("tekst") == "tajna grupe b",
               "stanje nakon pokusaja: %s" % netaknut)


def _inv_brisanje_grupe(ok, z):
    """
    INVARIJANTA 9: grupa se da obrisati DOK JOS IMA SADRZAJ, i za njom ne ostaje nista.

    Dvije strane istog pravila. Aplikacija koja brisanje odbije porukom o
    ogranicenju baze ostavlja korisnika zaglavljenog s grupom koju ne moze ni
    koristiti ni maknuti; aplikacija koja obrise grupu a ostavi njezine obrasce
    ostavlja retke koji pokazuju na id kojeg nema.

    Mjeri se preko API-ja, jer pokretac nema pristup bazi: nakon brisanja se
    nijedan id te grupe ne smije dati dohvatiti, a njezin se korisnik ne smije
    prijaviti. Redak koji je ostao u bazi a nema putanju do sebe ovo NE vidi - za
    to sluzi test protiv prave baze, opisan u skillu.
    """
    p = ok.p
    naziv = "grupa se da obrisati dok jos ima obrazac i zapis"
    try:
        c = ok.dodatna_grupa("c")
    except SystemExit as greska:
        z.preskoceno("invarijante", naziv, "priprema grupe: %s" % greska)
        return

    obrazac = c.napravi_obrazac(*ok.ime("brisanje"))
    if obrazac.status >= 300:
        z.preskoceno("invarijante", naziv, "priprema obrasca: %s" % obrazac)
        return
    obrazac_id = obrazac.tijelo.get("id")
    c.dodaj_stupac(obrazac_id, "tekst", "tekst")
    zapis = c.napravi_zapis(obrazac_id, {"tekst": "ostatak"})
    zapis_id = zapis.tijelo.get("id") if zapis.status < 300 else None

    # Tko ce poslije brisanja gledati ima li ostataka: administrator s odabranom
    # grupom C, jer korisnik te grupe se nakon brisanja ne moze ni prijaviti.
    promatrac = ok.admin_u_grupi(c.grupa_id)
    if promatrac.zovi("GET", p.putanja_obrazac(obrazac_id)).status != 200:
        promatrac = ok.admin
    vidljivo_prije = promatrac.zovi("GET", p.putanja_obrazac(obrazac_id)).status == 200

    odbijeno = None
    for korak in ok._koraci_brisanja(c.grupa_id):
        odgovor = ok.admin.zovi(korak["metoda"], korak["putanja"])
        if odgovor.status >= 300:
            odbijeno = "%s %s -> %s %s" % (korak["metoda"], korak["putanja"],
                                           odgovor.status, odgovor.poruka[:100])
            break

    if not z.provjeri("invarijante", naziv, odbijeno is None,
                      "%s | grupa sa sadrzajem mora nestati, ne biti odbijena" % odbijeno):
        return  # ostalo nema smisla mjeriti; pospremanje ce grupu srediti svojim redom

    ok.zaboravi_grupu(c)

    if vidljivo_prije:
        ostaci = []
        if promatrac.zovi("GET", p.putanja_obrazac(obrazac_id)).status < 300:
            ostaci.append("obrazac %s je jos dohvatljiv" % obrazac_id)
        if zapis_id is not None and \
                promatrac.zovi("GET", p.putanja_zapisi(obrazac_id, zapis_id)).status < 300:
            ostaci.append("zapis %s je jos dohvatljiv" % zapis_id)
        grupa = ok.admin.zovi("GET", p.putanja_grupa(c.grupa_id))
        if grupa.status < 300:
            ostaci.append("sama grupa je jos dohvatljiva (%s)" % grupa.status)
        z.provjeri("invarijante", "za obrisanom grupom ne ostaje nijedan dohvatljiv redak",
                   not ostaci, " | ".join(ostaci))
    else:
        z.preskoceno("invarijante", "za obrisanom grupom ne ostaje nijedan dohvatljiv redak",
                     "obrazac te grupe se ni prije brisanja nije dao dohvatiti, "
                     "pa 404 poslije ne bi dokazivao nista")

    if c.prijava:
        token, odgovor = ok.prijavi(*c.prijava)
        z.provjeri("invarijante", "korisnik obrisane grupe se vise ne moze prijaviti",
                   not token, "prijava je i dalje uspjela (%s)" % odgovor.status)

    druga = ok.a.zovi("GET", p.putanja_obrazac())
    z.provjeri("invarijante", "brisanje grupe nije diralo drugu grupu",
               druga.status == 200, "popis grupe A nakon brisanja grupe C: %s" % druga)


# --------------------------------------------------------- 3. IZOLACIJA

def sekcija_izolacija(ok, z):
    """
    Grupa A ne smije doci do nicega sto pripada grupi B - i to na SVAKOJ putanji.

    Tude je 404, ne 403: 403 potvrduje da resurs postoji, a to je samo po sebi
    curenje podatka. Provjera ide redom po svim putanjama koje kostar ima, jer se
    izolacija najlakse probije bas na onoj koja je dodana zadnja.
    """
    p = ok.p
    z.naslov("3 - izolacija grupa")

    obrazac = ok.b.napravi_obrazac(*ok.ime("tudi"))
    if obrazac.status >= 300:
        z.preskoceno("izolacija", "priprema tudeg obrasca", "%s" % obrazac)
        return
    tudi_id = obrazac.tijelo.get("id")
    ok.b.dodaj_stupac(tudi_id, "tekst", "tekst")
    tudi_zapis = ok.b.napravi_zapis(tudi_id, {"tekst": "tajna grupe b"})
    tudi_zapis_id = tudi_zapis.tijelo.get("id") if tudi_zapis.status < 300 else 1

    pokusaji = [
        ("GET    obrazac",          "GET",    p.putanja_obrazac(tudi_id), None),
        ("PUT    obrazac",          "PUT",    p.putanja_obrazac(tudi_id),
         p.tijelo_obrasca("preoteto", "preoteto")),
        ("DELETE obrazac",          "DELETE", p.putanja_obrazac(tudi_id), None),
        ("GET    popis zapisa",     "GET",    p.putanja_zapisi(tudi_id), None),
        ("POST   novi zapis",       "POST",   p.putanja_zapisi(tudi_id),
         p.tijelo_zapisa({"tekst": "podmetnuto"})),
        ("GET    zapis",            "GET",    p.putanja_zapisi(tudi_id, tudi_zapis_id), None),
        ("PUT    zapis",            "PUT",    p.putanja_zapisi(tudi_id, tudi_zapis_id),
         p.tijelo_zapisa({"tekst": "preoteto"})),
        ("DELETE zapis",            "DELETE", p.putanja_zapisi(tudi_id, tudi_zapis_id), None),
        ("GET    shema",            "GET",    p.putanja_stupci(tudi_id), None),
        ("POST   novi stupac",      "POST",   p.putanja_stupci(tudi_id),
         p.tijelo_stupca("podmetnut", "tekst")),
        ("PUT    stupac",           "PUT",    p.putanja_stupci(tudi_id, "tekst"),
         p.tijelo_stupca("tekst", "tekst")),
        ("DELETE stupac",           "DELETE", p.putanja_stupci(tudi_id, "tekst"), None),
    ]

    for opis, metoda, putanja, tijelo in pokusaji:
        odgovor = ok.a.zovi(metoda, putanja, tijelo)
        if odgovor.status == 403:
            objasnjenje = " (403 odaje da resurs postoji)"
        elif odgovor.status >= 500:
            objasnjenje = " (poziv je pukao, pa se izolacija ne moze ni ocijeniti)"
        else:
            objasnjenje = ""
        z.provjeri("izolacija", "tudi resurs: %s -> 404" % opis,
                   odgovor.status == 404, "dobiveno %s%s" % (odgovor.status, objasnjenje))

    popis = ok.a.zovi("GET", p.putanja_obrazac())
    tudi_u_popisu = [o for o in sadrzaj_stranice(popis) if o.get("id") == tudi_id]
    z.provjeri("izolacija", "tudi obrazac se ne pojavljuje u popisu",
               not tudi_u_popisu, "popis grupe A sadrzi obrazac grupe B")

    jos_tu = ok.b.dohvati_zapis(tudi_id, tudi_zapis_id)
    z.provjeri("izolacija", "nakon svih pokusaja tudi zapis je netaknut",
               jos_tu.status == 200 and p.data_iz(jos_tu.tijelo).get("tekst") == "tajna grupe b",
               "stanje nakon pokusaja: %s" % jos_tu)


# ------------------------------------------------------------------ pokretanje

SEKCIJE = {"prihvat": sekcija_prihvat, "invarijante": sekcija_invarijante, "izolacija": sekcija_izolacija}


def provjeri_postavke(postavke):
    """Suha provjera postavki, bez ijednog poziva - da se tipfeler vidi odmah."""
    greske = []
    for kljuc, polje in (("obrasci", "putanja"), ("stupci", "putanja"), ("zapisi", "putanja"),
                         ("grupe", "putanja"), ("korisnici", "putanja")):
        if polje not in getattr(postavke, kljuc):
            greske.append("%s.%s nedostaje" % (kljuc, polje))
    for kljuc in ("stupci", "zapisi"):
        putanja = getattr(postavke, kljuc).get("putanja", "")
        if "{obrazac}" not in putanja:
            greske.append("%s.putanja mora sadrzavati {obrazac}: %r" % (kljuc, putanja))
    for logicki in ("tekst", "broj", "datum"):
        if logicki not in postavke.stupci.get("tipovi", {}):
            greske.append("stupci.tipovi nema prijevod za %r" % logicki)
    for polje in ("username", "password"):
        if polje not in postavke.admin:
            greske.append("admin.%s nedostaje" % polje)
    greske.extend(_greske_filtra(postavke))
    return greske


# Oblik u kojem aplikacija prima filtar -> polja koja taj oblik mora imati.
OBLICI_FILTRA = {
    "dva-parametra": ("parametarKljuc", "parametarVrijednost"),
    "kljuc-dvotocka-vrijednost": ("parametar",),
    "kljuc-operator-vrijednost": ("parametar",),
}


def _greske_filtra(postavke):
    """
    Postavka koja opisuje filtar mora biti potpuna, inace tiho ne radi nista.

    Ovo je zamka koju je pokus 02 nasao u samom pokretacu: nepotpun ili krivo
    imenovan `filter` ne rusi nista, provjera pretrage se samo preskoci, a
    izvjestaj i dalje izgleda zeleno.
    """
    f = postavke.zapisi.get("upit", {}).get("filter")
    if f is None:
        return []
    if not isinstance(f, dict):
        return ["zapisi.upit.filter mora biti objekt"]
    oblik = f.get("oblik")
    if oblik not in OBLICI_FILTRA:
        return ["zapisi.upit.filter.oblik je %r, a mora biti jedan od: %s"
                % (oblik, ", ".join(sorted(OBLICI_FILTRA)))]
    return ["zapisi.upit.filter.%s nedostaje (oblik %s)" % (polje, oblik)
            for polje in OBLICI_FILTRA[oblik] if polje not in f]


def main():
    razclanjivac = argparse.ArgumentParser(
        description="Prijenosna provjera aplikacija podignutih skillom app-skeleton.")
    razclanjivac.add_argument("postavke", help="JSON s postavkama projekta, npr. projekti/autoservis.json")
    razclanjivac.add_argument("--seed", type=int, default=None,
                              help="sjeme generatora; isti seed = isti pokus (zadano: nasumicno)")
    razclanjivac.add_argument("--primjeraka", type=int, default=3,
                              help="koliko nasumicnih shema provjeriti (zadano 3)")
    razclanjivac.add_argument("--samo", choices=sorted(SEKCIJE), action="append",
                              help="pokreni samo navedenu skupinu; smije se ponoviti")
    razclanjivac.add_argument("--zadrzi", action="store_true",
                              help="ne brisi podatke koje je pokus napravio (za rucni pregled)")
    razclanjivac.add_argument("--provjeri-postavke", action="store_true",
                              help="samo provjeri datoteku s postavkama, bez pozivanja aplikacije")
    razclanjivac.add_argument("--adresa", default=None,
                              help="nadjacaj baseUrl iz postavki (npr. http://localhost:8081)")
    razclanjivac.add_argument("--bez-boja", action="store_true", help="ispis bez ANSI boja")
    argumenti = razclanjivac.parse_args()

    postavke = ucitaj_postavke(argumenti.postavke)
    if argumenti.adresa:
        postavke.base_url = argumenti.adresa

    if argumenti.provjeri_postavke:
        greske = provjeri_postavke(postavke)
        for greska in greske:
            print("  GRESKA  %s" % greska)
        print("Postavke %s: %s" % (postavke.izvor, "u redu" if not greske else "%d greska" % len(greske)))
        return 0 if not greske else 2

    seed = argumenti.seed if argumenti.seed is not None else random.randrange(1, 10 ** 6)
    generator = Generator(seed)
    zapisnik = Zapisnik(boje=not argumenti.bez_boja)

    print("=" * 70)
    print("PRIHVAT %s   projekt: %s" % (VERZIJA, postavke.naziv))
    print("aplikacija: %s" % postavke.base_url)
    print("seed: %d   (ponovi isti pokus s --seed %d)" % (seed, seed))
    print("=" * 70)

    okolina = Okolina(postavke, generator, zapisnik).pripremi()
    trazene = argumenti.samo or sorted(SEKCIJE)
    try:
        for ime in ("prihvat", "invarijante", "izolacija"):
            if ime not in trazene or ime in postavke.preskoci:
                continue
            if ime == "invarijante":
                SEKCIJE[ime](okolina, zapisnik, argumenti.primjeraka)
            else:
                SEKCIJE[ime](okolina, zapisnik)
    finally:
        if argumenti.zadrzi:
            print("\n(podaci pokusa ostavljeni na zahtjev: --zadrzi)")
        else:
            neuspjeli = okolina.pospremi()
            if neuspjeli:
                print("\nNije se dalo pospremiti: %s" % ", ".join(neuspjeli))

    zapisnik.naslov("4 - opca provjera")
    zapisnik.provjeri("opce", "nijedan od %d poziva nije zavrsio kao 5xx" % okolina.k.broj_poziva,
                      not okolina.k.petsto,
                      "prvih nekoliko: %s" % " | ".join(okolina.k.petsto[:4]))

    palo = zapisnik.izvjestaj()
    print("\nHTTP poziva: %d   seed: %d" % (okolina.k.broj_poziva, seed))
    return 1 if palo else 0


if __name__ == "__main__":
    sys.exit(main())

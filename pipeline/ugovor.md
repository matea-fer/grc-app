# Ugovor: što svaka aplikacija podignuta skillom `app-skeleton` mora zadovoljiti

Ovo je temeljni dokument cijelog pipelinea. Sve ostalo — skripta, CI, izvještaj —
samo je način da se provjeri piše li ovdje istina.

Dvije stvari o kojima ovisi je li dokument uopće koristan:

1. **Tvrdnje su o HTTP-u, ne o kodu.** Nigdje se ne spominje razred, paket, tablica
   ni build alat. Zato ista provjera vrijedi za Gradle i Maven projekt, za
   `Company` i `Tenant`, za `surveys` i `records`.
2. **Tvrdnje su nastale iz skilla, prije koda.** To je jedina obrana od kruga u
   kojem agent napiše aplikaciju, pa sam sebi napiše testove koji potvrde ono što
   je napravio. Test izveden iz koda ne može uhvatiti krivo shvaćeno pravilo.

Oznake u zagradama (`P1`, `I3`…) su iste kao u ispisu skripte `prihvat.py`.

---

## A. Prihvatni kriteriji — fiksne tvrdnje

Uvijek isti poziv, uvijek isti očekivani odgovor. Svaka tvrdnja pripada jednoj
fazi skilla, pa se pad čita kao „faza N nije zadovoljena".

| # | Tvrdnja | Faza |
|---|---|---|
| P1 | Poziv na zaštićenu putanju bez tokena vraća **401**. | 2 |
| P2 | Greška je u obliku `{"message": "..."}` i poruka nije prazna. | 1 |
| P3 | Neuspjela prijava (kriva lozinka) vraća **401**, ne 403. | 2 |
| P4 | Nepoznat korisnik vraća **401**, istom porukom kao kriva lozinka. | 2 |
| P5 | Poruka o neuspjeloj prijavi **ne odaje** je li korisničko ime postojalo. | 2 |
| P6 | Prijavljen korisnik dohvaća svoj profil s **200**. | 2 |
| P7 | Upis zapisa s ključem kojeg nema u shemi vraća **400**. | 4 |
| P8 | Ta poruka **imenuje baš taj ključ** (ne generično „neispravan zahtjev"). | 4 |
| P9 | Vrijednost krive vrste (tekst u brojčani stupac) vraća **400**. | 4 |
| P10 | Izmjena nepostojećeg zapisa vraća **404**, i onda kad je tijelo neispravno — **404 ima prednost pred 400**. | sve |
| P11 | Sortiranje po ključu izvan sheme vraća **400**, ne tiho zanemarivanje. | 5 |
| P12 | Korisnik bez prava na radnju dobiva **403** (ne 404, ne 200). | 2 |

Razlika 401 / 403 / 404 nije formalnost:

- **401** = ne znam tko si (nema tokena, ili prijava nije uspjela),
- **403** = znam tko si, ali ovo ne smiješ,
- **404** = ovoga za tebe nema (i kad postoji — jer bi 403 potvrdio da postoji).

---

## B. Invarijante — tvrdnje o BILO KOJIM podacima

Invarijanta je tvrdnja koja mora vrijediti uvijek, bez obzira na to kakvu je shemu
korisnik složio i kakve je vrijednosti upisao. Skripta ih ne provjerava na
odabranom primjeru nego na shemama i vrijednostima koje izmišlja generator.

### I1 · Spremi = pročitaj isto

Za bilo koju shemu i bilo koji valjan zapis: ono što je spremljeno mora se
pročitati nepromijenjeno.

Broj se smije vratiti kao `42` ili `42.0` — to je isti broj, drugi zapis. Sve
ostalo mora doći doslovno. Ako se prazan tekst `""` vrati kao `null`, to je
promjena vrijednosti i tvrdnja pada; ako je ta pretvorba namjerna, mora pisati u
ugovoru, jer inače dvije aplikacije rade dvije različite stvari.

Lovi: serijalizaciju JSON-a, format datuma, dijakritiku, tihe pretvorbe.

### I2 · Prazno ostaje prazno

Polje koje korisnik nije popunio ne smije se vratiti popunjeno. Konkretno: ne
smije dobiti `0`, `false` ni `""` samo zato što je stupac tog tipa.

Lovi: bug u kojem polje prikazano kao „-" tiho dobije vrijednost čim korisnik
klikne Spremi, iako ga nikad nije dirao.

### I3 · Nedeklariran ključ → 400, i poruka ga imenuje

Za bilo koju shemu i bilo koji ključ izvan nje.

Lovi: najčešći propust — validaciju koja petlja po shemi umjesto po dobivenim
ključevima, pa nepoznat ključ prođe i podatak uvede stupac koji nitko nije
deklarirao.

### I4 · Validacija samo na upisu

Zapis nastao prije izmjene sheme mora se i dalje moći pročitati (200).

Lovi: validaciju na čitanju, koja unatrag ruši sve što više ne odgovara novoj
shemi.

### I5 · Brisanje stupca miče deklaraciju I vrijednosti

Nakon brisanja stupca: ključa nema u shemi, i nema ga ni u jednom zapisu — ni kao
`null`. Ključ koji ostane visjeti u zapisu je vrijednost koju nijedna deklaracija
ne pokriva, i vraća točno onaj drugi izvor sheme kojeg se cijeli model rješava.

### I6 · Poredak je totalan, obrnut u drugom smjeru, prazno na kraju

Tri odvojene tvrdnje:

- brojčani stupac se sortira **kao broj**, ne kao tekst (inače `100` dolazi prije `9`),
- silazni poredak je **točno obrnut** uzlazni,
- prazne vrijednosti su **na kraju u oba smjera** (prazan tekst i nepostojeća
  vrijednost jednako; `NULLS LAST` sam ne pokriva `""`).

### I7 · Stranice se zbrajaju u cjelinu

Sve stranice zajedno daju točno sve zapise: bez rupa, bez zapisa koji se pojavi
dvaput, i s prijavljenim ukupnim brojem koji odgovara stvarnom.

Lovi: filtriranje u pregledniku umjesto na poslužitelju — tada se zbroj stranica
ne poklopi.

### I8 · Pretraga se izvodi na poslužitelju, nad svim zapisima

Prihvatni kriterij faze 5 doslovno: *pretraga vraća pogodak koji nije na prvoj
stranici*. Zato provjera namjerno stavi iglu izvan prve stranice i **prvo dokaže
da je ondje** (bez filtra je nema među dohvaćenima), pa tek onda traži — bez tog
preduvjeta nalaz ne bi dokazivao ništa.

Četiri tvrdnje:

- pogodak **izvan prve stranice** se nađe,
- u filtriranom popisu **nema zapisa koji ne odgovaraju** uvjetu,
- **prijavljeni ukupan broj prati filtar** (inače straničenje računa po
  nefiltriranom skupu, pa zadnje stranice ostanu prazne),
- **ključ po kojem se ne smije filtrirati vraća 400**, ne tiho zanemarivanje —
  isto pravilo kao P11 za sortiranje.

Lovi filtriranje u pregledniku, koje se ne vidi dok podataka ima malo: prvih
pedeset zapisa sadrži traženo, filtar radi, svi su sretni. Laže tek kad pogodak
nije među dohvaćenima — a tada ne javi grešku nego „nema rezultata", što korisnik
pročita kao istinu o svojim podacima.

### I9 · Grupa se da obrisati, i za njom ne ostaje ništa

Provjera napravi **treću grupu**, dade joj obrazac sa stupcem i zapisom, i tek
onda je pokuša obrisati — namjerno punu, jer prazna grupa ne dokazuje ništa.

Četiri tvrdnje:

- brisanje **prolazi** dok grupa još ima sadržaj (odbijanje uz poruku o
  ograničenju baze ostavlja korisnika zaglavljenog s grupom koju ne može ni
  koristiti ni maknuti),
- nakon brisanja se **nijedan njezin id više ne da dohvatiti** — ni grupa, ni
  obrazac, ni zapis,
- njezin se **korisnik više ne može prijaviti**,
- **druga grupa je netaknuta**.

Brisanje smije imati više koraka: postavka `grupe.brisanje` prima i niz, pa
aplikacija koja grupu prvo arhivira pa onda prazni prolazi jednako. Smije tražiti
i **potvrdu koja se ne otipka slučajno** — prepisan ključ grupe; u putanji koraka
se zato zamjenjuju i `{id}` i `{kljuc}`. Tvrdnja je o ishodu, ne o broju koraka:
grupa mora nestati, a koliko je zaštita pred tim, stvar je aplikacije.

Granica koju treba znati: ovo mjeri **kroz API**, pa vidi samo ono što ima
putanju do sebe. Redak koji je ostao u bazi bez ijedne putanje ovdje se ne
vidi — za njega služi test protiv prave baze, opisan u skillu (`deleteAll`
nad učitanim entitetima zna tiho preskočiti retke).

### I10 · Odabir grupe vrijedi i izvan popisa

Administrator nema grupu u tokenu, nego je bira, pa se njegov doseg ne vidi iz
tokena nego iz onoga što šalje uz poziv. Provjera mu odabere grupu A i onda
traži tuđe: `GET` obrasca, `GET`, `PUT` i `DELETE` zapisa grupe B — sve četiri
moraju biti 404, tuđi obrazac ne smije biti u popisu, a zapis grupe B mora
poslije ostati netaknut.

Prije toga se traži i **preduvjet**: s odabranom grupom B administrator taj
obrazac mora vidjeti. Bez njega bi svaki 404 prolazio i kad aplikacija
jednostavno ne radi.

Lovi kvar koji sekcija izolacije ne može uhvatiti, jer ona pušta dvije obične
grupe jednu na drugu — a ondje je sve ispravno 404. Kvar vidi samo
administrator: popisi grupu nose kao parametar upita, a dohvat jednog retka,
izmjena i brisanje ne nose ništa, pa ekran pokazuje podatke jedne grupe dok
traka tvrdi da je u drugoj — i daju se uređivati.

Ako postavke ne opisuju kako se grupa bira (`grupe.zaglavljeOdabira`),
invarijanta **pada**, ne preskače se: aplikacija koja taj mehanizam nema ima
nalaz, a ne nepotpune postavke.

---

## C. Izolacija — tuđe je 404, na svakoj putanji

Grupa A ne smije doći ni do čega što pripada grupi B, i to je jedino pravilo gdje
propust nije bug nego curenje tuđih podataka.

Provjerava se **svaka** kombinacija putanje i metode, ne uzorak, jer se izolacija
najlakše probije baš na onoj putanji koja je dodana zadnja:

| Resurs | Metode |
|---|---|
| obrazac | GET, PUT, DELETE |
| popis zapisa | GET, POST |
| pojedini zapis | GET, PUT, DELETE |
| shema (stupci) | GET, POST, PUT, DELETE |

Uz to:

- tuđi obrazac se **ne pojavljuje u popisu** vlastitih,
- nakon svih pokušaja tuđi zapis je **netaknut** (nijedan pokušaj nije djelomično
  prošao).

---

## Što ovaj ugovor NE pokriva

Da se ne bi čitao kao jamstvo koje nije:

- **Sučelje.** Aplikacija može proći svaku provjeru iz ovog ugovora i biti
  neupotrebljiva na prvom ekranu (u pokusu 01 tri su nalaza došla baš odatle, u
  pokusu 02 četiri). Za to služi E2E smoke, odnosno prolazak klikom.
- **Domenska pravila.** Ugovor je o kosturu; „rizik mora imati vlasnika" je
  pravilo domene i ne piše ovdje.
- **Brzina i opterećenje.** Nijedna tvrdnja nije o vremenu odgovora.
- **Prilozi, veze, formule, zaključavanje.** Proširenja iz faze 8 imaju vlastita
  pravila; kad ih aplikacija ima, ugovor se dopunjuje.

## Kako se ugovor mijenja

Kad pokus nađe nešto što ugovor nije predvidio, dopisuje se nova tvrdnja — i to
**prije** nego se popravi aplikacija. Obrnuti redoslijed (prvo popravak, pa
tvrdnja) daje tvrdnju napisanu prema popravku, a to je opet test izveden iz koda.

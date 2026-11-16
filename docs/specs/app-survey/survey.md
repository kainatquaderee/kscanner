# Survey — what the competing scanner is, and where this app stands

Records the evidence behind the app-wide comparison. Two feature specifications already cite it in
detail: `docs/specs/document-framing/design.md` §1 (live tracking) and
`docs/specs/book-scanning/design.md` §1 (books). This document covers the rest of the surface.

## 0. Method

The competing app is CamScanner **7.28.5**, decompiled with jadx to `/tmp/cs/out1` — 49,993 `.java`
files from the standalone APK `/tmp/cs/com.intsig.camscanner.apk` (165,754,906 bytes). Every count
and line below was read in this environment. Where a claim could not be checked it is marked as
unchecked rather than asserted.

One thing to say plainly at the start: **size is not the measure**. Their APK is five times ours
because it contains an office suite (`office_doc`, 916 files), a purchase flow (`purchase`, 201), a
signing product (`newsign`, 305), an AI assistant (`csai`, 341) and a Flutter runtime (`flutter`,
566). None of that is scanning. The scanning is concentrated in `capture` (888), `pagelist` (514),
`pdf` (142) and two OCR packages (`mode_ocr` 143, `ocrapi` 67), over a native engine of **243
native methods in 11 classes** (`nativelib/PageScanner.java` 102, `OctopusClassifier` 44,
`MagicEnhancer` 36, `ImageProcessor` 24, `DraftEngine` 11, and six more of 4–7).

This app is 59 Kotlin files / 12,405 lines of shipping code and 24 test files / 5,091 lines of
tests.

## 1. Where the two apps agree

These were checked, not assumed, because several turned out to be present already:

* **Search across recognised text.** Their `search/` is 232 files and `DocSearchRepository` matches
  on doc title, tag or page content (`search/repository/DocSearchRepository.java:245`,
  `str2 = ... ? "tag" : "content"`). Ours already does this: `DocumentStore.listDocuments`
  (`data/DocumentStore.kt:63-71`) matches `d.name LIKE ?` **or** `EXISTS (SELECT 1 FROM pages p
  WHERE p.document_id = d.id AND p.ocr_text LIKE ?)`, and `LibraryScreen.kt:215` is the box that
  drives it. Present.
* **Page order adjustment.** Their `PdfKitMainItemType.MOVE` ("adjust_page",
  `pdf/kit/PdfKitMainItemType.java:19`). Ours: drag-reorder in `DocumentScreen.kt:338` over
  `DocumentStore.reorderPages` (`:315`). Present.
* **Deskew, autofilter, shadow removal, perspective correction, per-page rotation and upright
  detection.** Present on both sides; ours is documented in the README's *Edge detection* and
  *Enhancement* sections and is framework-free and tested.
* **Searchable PDF text layer.** Theirs positions text from OCR boxes; ours does the same with a
  real invisible layer, `3 Tr` (`core/pdf/PdfWriter.kt:113-116`).

## 2. The PDF tool surface — the clearest gap

Their PDF kit is a stated list of sixteen operations, and it is enumerated in one place:
`pdf/kit/PdfKitMainItemType.java`. That enum is the most useful single artefact in the whole
decompiled tree, because it is their product decision written as code:

| Their item | Line | Ours |
|---|---|---|
| PDF_TO_WORD | `:8` | absent — server-backed (`pdf/external/ExportPdfToWordActivity.java`, `csai/util/convert/ToWordDocumentConverter.java`); out of scope without a network service |
| PDF_TO_EXCEL / PDF_TO_PPT | `:9-10` | absent, same reason |
| SIGNATURE | `:11` | absent (`pdf/signature/PdfSignatureSplice.java`) |
| WATER (watermark) | `:12` | absent |
| IMAGE / LONG_PICTURE | `:13-14` | images: present (`shareImages`). Long picture (pages stitched into one tall image): absent |
| COMPRESSION / FILE_COMPRESSION | `:15-16` | absent as an export operation. Page JPEGs are written once at capture quality (`AppSettings.jpegQuality = 88`, `data/SettingsStore.kt:20`); there is no per-export recompress and no size preview |
| MERGE | `:17` | absent — two documents cannot be combined; `grep -rni 'merge' app/src/main` matches only `Hough.kt`'s line merging |
| EXTRACT | `:18` | absent — a page range cannot be exported on its own |
| MOVE | `:19` | present (`DocumentScreen.kt:338`) |
| FILL_FROM / NO_CS_WATERMARK | `:20-21` | vendor-specific, not applicable |
| ADD_PAGE_NUMBER | `:22` | absent |
| PASSWORD | `:23` | absent — and we also cannot open an encrypted PDF, since importing a PDF is not supported at all |

Two of these are unusually cheap for us, and that is the finding that matters:

* **ADD_PAGE_NUMBER and WATER** are *visible text on the page*. Our writer already places text
  runs at the right user-space coordinates and already carries a base-14 font — `/F1`,
  `/Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding`
  (`core/pdf/PdfWriter.kt:137`). A visible stamp is the same code path with `0 Tr` instead of
  `3 Tr` plus a fill colour, so it is testable in a plain JVM test by reading the content stream
  back out of the produced bytes. No new dependency, no device.
* **MERGE and EXTRACT** are row operations on `pages` (`DbHelper.kt:31-43`) plus a renumber, in
  the shape `reorderPages` already has. Not JVM-testable (SQLite), so device verification.

By contrast **PASSWORD** is not cheap done properly: the PDF standard security handler needs MD5
and RC4/AES, and a subtly wrong implementation yields a file that no viewer opens. It is real work
with a real failure mode, so it is listed rather than recommended.

## 3. OCR

Their local engine is genuinely native — `OCREngine.LoadModel(String, int)`,
`RecognizePage(long, Polygon[], int[], int)`, `ReleaseModel()` (`nativelib/OCREngine.java:79-87`),
driven per-language (`ocrapi/LocalOcrClient.java:342` passes `OcrLanguage.transformLanguage(j)`,
`nativelib/OcrLanguage.java:613`) and with a user-facing chooser
(`mode_ocr/LanguageChoose.java`). There is also a cloud path
(`mode_ocr/OCRBalanceManager.java`, `CloudOCRResponse.java`) — their OCR is metered.

Ours is ML Kit's Latin recogniser (`core/ocr/OcrEngine.kt:19`,
`TextRecognizerOptions.DEFAULT_OPTIONS`), with the text and line geometry stored per page
(`ocr_text`, `ocr_lines`) and used for the PDF layer. Multilingual support would mean a new ML Kit
model artifact, which the offline Gradle cache cannot supply, so it is out of scope here rather
than declined.

Their engine is also a *classifier* for page geometry — `OctopusClassifier` (44 natives) — which is
what their curved-border arbitration and turn-page detection rest on (§1 of the two feature specs).

## 4. What this leaves

Ranked by value per unit of risk, given the constraint that the imaging core stays framework-free
and JVM-tested:

1. **Visible PDF stamps: page numbers, then an optional text watermark.** Small, self-contained in
   `core/pdf`, fully JVM-testable, and it is the same machinery a signature image would later need.
2. **Merge / extract documents.** Absent, clearly useful, row-level work on the existing schema;
   needs device verification only.
3. **Export compression with a size preview.** Small in code, but its value depends on a preview UI
   and it touches the capture quality contract, which is a product decision.
4. **PDF password.** Deferred with the reasoning in §2.

Items 1 and 2 are the two worth building next; 3 and 4 are recorded so the choice is not re-derived
later.
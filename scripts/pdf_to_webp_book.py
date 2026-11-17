#!/usr/bin/env python3
"""
Converts a local PDF into a BS Reader-compatible .zip archive of high-resolution
WebP pages (cover.webp + 300-DPI page_XXX.webp), extracts and translates text
lines into Bangla (translations_bn.json) using the repository's custom Gemini
prompts, and updates catalog.json metadata.
"""

import argparse
import json
import os
import re
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from PIL import Image
from pdf2image import convert_from_path, pdfinfo_from_path

try:
    import fitz  # PyMuPDF
except ImportError:
    fitz = None

LANCZOS = getattr(Image, "Resampling", Image).LANCZOS
DEFAULT_PROMPT_PATH = Path(__file__).with_name("translation_prompt.txt")
DEFAULT_CORRECTION_PROMPT_PATH = Path(__file__).with_name("translation_correction_prompt.txt")


def slugify(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug or "manual"


def to_rgb_white_bg(img: Image.Image) -> Image.Image:
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        rgba = img.convert("RGBA")
        bg = Image.new("RGB", rgba.size, (255, 255, 255))
        bg.paste(rgba, mask=rgba.split()[3])
        return bg
    if img.mode != "RGB":
        return img.convert("RGB")
    return img


def resize_to_width(img: Image.Image, target_width: int) -> Image.Image:
    img = to_rgb_white_bg(img)
    w, h = img.size
    if w == target_width:
        return img
    target_height = max(1, round(h * (target_width / float(w))))
    return img.resize((target_width, target_height), LANCZOS)


def fit_within_bounds(img: Image.Image, max_width: int, max_height: int) -> Image.Image:
    """Keeps native 300-DPI Poppler output untouched unless it exceeds Android texture bounds."""
    img = to_rgb_white_bg(img)
    w, h = img.size
    if w <= max_width and h <= max_height:
        return img
    scale = min(max_width / float(w), max_height / float(h))
    target_w = max(1, round(w * scale))
    target_h = max(1, round(h * scale))
    return img.resize((target_w, target_h), LANCZOS)


def extract_page_lines(pdf_path: Path, page_numbers_1based: list[int]) -> dict[int, list[str]]:
    """Extracts ordered, cleaned text lines per 1-based PDF page number using PyMuPDF."""
    if fitz is None:
        print("Warning: PyMuPDF (fitz) is not installed; skipping text extraction.")
        return {}

    doc = fitz.open(str(pdf_path))
    pages_map: dict[int, list[str]] = {}
    try:
        for pdf_page_num in page_numbers_1based:
            page = doc[pdf_page_num - 1]
            text_dict = page.get_text("dict", sort=True)
            lines_out: list[str] = []
            for block in text_dict.get("blocks", []):
                if block.get("type", 0) != 0:
                    continue
                for line in block.get("lines", []):
                    spans_text = "".join(span.get("text", "") for span in line.get("spans", []))
                    cleaned = re.sub(r"\s+", " ", spans_text).strip()
                    if cleaned:
                        lines_out.append(cleaned)
            pages_map[pdf_page_num] = lines_out
    finally:
        doc.close()
    return pages_map


def is_translatable_line(text: str) -> bool:
    """Skips pure punctuation/numbers/codes that stay identical in Bangla."""
    return bool(re.search(r"[A-Za-z]{2,}", text))


def call_gemini_json(
    api_key: str,
    model: str,
    system_instruction: str,
    user_payload: str,
    max_retries: int = 5,
    base_delay_sec: float = 2.0,
) -> dict:
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{model}:generateContent?key={api_key}"
    )
    body = {
        "systemInstruction": {"parts": [{"text": system_instruction}]},
        "contents": [{"role": "user", "parts": [{"text": user_payload}]}],
        "generationConfig": {
            "temperature": 0.2,
            "responseMimeType": "application/json",
            "responseSchema": {
                "type": "OBJECT",
                "properties": {
                    "items": {
                        "type": "ARRAY",
                        "items": {
                            "type": "OBJECT",
                            "properties": {
                                "index": {"type": "INTEGER"},
                                "bn": {"type": "STRING"},
                            },
                            "required": ["index", "bn"],
                        },
                    }
                },
                "required": ["items"],
            },
        },
    }
    data = json.dumps(body).encode("utf-8")

    for attempt in range(max_retries + 1):
        req = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=90) as resp:
                raw = resp.read().decode("utf-8")
                parsed = json.loads(raw)
                candidates = parsed.get("candidates") or []
                if not candidates:
                    raise RuntimeError(f"Empty Gemini candidates: {raw[:300]}")
                text_out = candidates[0]["content"]["parts"][0]["text"]
                return json.loads(text_out)
        except urllib.error.HTTPError as err:
            err_body = err.read().decode("utf-8", errors="ignore")
            if err.code in (429, 500, 502, 503, 504) and attempt < max_retries:
                wait_sec = base_delay_sec * (2 ** attempt)
                print(f"Gemini HTTP {err.code}; retrying in {wait_sec:.1f}s...")
                time.sleep(wait_sec)
                continue
            raise RuntimeError(f"Gemini API error HTTP {err.code}: {err_body[:400]}") from err
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError) as err:
            if attempt < max_retries:
                wait_sec = base_delay_sec * (2 ** attempt)
                print(f"Gemini transient error ({err}); retrying in {wait_sec:.1f}s...")
                time.sleep(wait_sec)
                continue
            raise


def translate_lines_with_gemini(
    api_key: str,
    model: str,
    translation_prompt: str,
    correction_prompt: str | None,
    lines: list[str],
    api_delay_sec: float,
) -> list[str]:
    indexed_items = [{"index": i, "en": line} for i, line in enumerate(lines)]

    pass1_system = (
        f"{translation_prompt.strip()}\n\n"
        "JSON OUTPUT FORMAT REQUIREMENT:\n"
        "You will receive a JSON array of objects `{index, en}` representing lines from one page "
        "in top-to-bottom reading order. Preserve the exact same count and `index` values. "
        "Return a valid JSON object strictly matching:\n"
        '{"items": [{"index": 0, "bn": "..."}]}\n'
        "Every item's `bn` field must contain ONLY the natural Bangla translation for that line."
    )
    pass1_user = json.dumps({"items": indexed_items}, ensure_ascii=False)
    pass1_res = call_gemini_json(api_key, model, pass1_system, pass1_user)
    pass1_items = pass1_res if isinstance(pass1_res, list) else pass1_res.get("items", [])
    bn_by_index = {
        int(item["index"]): str(item.get("bn", "")).strip()
        for item in pass1_items
        if isinstance(item, dict) and "index" in item
    }
    first_pass_lines = [bn_by_index.get(i, lines[i]) for i in range(len(lines))]

    if api_delay_sec > 0:
        time.sleep(api_delay_sec)

    if not correction_prompt:
        return first_pass_lines

    pass2_system = (
        f"{correction_prompt.strip()}\n\n"
        "JSON OUTPUT FORMAT REQUIREMENT:\n"
        "You will receive a JSON array of objects `{index, en, bn}` where `en` is the original "
        "English line and `bn` is the draft Bangla translation. Preserve the exact count and "
        "`index` values. Return a valid JSON object strictly matching:\n"
        '{"items": [{"index": 0, "bn": "..."}]}'
    )
    pass2_items = [
        {"index": i, "en": lines[i], "bn": first_pass_lines[i]}
        for i in range(len(lines))
    ]
    try:
        pass2_res = call_gemini_json(
            api_key,
            model,
            pass2_system,
            json.dumps({"items": pass2_items}, ensure_ascii=False),
        )
        pass2_list = pass2_res if isinstance(pass2_res, list) else pass2_res.get("items", [])
        corrected_by_index = {
            int(item["index"]): str(item.get("bn", "")).strip()
            for item in pass2_list
            if isinstance(item, dict) and "index" in item
        }
        if api_delay_sec > 0:
            time.sleep(api_delay_sec)
        return [corrected_by_index.get(i, first_pass_lines[i]) for i in range(len(lines))]
    except Exception as exc:
        print(f"Warning: second-pass correction failed ({exc}); keeping Pass 1 translation.", flush=True)
        return first_pass_lines


def build_bangla_translations_bundle(
    pdf_path: Path,
    reader_to_pdf_pages: list[tuple[int, int]],
    api_key: str,
    model: str,
    prompt_path: Path,
    correction_prompt_path: Path | None,
    enable_second_pass: bool = True,
    api_delay_sec: float = 2.0,
) -> dict | None:
    if not api_key.strip():
        print("GEMINI_API_KEY not set — skipping Bangla translation generation.", flush=True)
        return None
    if fitz is None:
        print("PyMuPDF not available — skipping Bangla translation generation.", flush=True)
        return None
    if not prompt_path.exists():
        print(f"Translation prompt not found at {prompt_path} — skipping.", flush=True)
        return None

    translation_prompt = prompt_path.read_text(encoding="utf-8")
    correction_prompt = None
    if enable_second_pass and correction_prompt_path and correction_prompt_path.exists():
        correction_prompt = correction_prompt_path.read_text(encoding="utf-8")

    pdf_pages = [pdf_p for _, pdf_p in reader_to_pdf_pages]
    extracted_by_pdf_page = extract_page_lines(pdf_path, pdf_pages)

    line_cache: dict[str, str] = {}
    pages_payload: list[dict] = []
    total_pages = len(reader_to_pdf_pages)
    translated_pages_count = 0
    total_translated_lines = 0

    for reader_page_num, pdf_page_num in reader_to_pdf_pages:
        pct = round((reader_page_num / float(max(1, total_pages))) * 100)
        raw_lines = extracted_by_pdf_page.get(pdf_page_num, [])
        if not raw_lines:
            print(f"[Bangla {reader_page_num}/{total_pages} | {pct}%] No text lines (image/blank page).", flush=True)
            pages_payload.append({"page": reader_page_num, "lines": []})
            continue

        # Identify lines not yet cached that actually contain English words
        uncached_lines: list[str] = []
        seen_in_batch: set[str] = set()
        for line in raw_lines:
            if is_translatable_line(line) and line not in line_cache and line not in seen_in_batch:
                uncached_lines.append(line)
                seen_in_batch.add(line)

        if uncached_lines:
            pass_label = "2-pass" if correction_prompt else "1-pass"
            print(
                f"[Bangla {reader_page_num}/{total_pages} | {pct}%] "
                f"Translating {len(uncached_lines)} line(s) ({pass_label}, {model})...",
                flush=True,
            )
            try:
                translated_batch = translate_lines_with_gemini(
                    api_key=api_key,
                    model=model,
                    translation_prompt=translation_prompt,
                    correction_prompt=correction_prompt,
                    lines=uncached_lines,
                    api_delay_sec=api_delay_sec,
                )
                for src, dst in zip(uncached_lines, translated_batch):
                    line_cache[src] = dst if dst else src
                print(
                    f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Completed.",
                    flush=True,
                )
            except Exception as exc:
                print(
                    f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Warning: translation failed ({exc})",
                    flush=True,
                )
        else:
            print(
                f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Served from line cache.",
                flush=True,
            )

        page_lines_out = []
        for line in raw_lines:
            if not is_translatable_line(line):
                bn_text = line
            else:
                bn_text = line_cache.get(line, "")
            if bn_text:
                page_lines_out.append({"en": line, "bn": bn_text})

        if page_lines_out:
            translated_pages_count += 1
            total_translated_lines += len(page_lines_out)

        pages_payload.append({"page": reader_page_num, "lines": page_lines_out})

    print(
        f"Bangla translation summary for '{pdf_path.name}': "
        f"{translated_pages_count}/{total_pages} pages, {total_translated_lines} lines.",
        flush=True,
    )

    return {
        "version": 1,
        "language": "bn",
        "translator": model,
        "pages": pages_payload,
    }


def render_pdf_to_webp_zip(
    pdf_path: Path,
    output_dir: Path,
    book_id: str,
    title: str,
    author: str,
    description: str,
    category: str,
    base_url: str,
    dpi: int = 300,
    max_page_width: int = 2560,
    max_page_height: int = 3600,
    min_page_width: int = 2000,
    cover_width: int = 960,
    webp_quality: int = 94,
    lossless: bool = False,
    include_cover_as_page: bool = True,
    gemini_api_key: str = "",
    gemini_model: str = "gemini-2.5-flash",
    prompt_path: Path = DEFAULT_PROMPT_PATH,
    correction_prompt_path: Path = DEFAULT_CORRECTION_PROMPT_PATH,
    enable_second_pass: bool = True,
    api_delay_sec: float = 2.0,
) -> dict:
    if not pdf_path.exists():
        raise FileNotFoundError(f"PDF not found: {pdf_path}")

    output_dir.mkdir(parents=True, exist_ok=True)
    zip_filename = f"{book_id}.zip"
    zip_path = output_dir / zip_filename
    standalone_cover_path = output_dir / f"{book_id}_cover.webp"

    info = pdfinfo_from_path(str(pdf_path))
    total_pdf_pages = int(info["Pages"])
    if total_pdf_pages < 1:
        raise ValueError("PDF contains zero pages.")

    rendered_page_count = 0
    reader_to_pdf_pages: list[tuple[int, int]] = []
    save_kwargs = (
        {"format": "WEBP", "lossless": True, "quality": 100, "method": 6}
        if lossless
        else {"format": "WEBP", "quality": webp_quality, "method": 6}
    )

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        files_to_zip: list[tuple[Path, str]] = []

        # 1. Render page 1 as crisp cover.webp thumbnail
        cover_images = convert_from_path(
            str(pdf_path), dpi=dpi, first_page=1, last_page=1
        )
        cover_img = resize_to_width(cover_images[0], cover_width)
        cover_tmp = tmp_path / "cover.webp"
        cover_img.save(cover_tmp, format="WEBP", quality=max(webp_quality, 90), method=6)
        cover_img.save(standalone_cover_path, format="WEBP", quality=max(webp_quality, 90), method=6)
        files_to_zip.append((cover_tmp, "cover.webp"))

        # 2. Render each PDF page at high DPI with native pixel fidelity
        start_page = 1 if (include_cover_as_page or total_pdf_pages == 1) else 2
        for pdf_page in range(start_page, total_pdf_pages + 1):
            pages = convert_from_path(
                str(pdf_path), dpi=dpi, first_page=pdf_page, last_page=pdf_page
            )
            raw_page = pages[0]

            if raw_page.width < min_page_width and dpi < 600:
                boosted_dpi = min(600, max(dpi, round(dpi * (min_page_width / float(raw_page.width)))))
                if boosted_dpi > dpi:
                    raw_page = convert_from_path(
                        str(pdf_path), dpi=boosted_dpi, first_page=pdf_page, last_page=pdf_page
                    )[0]

            page_img = fit_within_bounds(raw_page, max_page_width, max_page_height)

            rendered_page_count += 1
            reader_to_pdf_pages.append((rendered_page_count, pdf_page))
            arc_name = f"page_{rendered_page_count:03d}.webp"
            page_tmp = tmp_path / arc_name
            page_img.save(page_tmp, **save_kwargs)
            files_to_zip.append((page_tmp, arc_name))

        # 3. Extract text lines & translate to Bangla (translations_bn.json) if API key is configured
        translations_bundle = build_bangla_translations_bundle(
            pdf_path=pdf_path,
            reader_to_pdf_pages=reader_to_pdf_pages,
            api_key=gemini_api_key,
            model=gemini_model,
            prompt_path=prompt_path,
            correction_prompt_path=correction_prompt_path,
            enable_second_pass=enable_second_pass,
            api_delay_sec=api_delay_sec,
        )
        if translations_bundle is not None:
            trans_tmp = tmp_path / "translations_bn.json"
            trans_tmp.write_text(
                json.dumps(translations_bundle, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            files_to_zip.append((trans_tmp, "translations_bn.json"))

        # 4. Bundle into zipped book archive
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for src_file, arc_name in files_to_zip:
                zf.write(src_file, arcname=arc_name)

    zip_size_bytes = zip_path.stat().st_size
    normalized_base = base_url.rstrip("/")

    return {
        "id": book_id,
        "title": title,
        "author": author,
        "description": description,
        "category": category,
        "pdfFileName": pdf_path.name,
        "coverUrl": f"{normalized_base}/{standalone_cover_path.name}",
        "zipUrl": f"{normalized_base}/{zip_filename}",
        "pageCount": rendered_page_count,
        "fileSizeBytes": zip_size_bytes,
    }


def upsert_catalog_entry(catalog_path: Path, entry: dict, catalog_title: str) -> None:
    if catalog_path.exists():
        data = json.loads(catalog_path.read_text(encoding="utf-8"))
        if isinstance(data, list):
            data = {"catalogTitle": catalog_title, "books": data}
    else:
        data = {"catalogTitle": catalog_title, "books": []}

    books = [b for b in data.get("books", []) if b.get("id") != entry["id"]]
    books.append(entry)
    data["books"] = books
    catalog_path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert a PDF into a high-resolution BS Reader WebP .zip archive with offline Bangla translations."
    )
    parser.add_argument("pdf", type=Path, help="Path to the source PDF file")
    parser.add_argument("-o", "--output-dir", type=Path, default=Path("./docs"))
    parser.add_argument("--id", dest="book_id", default=None)
    parser.add_argument("--title", default=None)
    parser.add_argument("--author", default="BESTSELLER Manuals")
    parser.add_argument("--description", default="")
    parser.add_argument("--category", default="Manual")
    parser.add_argument(
        "--base-url",
        default="https://lalonbrand.github.io/bs-reader-manuals",
        help="Public base URL where the .zip and cover.webp will be hosted",
    )
    parser.add_argument("--dpi", type=int, default=300)
    parser.add_argument("--quality", type=int, default=94)
    parser.add_argument("--lossless", action="store_true")
    parser.add_argument("--skip-cover-page", action="store_true")
    parser.add_argument("--catalog", type=Path, default=None)
    parser.add_argument("--gemini-model", default="gemini-2.5-flash")
    parser.add_argument("--prompt-file", type=Path, default=DEFAULT_PROMPT_PATH)
    parser.add_argument("--correction-prompt-file", type=Path, default=DEFAULT_CORRECTION_PROMPT_PATH)
    parser.add_argument("--no-second-pass", action="store_true")
    parser.add_argument("--api-delay", type=float, default=2.0)
    args = parser.parse_args()

    title = args.title or args.pdf.stem.replace("_", " ").replace("-", " ").title()
    book_id = args.book_id or slugify(title)
    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()

    entry = render_pdf_to_webp_zip(
        pdf_path=args.pdf,
        output_dir=args.output_dir,
        book_id=book_id,
        title=title,
        author=args.author,
        description=args.description,
        category=args.category,
        base_url=args.base_url,
        dpi=args.dpi,
        webp_quality=args.quality,
        lossless=args.lossless,
        include_cover_as_page=not args.skip_cover_page,
        gemini_api_key=gemini_key,
        gemini_model=args.gemini_model,
        prompt_path=args.prompt_file,
        correction_prompt_path=args.correction_prompt_file,
        enable_second_pass=not args.no_second_pass,
        api_delay_sec=args.api_delay,
    )

    if args.catalog:
        upsert_catalog_entry(args.catalog, entry, catalog_title="BS Reader Manuals")

    print(json.dumps(entry, indent=2))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Converts a local PDF into a BS Reader-compatible .zip archive of high-resolution
WebP pages (cover.webp + 300-DPI page_XXX.webp), extracts and translates text
lines into Bangla (translations_bn.json) using the repository's custom Gemini
prompts, and updates catalog.json metadata.
"""

import argparse
import base64
import io
import json
import os
import re
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from collections import deque
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


PARA_START_RE = re.compile(r"^(?:\d+(?:\.\d+)*\.?\s+|[•●▪▫\-–—*]\s+|[A-Za-z]\)\s+)")


def _join_wrapped_lines(parts: list[str]) -> str:
    joined = ""
    for part in parts:
        chunk = part.strip()
        if not chunk:
            continue
        if not joined:
            joined = chunk
        elif joined.endswith("-") and len(joined) >= 2 and joined[-2].isalpha() and chunk[0].islower():
            joined = joined[:-1] + chunk
        else:
            joined = f"{joined} {chunk}"
    return re.sub(r"\s+", " ", joined).strip()


def extract_page_paragraphs(pdf_path: Path, page_numbers_1based: list[int]) -> dict[int, list[str]]:
    """Extracts ordered, cohesive paragraphs & headings per 1-based PDF page using PyMuPDF."""
    if fitz is None:
        print("Warning: PyMuPDF (fitz) is not installed; skipping text extraction.")
        return {}

    doc = fitz.open(str(pdf_path))
    pages_map: dict[int, list[str]] = {}
    try:
        for pdf_page_num in page_numbers_1based:
            page = doc[pdf_page_num - 1]
            text_dict = page.get_text("dict", sort=True)

            # Collect row entries: (block_idx, y0, y1, x0, font_size, text)
            block_rows: list[tuple[int, float, float, float, float, str]] = []
            for block_idx, block in enumerate(text_dict.get("blocks", [])):
                if block.get("type", 0) != 0:
                    continue
                fragments: list[tuple[float, float, float, float, float, str]] = []
                for line in block.get("lines", []):
                    spans = line.get("spans", [])
                    spans_text = "".join(span.get("text", "") for span in spans)
                    cleaned = re.sub(r"\s+", " ", spans_text).strip()
                    if not cleaned:
                        continue
                    bbox = line.get("bbox") or (0.0, 0.0, 0.0, 0.0)
                    sizes = [float(span.get("size", 10.0)) for span in spans if span.get("text", "").strip()]
                    avg_size = sum(sizes) / len(sizes) if sizes else 10.0
                    y0 = float(bbox[1])
                    y1 = float(bbox[3])
                    x0 = float(bbox[0])
                    y_mid = (y0 + y1) / 2.0
                    fragments.append((y_mid, y0, y1, x0, avg_size, cleaned))

                fragments.sort(key=lambda item: (round(item[0] / 4.0), item[3]))
                rows_in_block: list[list[tuple[float, float, float, float, float, str]]] = []
                for frag in fragments:
                    if rows_in_block and abs(frag[0] - rows_in_block[-1][0][0]) <= 4.5:
                        rows_in_block[-1].append(frag)
                    else:
                        rows_in_block.append([frag])

                for row in rows_in_block:
                    row.sort(key=lambda item: item[3])
                    merged_text = " ".join(part[5] for part in row).strip()
                    if merged_text:
                        r_y0 = min(part[1] for part in row)
                        r_y1 = max(part[2] for part in row)
                        r_x0 = row[0][3]
                        r_size = sum(part[4] for part in row) / len(row)
                        block_rows.append((block_idx, r_y0, r_y1, r_x0, r_size, merged_text))

            paragraphs: list[str] = []
            current_parts: list[str] = []
            prev_row: tuple[int, float, float, float, float, str] | None = None

            for row in block_rows:
                b_idx, y0, y1, x0, fsize, text = row
                if prev_row is None:
                    current_parts = [text]
                    prev_row = row
                    continue

                p_b_idx, p_y0, p_y1, p_x0, p_fsize, p_text = prev_row
                line_height = max(8.0, p_y1 - p_y0)
                v_gap = y0 - p_y1
                same_block = b_idx == p_b_idx
                font_changed = abs(fsize - p_fsize) >= 1.2
                new_list_or_heading = bool(PARA_START_RE.match(text))
                prev_looks_like_heading = (
                    len(p_text) <= 75
                    and not p_text.endswith((".", ",", ";", ":"))
                    and bool(re.match(r"^\d+(?:\.\d+)*\.?\s+[A-Z]", p_text) or p_text.istitle() or p_text.isupper())
                )
                large_gap = v_gap > (line_height * 0.65)
                tight_continuation = (
                    not same_block
                    and v_gap >= -4.0
                    and v_gap <= (line_height * 0.75)
                    and not font_changed
                    and not new_list_or_heading
                    and not prev_looks_like_heading
                    and not p_text.endswith((".", "!", "?"))
                )

                should_split = (
                    font_changed
                    or new_list_or_heading
                    or prev_looks_like_heading
                    or large_gap
                    or (not same_block and not tight_continuation)
                )

                if should_split:
                    para = _join_wrapped_lines(current_parts)
                    if para:
                        paragraphs.append(para)
                    current_parts = [text]
                else:
                    current_parts.append(text)

                prev_row = row

            if current_parts:
                para = _join_wrapped_lines(current_parts)
                if para:
                    paragraphs.append(para)

            pages_map[pdf_page_num] = paragraphs
    finally:
        doc.close()
    return pages_map


def is_translatable_line(text: str) -> bool:
    """Skips pure punctuation/numbers/codes that stay identical in Bangla."""
    return bool(re.search(r"[A-Za-z]{2,}", text))


def has_bangla_script(text: str) -> bool:
    return bool(re.search(r"[\u0980-\u09FF]", text))


class GeminiRateLimiter:
    """Enforces a strict requests-per-minute (RPM) cap using a 60s sliding window + even spacing."""

    def __init__(self, rpm: float = 10.0):
        self.rpm = max(1.0, float(rpm))
        self.min_interval_sec = 60.0 / self.rpm
        self.max_per_window = max(1, int(self.rpm))
        self._timestamps: deque[float] = deque()

    def wait_for_slot(self) -> None:
        now = time.monotonic()

        # 1. Enforce even spacing (e.g., 6.0s between request starts for 10 RPM)
        if self._timestamps:
            elapsed_since_last = now - self._timestamps[-1]
            if elapsed_since_last < self.min_interval_sec:
                time.sleep(self.min_interval_sec - elapsed_since_last)
                now = time.monotonic()

        # 2. Enforce strict sliding 60s window cap
        while self._timestamps and (now - self._timestamps[0]) >= 60.0:
            self._timestamps.popleft()

        if len(self._timestamps) >= self.max_per_window:
            wait_sec = 60.0 - (now - self._timestamps[0]) + 0.05
            if wait_sec > 0:
                time.sleep(wait_sec)
            now = time.monotonic()
            while self._timestamps and (now - self._timestamps[0]) >= 60.0:
                self._timestamps.popleft()

        self._timestamps.append(now)


PREFERRED_GEMINI_MODELS = (
    "gemini-2.5-flash",
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash",
    "gemini-3-flash-preview",
)


def _strip_json_fences(raw_text: str) -> str:
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    return cleaned.strip()


def _extract_candidate_text(parsed: dict) -> str:
    candidates = parsed.get("candidates") or []
    if not candidates:
        raise RuntimeError(f"Empty Gemini candidates: {json.dumps(parsed)[:300]}")
    content_obj = candidates[0].get("content") or {}
    parts = content_obj.get("parts") or []
    # Thinking models (e.g., gemini-2.5-flash) may emit thought parts before the JSON text part.
    non_thought_texts = [
        str(p.get("text", ""))
        for p in parts
        if isinstance(p, dict) and not p.get("thought") and str(p.get("text", "")).strip()
    ]
    if non_thought_texts:
        return "\n".join(non_thought_texts).strip()
    any_texts = [
        str(p.get("text", ""))
        for p in parts
        if isinstance(p, dict) and str(p.get("text", "")).strip()
    ]
    if any_texts:
        return "\n".join(any_texts).strip()
    finish_reason = candidates[0].get("finishReason", "UNKNOWN")
    raise RuntimeError(f"No text part in Gemini candidate (finishReason={finish_reason}).")


def _discover_available_flash_models(api_key: str) -> list[str]:
    url = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}"
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    models: list[str] = []
    for entry in payload.get("models", []):
        methods = entry.get("supportedGenerationMethods") or []
        if "generateContent" not in methods:
            continue
        name = str(entry.get("name", "")).removeprefix("models/").strip()
        if name and "flash" in name.lower():
            models.append(name)
    return models


def preflight_gemini_api(
    api_key: str,
    requested_model: str,
    rate_limiter: GeminiRateLimiter,
) -> tuple[str | None, str]:
    """Verifies API key & resolves a working Flash model before looping over all PDF pages."""
    candidates: list[str] = [requested_model]
    try:
        available = _discover_available_flash_models(api_key)
        if available:
            print(f"Discovered Flash models on key: {available[:8]}", flush=True)
            if requested_model not in available:
                candidates = []
            for preferred in PREFERRED_GEMINI_MODELS:
                if preferred in available and preferred not in candidates:
                    candidates.append(preferred)
            for name in available:
                if name not in candidates and "tts" not in name.lower() and "image" not in name.lower():
                    candidates.append(name)
    except urllib.error.HTTPError as err:
        err_body = err.read().decode("utf-8", errors="ignore")
        if err.code in (400, 401, 403):
            return None, f"Gemini API key rejected (HTTP {err.code}): {err_body[:240]}"
        print(f"Warning: ListModels check returned HTTP {err.code}; proceeding with {requested_model}.", flush=True)
    except Exception as exc:
        print(f"Warning: ListModels check failed ({exc}); proceeding with {requested_model}.", flush=True)

    probe_schema = {
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
    }

    last_error = "No compatible Gemini Flash model responded."
    for candidate_model in candidates[:4]:
        try:
            probe_res = call_gemini_json(
                api_key=api_key,
                model=candidate_model,
                system_instruction="Translate English to Bangla and return JSON: {\"items\": [{\"index\": 0, \"bn\": \"...\"}]}",
                user_parts=[{"text": json.dumps({"items": [{"index": 0, "en": "Quality Manual"}]})}],
                response_schema=probe_schema,
                max_retries=1,
                rate_limiter=rate_limiter,
            )
            items = extract_items_list(probe_res)
            if items and has_bangla_script(str(items[0].get("bn", ""))):
                return candidate_model, f"Verified working model: {candidate_model}"
            last_error = f"Model {candidate_model} probe returned unexpected payload: {str(probe_res)[:160]}"
        except Exception as exc:
            last_error = str(exc)
            print(f"Pre-flight check failed for {candidate_model}: {last_error}", flush=True)
            # Invalid API key fails for all models; stop immediately
            if "API_KEY_INVALID" in last_error or "HTTP 401" in last_error or "HTTP 403" in last_error:
                break

    return None, last_error


def call_gemini_json(
    api_key: str,
    model: str,
    system_instruction: str,
    user_parts: list[dict],
    response_schema: dict | None = None,
    max_retries: int = 5,
    base_delay_sec: float = 4.0,
    rate_limiter: GeminiRateLimiter | None = None,
) -> dict | list:
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{model}:generateContent?key={api_key}"
    )

    def build_body(include_schema: bool, include_thinking_cfg: bool) -> bytes:
        gen_cfg: dict = {
            "temperature": 0.2,
            "responseMimeType": "application/json",
        }
        if include_thinking_cfg:
            gen_cfg["thinkingConfig"] = {"thinkingBudget": 0}
        if include_schema and response_schema is not None:
            gen_cfg["responseSchema"] = response_schema
        payload = {
            "systemInstruction": {"parts": [{"text": system_instruction}]},
            "contents": [{"role": "user", "parts": user_parts}],
            "generationConfig": gen_cfg,
        }
        return json.dumps(payload).encode("utf-8")

    use_schema = response_schema is not None
    use_thinking_cfg = "2.5" in model
    data = build_body(use_schema, use_thinking_cfg)

    for attempt in range(max_retries + 1):
        if rate_limiter is not None:
            rate_limiter.wait_for_slot()
        req = urllib.request.Request(
            url,
            data=data,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                raw = resp.read().decode("utf-8")
                parsed = json.loads(raw)
                text_out = _strip_json_fences(_extract_candidate_text(parsed))
                return json.loads(text_out)
        except urllib.error.HTTPError as err:
            err_body = err.read().decode("utf-8", errors="ignore")
            if err.code == 400 and use_thinking_cfg and "thinking" in err_body.lower():
                print("Gemini HTTP 400 on thinkingConfig; retrying without thinkingConfig...", flush=True)
                use_thinking_cfg = False
                data = build_body(use_schema, False)
                continue
            if err.code == 400 and use_schema and "API_KEY_INVALID" not in err_body:
                print("Gemini HTTP 400 with responseSchema; retrying without schema...", flush=True)
                use_schema = False
                data = build_body(False, use_thinking_cfg)
                continue
            if err.code in (429, 500, 502, 503, 504) and attempt < max_retries:
                retry_after = err.headers.get("Retry-After") if err.headers else None
                try:
                    header_wait = float(retry_after) if retry_after else 0.0
                except ValueError:
                    header_wait = 0.0
                wait_sec = min(90.0, max(header_wait, 15.0 if err.code == 429 else base_delay_sec * (2 ** attempt)))
                if err.code == 429:
                    wait_sec = min(90.0, max(header_wait, 15.0 * (attempt + 1)))
                print(f"Gemini HTTP {err.code}; retrying in {wait_sec:.1f}s...", flush=True)
                time.sleep(wait_sec)
                continue
            raise RuntimeError(f"Gemini API error HTTP {err.code}: {err_body[:400]}") from err
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, RuntimeError) as err:
            if attempt < max_retries:
                wait_sec = base_delay_sec * (2 ** attempt)
                print(f"Gemini transient error ({err}); retrying in {wait_sec:.1f}s...", flush=True)
                time.sleep(wait_sec)
                continue
            raise


def extract_items_list(res: dict | list) -> list:
    if isinstance(res, list):
        return res
    if isinstance(res, dict):
        if isinstance(res.get("items"), list):
            return res["items"]
        for val in res.values():
            if isinstance(val, list):
                return val
    return []


def translate_paragraphs_with_gemini(
    api_key: str,
    model: str,
    translation_prompt: str,
    correction_prompt: str | None,
    paragraphs: list[str],
    rate_limiter: GeminiRateLimiter,
) -> list[str]:
    indexed_items = [{"index": i, "en": para} for i, para in enumerate(paragraphs)]

    pass1_system = (
        f"{translation_prompt.strip()}\n\n"
        "JSON OUTPUT FORMAT REQUIREMENT:\n"
        "You will receive a JSON array of objects `{index, en}` representing complete paragraphs "
        "and headings from one manual page in top-to-bottom reading order. Preserve the exact same "
        "count and `index` values. Translate strictly what is written in each `en` paragraph—do NOT "
        "add extra explanations, parenthetical notes, examples, or commentary that are not in the PDF.\n"
        "Return a valid JSON object strictly matching:\n"
        '{"items": [{"index": 0, "bn": "..."}]}\n'
        "Every item's `bn` field must contain ONLY the natural Bangla paragraph translation."
    )
    schema = {
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
    }

    pass1_user = json.dumps({"items": indexed_items}, ensure_ascii=False)
    pass1_res = call_gemini_json(
        api_key,
        model,
        pass1_system,
        [{"text": pass1_user}],
        response_schema=schema,
        rate_limiter=rate_limiter,
    )
    pass1_items = extract_items_list(pass1_res)
    bn_by_index = {
        int(item["index"]): str(item.get("bn", "")).strip()
        for item in pass1_items
        if isinstance(item, dict) and "index" in item
    }
    first_pass_paragraphs = [bn_by_index.get(i, "") for i in range(len(paragraphs))]

    if not correction_prompt:
        return first_pass_paragraphs

    pass2_system = (
        f"{correction_prompt.strip()}\n\n"
        "JSON OUTPUT FORMAT REQUIREMENT:\n"
        "You will receive a JSON array of objects `{index, en, bn}` where `en` is the original "
        "English paragraph and `bn` is the draft Bangla paragraph translation. Remove any extra "
        "explanations not present in `en` and keep natural Bangladeshi Bangla flow. Preserve the exact "
        "count and `index` values. Return a valid JSON object strictly matching:\n"
        '{"items": [{"index": 0, "bn": "..."}]}'
    )
    pass2_items = [
        {"index": i, "en": paragraphs[i], "bn": first_pass_paragraphs[i] or paragraphs[i]}
        for i in range(len(paragraphs))
    ]
    try:
        pass2_res = call_gemini_json(
            api_key,
            model,
            pass2_system,
            [{"text": json.dumps({"items": pass2_items}, ensure_ascii=False)}],
            response_schema=schema,
            rate_limiter=rate_limiter,
        )
        pass2_list = extract_items_list(pass2_res)
        corrected_by_index = {
            int(item["index"]): str(item.get("bn", "")).strip()
            for item in pass2_list
            if isinstance(item, dict) and "index" in item
        }
        return [corrected_by_index.get(i, first_pass_paragraphs[i]) for i in range(len(paragraphs))]
    except Exception as exc:
        print(f"Warning: second-pass correction failed ({exc}); keeping Pass 1 translation.", flush=True)
        return first_pass_paragraphs


def translate_page_image_with_gemini(
    api_key: str,
    model: str,
    translation_prompt: str,
    correction_prompt: str | None,
    page_image_path: Path,
    rate_limiter: GeminiRateLimiter,
) -> list[dict[str, str]]:
    """Multimodal OCR + Bangla translation fallback for PDFs whose text is outlined/scanned."""
    with Image.open(page_image_path) as img:
        rgb = to_rgb_white_bg(img)
        if rgb.width > 1800:
            rgb = resize_to_width(rgb, 1800)
        buf = io.BytesIO()
        rgb.save(buf, format="JPEG", quality=88)
        b64_jpeg = base64.b64encode(buf.getvalue()).decode("ascii")

    vision_schema = {
        "type": "OBJECT",
        "properties": {
            "items": {
                "type": "ARRAY",
                "items": {
                    "type": "OBJECT",
                    "properties": {
                        "index": {"type": "INTEGER"},
                        "en": {"type": "STRING"},
                        "bn": {"type": "STRING"},
                    },
                    "required": ["index", "en", "bn"],
                },
            }
        },
        "required": ["items"],
    }

    pass1_system = (
        f"{translation_prompt.strip()}\n\n"
        "MULTIMODAL OCR & PARAGRAPH JSON OUTPUT REQUIREMENT:\n"
        "Read the attached manual page image in natural top-to-bottom reading order and group text "
        "into complete headings, paragraphs, and list items (do NOT split body paragraphs into "
        "individual visual lines). For each paragraph/heading, put the exact English text in `en` and "
        "its strictly faithful Bangla translation in `bn`—do NOT add extra explanations or details "
        "not printed on the page. If the page is blank or artwork-only with zero readable text, "
        "return `{\"items\": []}`.\n"
        "Return valid JSON strictly matching: "
        '{"items": [{"index": 0, "en": "...", "bn": "..."}]}'
    )

    parts = [
        {"inlineData": {"mimeType": "image/jpeg", "data": b64_jpeg}},
        {"text": "Extract every visible heading and paragraph in reading order and translate strictly to Bangla without extra explanations."},
    ]
    res = call_gemini_json(
        api_key,
        model,
        pass1_system,
        parts,
        response_schema=vision_schema,
        rate_limiter=rate_limiter,
    )
    raw_items = extract_items_list(res)
    extracted_pairs: list[dict[str, str]] = []
    for item in raw_items:
        if not isinstance(item, dict):
            continue
        en_text = str(item.get("en", "")).strip()
        bn_text = str(item.get("bn", "")).strip()
        if en_text and is_translatable_line(en_text) and bn_text:
            extracted_pairs.append({"en": en_text, "bn": bn_text})

    if not extracted_pairs or not correction_prompt:
        return extracted_pairs

    pass2_schema = {
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
    }
    pass2_system = (
        f"{correction_prompt.strip()}\n\n"
        "JSON OUTPUT FORMAT REQUIREMENT:\n"
        "Review each `{index, en, bn}` pair and return corrected Bangla lines strictly matching:\n"
        '{"items": [{"index": 0, "bn": "..."}]}'
    )
    pass2_items = [
        {"index": i, "en": pair["en"], "bn": pair["bn"]}
        for i, pair in enumerate(extracted_pairs)
    ]
    try:
        pass2_res = call_gemini_json(
            api_key,
            model,
            pass2_system,
            [{"text": json.dumps({"items": pass2_items}, ensure_ascii=False)}],
            response_schema=pass2_schema,
            rate_limiter=rate_limiter,
        )
        pass2_list = extract_items_list(pass2_res)
        corrected_by_index = {
            int(item["index"]): str(item.get("bn", "")).strip()
            for item in pass2_list
            if isinstance(item, dict) and "index" in item
        }
        return [
            {"en": pair["en"], "bn": corrected_by_index.get(i, pair["bn"])}
            for i, pair in enumerate(extracted_pairs)
        ]
    except Exception as exc:
        print(f"Warning: vision second-pass correction failed ({exc}); keeping Pass 1 translation.", flush=True)
        return extracted_pairs


def build_bangla_translations_bundle(
    pdf_path: Path,
    reader_pages_info: list[tuple[int, int, Path]],
    api_key: str,
    model: str,
    prompt_path: Path,
    correction_prompt_path: Path | None,
    enable_second_pass: bool = True,
    requests_per_minute: float = 10.0,
    diagnostics_out: dict | None = None,
) -> dict | None:
    def record_diag(status: str, detail: str, active_model: str = model) -> None:
        if diagnostics_out is not None:
            diagnostics_out.update({
                "status": status,
                "detail": detail,
                "model": active_model,
            })

    if not api_key.strip():
        msg = "GEMINI_API_KEY secret is not configured in GitHub repository secrets."
        print(f"::warning::{msg} Skipping Bangla translation generation.", flush=True)
        record_diag("SKIPPED_NO_KEY", msg)
        return None
    if not prompt_path.exists():
        msg = f"Translation prompt not found at {prompt_path}."
        print(f"::warning::{msg} Skipping.", flush=True)
        record_diag("SKIPPED_NO_PROMPT", msg)
        return None

    rate_limiter = GeminiRateLimiter(rpm=requests_per_minute)
    print(
        f"Rate limiter active: {rate_limiter.rpm:.1f} requests/minute "
        f"(1 request every {rate_limiter.min_interval_sec:.1f}s).",
        flush=True,
    )

    active_model, preflight_msg = preflight_gemini_api(api_key.strip(), model, rate_limiter)
    if not active_model:
        print(f"::error::Gemini pre-flight failed — {preflight_msg}", flush=True)
        record_diag("API_ERROR", preflight_msg)
        return None
    print(f"Gemini pre-flight OK: {preflight_msg}", flush=True)
    model = active_model

    translation_prompt = prompt_path.read_text(encoding="utf-8")
    correction_prompt = None
    if enable_second_pass and correction_prompt_path and correction_prompt_path.exists():
        correction_prompt = correction_prompt_path.read_text(encoding="utf-8")

    pdf_pages = [pdf_p for _, pdf_p, _ in reader_pages_info]
    extracted_by_pdf_page = extract_page_paragraphs(pdf_path, pdf_pages)
    if fitz is None:
        print("Warning: PyMuPDF unavailable; falling back to Gemini Vision OCR for all pages.", flush=True)

    para_cache: dict[str, str] = {}
    pages_payload: list[dict] = []
    total_pages = len(reader_pages_info)
    translated_pages_count = 0
    total_translated_paragraphs = 0
    actual_bangla_paragraphs_count = 0
    failed_pages_count = 0
    last_error_detail = ""

    for reader_page_num, pdf_page_num, page_img_path in reader_pages_info:
        pct = round((reader_page_num / float(max(1, total_pages))) * 100)
        raw_paragraphs = extracted_by_pdf_page.get(pdf_page_num, [])
        translatable_raw = [para for para in raw_paragraphs if is_translatable_line(para)]

        if not translatable_raw:
            reason = "No PDF text layer" if not raw_paragraphs else "Only bare numbers/symbols in PDF text layer"
            print(
                f"[Bangla {reader_page_num}/{total_pages} | {pct}%] "
                f"{reason}; using Gemini Vision on page image...",
                flush=True,
            )
            try:
                vision_paragraphs = translate_page_image_with_gemini(
                    api_key=api_key,
                    model=model,
                    translation_prompt=translation_prompt,
                    correction_prompt=correction_prompt,
                    page_image_path=page_img_path,
                    rate_limiter=rate_limiter,
                )
                if vision_paragraphs:
                    translated_pages_count += 1
                    total_translated_paragraphs += len(vision_paragraphs)
                    actual_bangla_paragraphs_count += sum(
                        1 for pair in vision_paragraphs if has_bangla_script(pair.get("bn", ""))
                    )
                    print(
                        f"[Bangla {reader_page_num}/{total_pages} | {pct}%] "
                        f"Vision translated {len(vision_paragraphs)} paragraph(s).",
                        flush=True,
                    )
                else:
                    print(
                        f"[Bangla {reader_page_num}/{total_pages} | {pct}%] No visible text on image.",
                        flush=True,
                    )
                pages_payload.append({"page": reader_page_num, "paragraphs": vision_paragraphs})
            except Exception as exc:
                failed_pages_count += 1
                last_error_detail = str(exc)
                print(
                    f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Warning: vision translation failed ({exc})",
                    flush=True,
                )
                pages_payload.append({"page": reader_page_num, "paragraphs": []})
            continue

        # Identify paragraphs not yet cached that actually contain English words
        uncached_paragraphs: list[str] = []
        seen_in_batch: set[str] = set()
        for para in translatable_raw:
            if para not in para_cache and para not in seen_in_batch:
                uncached_paragraphs.append(para)
                seen_in_batch.add(para)

        if uncached_paragraphs:
            pass_label = "2-pass" if correction_prompt else "1-pass"
            print(
                f"[Bangla {reader_page_num}/{total_pages} | {pct}%] "
                f"Translating {len(uncached_paragraphs)} paragraph(s) ({pass_label}, {model})...",
                flush=True,
            )
            try:
                translated_batch = translate_paragraphs_with_gemini(
                    api_key=api_key,
                    model=model,
                    translation_prompt=translation_prompt,
                    correction_prompt=correction_prompt,
                    paragraphs=uncached_paragraphs,
                    rate_limiter=rate_limiter,
                )
                for src, dst in zip(uncached_paragraphs, translated_batch):
                    cleaned_dst = dst.strip() if dst else ""
                    if cleaned_dst:
                        para_cache[src] = cleaned_dst
                print(
                    f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Completed.",
                    flush=True,
                )
            except Exception as exc:
                failed_pages_count += 1
                last_error_detail = str(exc)
                print(
                    f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Warning: translation failed ({exc})",
                    flush=True,
                )
        else:
            print(
                f"[Bangla {reader_page_num}/{total_pages} | {pct}%] Served from paragraph cache.",
                flush=True,
            )

        page_paragraphs_out = []
        for para in translatable_raw:
            bn_text = para_cache.get(para, "").strip()
            if bn_text:
                page_paragraphs_out.append({"en": para, "bn": bn_text})
                if has_bangla_script(bn_text):
                    actual_bangla_paragraphs_count += 1

        if page_paragraphs_out:
            translated_pages_count += 1
            total_translated_paragraphs += len(page_paragraphs_out)

        pages_payload.append({"page": reader_page_num, "paragraphs": page_paragraphs_out})

    print(
        f"Bangla translation summary for '{pdf_path.name}': "
        f"{translated_pages_count}/{total_pages} pages, {total_translated_paragraphs} paragraphs "
        f"({actual_bangla_paragraphs_count} with Bangla script, failed pages: {failed_pages_count}).",
        flush=True,
    )

    if total_translated_paragraphs == 0 or actual_bangla_paragraphs_count == 0:
        reason = last_error_detail or "Model returned empty or non-Bangla output."
        print(
            f"::warning::Zero real Bangla translations generated for '{pdf_path.name}' ({reason}); "
            "omitting empty translations_bn.json.",
            flush=True,
        )
        record_diag("ZERO_OUTPUT", reason, model)
        return None

    record_diag("OK", f"Translated {translated_pages_count}/{total_pages} pages using {model}", model)
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
    requests_per_minute: float = 10.0,
    status_file: Path | None = None,
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
    reader_pages_info: list[tuple[int, int, Path]] = []
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
            arc_name = f"page_{rendered_page_count:03d}.webp"
            page_tmp = tmp_path / arc_name
            page_img.save(page_tmp, **save_kwargs)
            files_to_zip.append((page_tmp, arc_name))
            reader_pages_info.append((rendered_page_count, pdf_page, page_tmp))

        # 3. Extract text lines (or use Gemini Vision on image/outlined pages) & translate to Bangla
        diag_info: dict = {}
        translations_bundle = build_bangla_translations_bundle(
            pdf_path=pdf_path,
            reader_pages_info=reader_pages_info,
            api_key=gemini_api_key,
            model=gemini_model,
            prompt_path=prompt_path,
            correction_prompt_path=correction_prompt_path,
            enable_second_pass=enable_second_pass,
            requests_per_minute=requests_per_minute,
            diagnostics_out=diag_info,
        )
        if status_file is not None:
            existing_status = {}
            if status_file.exists():
                try:
                    existing_status = json.loads(status_file.read_text(encoding="utf-8"))
                except Exception:
                    existing_status = {}
            existing_status[zip_filename] = diag_info
            status_file.parent.mkdir(parents=True, exist_ok=True)
            status_file.write_text(json.dumps(existing_status, indent=2), encoding="utf-8")

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


def prune_catalog_with_incoming_dir(
    catalog_path: Path,
    output_dir: Path,
    incoming_dir: Path,
    catalog_title: str = "BS Reader Manuals",
) -> list[str]:
    """Removes catalog entries and generated .zip/_cover.webp files whose source PDF is gone."""
    existing_pdfs = {
        p.name.lower(): p.name
        for p in incoming_dir.iterdir()
        if p.is_file() and p.suffix.lower() == ".pdf"
    } if incoming_dir.exists() else {}

    if catalog_path.exists():
        data = json.loads(catalog_path.read_text(encoding="utf-8"))
        if isinstance(data, list):
            data = {"catalogTitle": catalog_title, "books": data}
    else:
        data = {"catalogTitle": catalog_title, "books": []}

    kept_books: list[dict] = []
    removed_ids: list[str] = []
    kept_ids: set[str] = set()

    for book in data.get("books", []):
        book_id = str(book.get("id", "")).strip()
        pdf_name = str(book.get("pdfFileName", "")).strip().lower()
        if pdf_name and pdf_name in existing_pdfs:
            kept_books.append(book)
            if book_id:
                kept_ids.add(book_id)
        else:
            if book_id:
                removed_ids.append(book_id)
                for suffix in (".zip", "_cover.webp"):
                    target = output_dir / f"{book_id}{suffix}"
                    if target.exists():
                        target.unlink()

    # Also clean up any orphaned archives/covers in output_dir not present in kept_ids
    if output_dir.exists():
        for artifact in output_dir.iterdir():
            if not artifact.is_file():
                continue
            if artifact.name.endswith(".zip"):
                aid = artifact.name[:-4]
                if aid not in kept_ids:
                    artifact.unlink()
                    if aid not in removed_ids:
                        removed_ids.append(aid)
            elif artifact.name.endswith("_cover.webp"):
                aid = artifact.name[:-11]
                if aid not in kept_ids:
                    artifact.unlink()

    data["books"] = kept_books
    catalog_path.parent.mkdir(parents=True, exist_ok=True)
    catalog_path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    return removed_ids


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert a PDF into a high-resolution BS Reader WebP .zip archive with offline Bangla translations."
    )
    parser.add_argument("pdf", nargs="?", type=Path, default=None, help="Path to the source PDF file")
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
    parser.add_argument("--sync-incoming-dir", type=Path, default=None)
    parser.add_argument("--prune-only", action="store_true")
    parser.add_argument("--gemini-model", default="gemini-2.5-flash")
    parser.add_argument("--prompt-file", type=Path, default=DEFAULT_PROMPT_PATH)
    parser.add_argument("--correction-prompt-file", type=Path, default=DEFAULT_CORRECTION_PROMPT_PATH)
    parser.add_argument("--no-second-pass", action="store_true")
    parser.add_argument("--rpm", type=float, default=10.0, help="Max Gemini API requests per minute (default: 10)")
    parser.add_argument("--api-delay", type=float, default=None, help="Optional legacy delay in seconds (overrides --rpm if > 0)")
    parser.add_argument("--status-file", type=Path, default=None, help="Optional JSON file for translation diagnostic status")
    args = parser.parse_args()

    if args.prune_only:
        if not args.catalog or not args.sync_incoming_dir:
            parser.error("--prune-only requires both --catalog and --sync-incoming-dir")
        removed = prune_catalog_with_incoming_dir(
            catalog_path=args.catalog,
            output_dir=args.output_dir,
            incoming_dir=args.sync_incoming_dir,
        )
        print(f"Pruned catalog against {args.sync_incoming_dir}: removed {len(removed)} manual(s) {removed}", flush=True)
        return

    if args.pdf is None:
        parser.error("pdf path is required unless --prune-only is set")

    title = args.title or args.pdf.stem.replace("_", " ").replace("-", " ").title()
    book_id = args.book_id or slugify(title)
    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()
    effective_rpm = args.rpm
    if args.api_delay is not None and args.api_delay > 0:
        effective_rpm = 60.0 / args.api_delay

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
        requests_per_minute=effective_rpm,
        status_file=args.status_file,
    )

    if args.catalog:
        upsert_catalog_entry(args.catalog, entry, catalog_title="BS Reader Manuals")
        if args.sync_incoming_dir:
            prune_catalog_with_incoming_dir(
                catalog_path=args.catalog,
                output_dir=args.output_dir,
                incoming_dir=args.sync_incoming_dir,
            )

    print(json.dumps(entry, indent=2))


if __name__ == "__main__":
    main()

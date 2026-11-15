#!/usr/bin/env python3
"""
Converts a local PDF into a BS Reader-compatible .zip archive of pre-rasterized
WebP pages (cover.webp + 1200px-wide page_XXX.webp) and generates/updates a
catalog.json metadata entry.
"""

import argparse
import json
import re
import tempfile
import zipfile
from pathlib import Path

from PIL import Image
from pdf2image import convert_from_path, pdfinfo_from_path

LANCZOS = getattr(Image, "Resampling", Image).LANCZOS


def slugify(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug or "manual"


def resize_to_width(img: Image.Image, target_width: int) -> Image.Image:
    if img.mode != "RGB":
        img = img.convert("RGB")
    w, h = img.size
    if w == target_width:
        return img
    target_height = max(1, round(h * (target_width / float(w))))
    return img.resize((target_width, target_height), LANCZOS)


def render_pdf_to_webp_zip(
    pdf_path: Path,
    output_dir: Path,
    book_id: str,
    title: str,
    author: str,
    description: str,
    category: str,
    base_url: str,
    dpi: int = 180,
    page_width: int = 1200,
    cover_width: int = 600,
    webp_quality: int = 82,
    include_cover_as_page: bool = True,
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

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        files_to_zip: list[tuple[Path, str]] = []

        # 1. Render page 1 as cover.webp thumbnail
        cover_images = convert_from_path(
            str(pdf_path), dpi=dpi, first_page=1, last_page=1
        )
        cover_img = resize_to_width(cover_images[0], cover_width)
        cover_tmp = tmp_path / "cover.webp"
        cover_img.save(cover_tmp, format="WEBP", quality=webp_quality, method=6)
        cover_img.save(standalone_cover_path, format="WEBP", quality=webp_quality, method=6)
        files_to_zip.append((cover_tmp, "cover.webp"))

        # 2. Convert pages into 1200px-wide compressed WebP sheets
        start_page = 1 if (include_cover_as_page or total_pdf_pages == 1) else 2
        for pdf_page in range(start_page, total_pdf_pages + 1):
            pages = convert_from_path(
                str(pdf_path), dpi=dpi, first_page=pdf_page, last_page=pdf_page
            )
            page_img = resize_to_width(pages[0], page_width)

            rendered_page_count += 1
            arc_name = f"page_{rendered_page_count:03d}.webp"
            page_tmp = tmp_path / arc_name
            page_img.save(page_tmp, format="WEBP", quality=webp_quality, method=6)
            files_to_zip.append((page_tmp, arc_name))

        # 3. Bundle into zipped book archive
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
        description="Convert a PDF into a BS Reader WebP .zip archive and catalog entry."
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
        default="https://mrlrajin.github.io/bs-reader-manuals",
        help="Public base URL where the .zip and cover.webp will be hosted",
    )
    parser.add_argument("--dpi", type=int, default=180)
    parser.add_argument("--quality", type=int, default=82)
    parser.add_argument(
        "--skip-cover-page",
        action="store_true",
        help="Exclude PDF page 1 from the readable page_XXX.webp sequence",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=None,
        help="Optional path to catalog.json to update in place",
    )
    args = parser.parse_args()

    title = args.title or args.pdf.stem.replace("_", " ").replace("-", " ").title()
    book_id = args.book_id or slugify(title)

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
        include_cover_as_page=not args.skip_cover_page,
    )

    if args.catalog:
        upsert_catalog_entry(args.catalog, entry, catalog_title="BS Reader Manuals")

    print(json.dumps(entry, indent=2))


if __name__ == "__main__":
    main()

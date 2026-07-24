#!/usr/bin/env python3
"""Build a RAG-friendly Markdown snapshot from Londonist residence pages.

The script deliberately excludes date-sensitive room availability. Londonist
loads that data from a separate endpoint, so prices and inventory should remain
in the application's structured offer tables instead of a static knowledge
document.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import date
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable
from urllib.request import Request, urlopen


LISTING_URL = "https://londonist.co.uk/student-accommodation/london/residences"
DETAIL_URL_PATTERN = re.compile(
    r"https://londonist\.co\.uk/student-accommodation/london/residences/[a-z0-9-]+"
)


def clean_text(value: str | None) -> str:
    if not value:
        return ""
    value = re.sub(r"<br\s*/?>", " ", value, flags=re.I)
    value = re.sub(r"<[^>]+>", " ", value)
    value = html.unescape(value)
    return re.sub(r"\s+", " ", value).strip()


def first_match(pattern: str, value: str, flags: int = re.I | re.S) -> str:
    match = re.search(pattern, value, flags)
    return clean_text(match.group(1)) if match else ""


def md_escape(value: str) -> str:
    return value.replace("|", r"\|").replace("\n", " ").strip()


class BlockTextParser(HTMLParser):
    """Extract readable paragraph/list blocks without third-party packages."""

    BLOCK_TAGS = {"p", "li", "h1", "h2", "h3", "h4", "h5", "h6"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.depth = 0
        self.current_tag = ""
        self.parts: list[str] = []
        self.blocks: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() == "br" and self.depth:
            self.parts.append("\n")
            return
        if tag.lower() in self.BLOCK_TAGS:
            if self.depth == 0:
                self.parts = []
                self.current_tag = tag.lower()
            self.depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() not in self.BLOCK_TAGS or self.depth == 0:
            return
        self.depth -= 1
        if self.depth == 0:
            raw = "".join(self.parts)
            segments = re.split(r"\n+", raw)
            segment_tag = "line" if len(segments) > 1 else self.current_tag
            for segment in segments:
                text = re.sub(r"\s+", " ", segment).strip()
                if text:
                    self.blocks.append((segment_tag, text))
            self.parts = []
            self.current_tag = ""

    def handle_data(self, data: str) -> None:
        if self.depth:
            self.parts.append(data)


@dataclass
class Residence:
    residence_id: str
    slug: str
    name: str
    url: str
    zone: str = ""
    postcode: str = ""
    address: str = ""
    station: str = ""
    transport: str = ""
    latitude: str = ""
    longitude: str = ""
    starting_price: str = ""
    tags: list[str] = field(default_factory=list)
    facilities: list[str] = field(default_factory=list)
    nearby_universities: list[str] = field(default_factory=list)
    nearby_attractions: list[str] = field(default_factory=list)


def unique(items: Iterable[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for item in items:
        item = clean_text(item)
        key = item.casefold()
        if item and key not in seen:
            seen.add(key)
            result.append(item)
    return result


def parse_listing(listing_html: str) -> dict[str, Residence]:
    starts = list(
        re.finditer(r'<div class="listing-item"\s+data-id="(\d+)">', listing_html, re.I)
    )
    residences: dict[str, Residence] = {}
    for index, match in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(listing_html)
        closing = listing_html.find("<!-- listing-item end -->", match.start(), end)
        if closing != -1:
            end = closing
        block = listing_html[match.start() : end]
        url_match = DETAIL_URL_PATTERN.search(block)
        if not url_match:
            continue
        url = url_match.group(0)
        slug = url.rstrip("/").rsplit("/", 1)[-1]
        if slug in residences:
            continue

        name = first_match(
            rf'<a[^>]+href="{re.escape(url)}"[^>]+aria-label="([^"]+)"', block
        )
        if not name:
            name = first_match(r'<h3 class="title-sin_map">.*?<a[^>]*>(.*?)</a>', block)

        location_html = first_match_raw(
            r'<div class="geodir-category-location[^"]*"[^>]*>(.*?)'
            r'<div class="d-flex align-items-center my-1">',
            block,
        )
        if not location_html:
            location_html = first_match_raw(
                r'<div class="geodir-category-location[^"]*"[^>]*>(.*?)</div>', block
            )

        status_html = first_match_raw(
            r'<div class="geodir_status_date[^"]*"[^>]*>(.*?)</div>', block
        )
        tags = unique(re.findall(r"<span>(.*?)</span>", status_html, re.I | re.S))
        facilities = unique(
            re.findall(r'data-tooltip="([^"]+)"', block, re.I | re.S)
        )
        price = first_match(r'<span class="price-amount-text">\s*(.*?)</span>', block)

        residence = Residence(
            residence_id=match.group(1),
            slug=slug,
            name=name or slug.replace("-", " ").title(),
            url=url,
            zone=first_match(r'<span class="residence-zone">(.*?)</span>', block),
            postcode=first_match(r'<span class="residence-postcode">(.*?)</span>', block),
            station=clean_text(location_html),
            transport=first_match(
                r'<div class="d-flex align-items-center my-1">.*?'
                r'<span class="ms-2">(.*?)</span>',
                block,
            ),
            latitude=first_match_attr(r'data-newlatitude="([^"]+)"', block),
            longitude=first_match_attr(r'data-newlongitude="([^"]+)"', block),
            starting_price=price,
            tags=tags,
            facilities=facilities,
        )
        residences[slug] = residence
    return residences


def first_match_raw(pattern: str, value: str, flags: int = re.I | re.S) -> str:
    match = re.search(pattern, value, flags)
    return match.group(1).strip() if match else ""


def first_match_attr(pattern: str, value: str) -> str:
    match = re.search(pattern, value, re.I)
    return html.unescape(match.group(1)).strip() if match else ""


def description_lists(detail_html: str) -> tuple[list[str], list[str], str, str]:
    wrapper = first_match_raw(
        r'<div class="list-single-main-item_content fl-wrap description-wrapper">'
        r"(.*?)</div>\s*<button[^>]+class=\"read-more-btn\"",
        detail_html,
    )
    if not wrapper:
        return [], [], "", ""

    parser = BlockTextParser()
    parser.feed(wrapper)
    universities: list[str] = []
    attractions: list[str] = []
    detail_zone = ""
    nearest_station = ""
    mode = ""

    for tag, line in parser.blocks:
        normalized = line.casefold()
        if normalized.startswith("zone "):
            detail_zone = line
            continue
        if normalized.startswith("nearest ") and "station" in normalized:
            nearest_station = re.sub(
                r"^Nearest\s+(?:Tube\s+)?Station\s*:\s*", "", line, flags=re.I
            )
            continue
        if "nearby" in normalized and (
            "universit" in normalized or "college" in normalized or "school" in normalized
        ) or "academic institutions" in normalized:
            mode = "universities"
            continue
        if "nearby" in normalized and (
            "attraction" in normalized or "location" in normalized
        ) or normalized.startswith("unbeatable location"):
            mode = "attractions"
            continue
        if line.rstrip().endswith(":"):
            mode = ""
            continue
        if tag not in {"li", "line"}:
            mode = ""
            continue
        line = re.sub(r"^\d+\)\s*", "", line)
        if line.casefold().startswith(
            ("travel times", "all durations", "note:", "please note")
        ):
            continue
        if mode == "universities":
            universities.append(line)
        elif mode == "attractions":
            attractions.append(line)

    return unique(universities), unique(attractions), detail_zone, nearest_station


def merge_detail(residence: Residence, detail_html: str) -> None:
    residence.address = first_match(
        r'<span class="residence-address-text"[^>]*>(.*?)</span>', detail_html
    )
    latitude = first_match_attr(r'data-latitude="([^"]+)"', detail_html)
    longitude = first_match_attr(r'data-longitude="([^"]+)"', detail_html)
    residence.latitude = latitude or residence.latitude
    residence.longitude = longitude or residence.longitude

    facilities_block = first_match_raw(
        r">Features and Facilities</h5>.*?"
        r'<div class="listing-features[^"]*"[^>]*>(.*?)</div>\s*</div>',
        detail_html,
    )
    detail_facilities = re.findall(
        r"<span>(.*?)</span>", facilities_block, re.I | re.S
    )
    detail_facilities = [
        value
        for value in detail_facilities
        if clean_text(value).casefold() not in {"enable scrolling", "your location"}
    ]
    residence.facilities = unique([*residence.facilities, *detail_facilities])

    universities, attractions, detail_zone, nearest_station = description_lists(
        detail_html
    )
    residence.nearby_universities = universities
    residence.nearby_attractions = attractions
    residence.zone = residence.zone or detail_zone
    residence.station = residence.station or nearest_station

    if not residence.postcode and residence.address:
        match = re.search(
            r"\b(?:[A-Z]{1,2}\d[A-Z\d]?\s*\d[A-Z]{2})\b", residence.address, re.I
        )
        if match:
            residence.postcode = match.group(0).upper()


def render_markdown(residences: list[Residence], snapshot_date: str) -> str:
    lines = [
        "# Londonist 伦敦学生公寓资料",
        "",
        f"- 数据来源：Londonist 官网公开列表页与各公寓详情页",
        f"- 抓取日期：{snapshot_date}",
        f"- 公寓数量：{len(residences)}",
        "- 城市：London",
        "",
        "> 使用边界：本文档适合回答公寓名称、地址、区域、交通、设施及附近学校等相对稳定的信息。"
        "官网起价只是抓取时的展示起价，不代表指定日期的最终报价；房型价格、租期与库存必须调用结构化报价/库存 Tool 查询。",
        "",
        "## 公寓索引",
        "",
        "| 公寓 | 区域 | 邮编 | 最近车站 | 官网展示起价 |",
        "|---|---|---|---|---|",
    ]
    for item in residences:
        price = f"{item.starting_price}/week" if item.starting_price else "未展示"
        lines.append(
            f"| [{md_escape(item.name)}](#{anchor(item.name)}) | "
            f"{md_escape(item.zone) or '未注明'} | "
            f"{md_escape(item.postcode) or '未注明'} | "
            f"{md_escape(item.station) or '未注明'} | {md_escape(price)} |"
        )

    for item in residences:
        lines.extend(
            [
                "",
                f"## {item.name}",
                "",
                f"- 官网公寓 ID：{item.residence_id}",
                f"- 城市：London",
                f"- 地址：{item.address or '官网未注明'}",
                f"- 区域：{item.zone or '官网未注明'}",
                f"- 邮编：{item.postcode or '官网未注明'}",
                f"- 最近车站：{item.station or '官网未注明'}",
                f"- 交通线路：{item.transport or '官网未注明'}",
                f"- 官网展示起价：{price_text(item)}",
                f"- 页面标签：{join_or_unknown(item.tags)}",
                f"- 设施：{join_or_unknown(item.facilities)}",
                f"- 来源：{item.url}",
            ]
        )
        if item.nearby_universities:
            lines.extend(["", "### 附近学校/大学", ""])
            lines.extend(f"- {value}" for value in item.nearby_universities)
        if item.nearby_attractions:
            lines.extend(["", "### 附近地标与生活配套", ""])
            lines.extend(f"- {value}" for value in item.nearby_attractions)
        lines.extend(
            [
                "",
                "### 检索提示",
                "",
                f"- 可用名称：{item.name}；{item.slug}",
                "- 价格和库存：不要依据本节推断实时可订状态，应调用结构化报价/库存 Tool。",
            ]
        )

    lines.extend(
        [
            "",
            "## 数据说明",
            "",
            "- 距离和通勤时间均为官网页面提供的近似值，可能受路线、交通和步行速度影响。",
            "- “官网展示起价”来自列表页，只用于粗略了解价格层级。",
            "- 官网个别地图坐标与页面地址存在明显不一致，因此本文档不输出未经校验的经纬度；位置检索以地址、邮编和车站为准。",
            "- 本文档未固化详情页动态加载的 Available Rooms，避免知识库产生过期库存与报价。",
            "- 更新知识库时应重新抓取全部详情页，并以最新文档替换旧版本，避免重复召回。",
            "",
        ]
    )
    return "\n".join(lines)


def anchor(name: str) -> str:
    return re.sub(r"[^a-z0-9\u4e00-\u9fff-]+", "-", name.casefold()).strip("-")


def price_text(item: Residence) -> str:
    if item.starting_price:
        return f"{item.starting_price}/week（列表页快照，非指定日期报价）"
    return "官网未展示"


def join_or_unknown(values: list[str]) -> str:
    return "、".join(values) if values else "官网未注明"


def download(url: str, destination: Path) -> None:
    request = Request(
        url,
        headers={
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 Chrome/126 Safari/537.36"
            )
        },
    )
    with urlopen(request, timeout=30) as response:
        destination.write_bytes(response.read())


def prepare_sources(args: argparse.Namespace) -> tuple[Path, Path]:
    if args.listing_html and args.details_dir:
        return args.listing_html, args.details_dir

    cache_dir: Path = args.cache_dir
    details_dir = cache_dir / "details"
    details_dir.mkdir(parents=True, exist_ok=True)
    listing_path = cache_dir / "residences.html"
    if args.refresh or not listing_path.exists():
        download(LISTING_URL, listing_path)

    listing_html = listing_path.read_text(encoding="utf-8", errors="replace")
    urls = sorted(set(DETAIL_URL_PATTERN.findall(listing_html)))
    for index, url in enumerate(urls, start=1):
        slug = url.rstrip("/").rsplit("/", 1)[-1]
        output = details_dir / f"{slug}.html"
        if args.refresh or not output.exists():
            print(f"[{index}/{len(urls)}] {slug}", file=sys.stderr)
            download(url, output)
            time.sleep(args.delay)
    return listing_path, details_dir


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listing-html", type=Path)
    parser.add_argument("--details-dir", type=Path)
    parser.add_argument("--cache-dir", type=Path, default=Path(".cache/londonist"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--snapshot-date", default=date.today().isoformat())
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--delay", type=float, default=0.25)
    args = parser.parse_args()

    if bool(args.listing_html) != bool(args.details_dir):
        parser.error("--listing-html and --details-dir must be supplied together")

    listing_path, details_dir = prepare_sources(args)
    listing_html = listing_path.read_text(encoding="utf-8", errors="replace")
    residences_by_slug = parse_listing(listing_html)
    if not residences_by_slug:
        raise RuntimeError("No residence cards found in listing HTML")

    missing: list[str] = []
    for slug, residence in residences_by_slug.items():
        detail_path = details_dir / f"{slug}.html"
        if not detail_path.exists():
            missing.append(slug)
            continue
        merge_detail(
            residence, detail_path.read_text(encoding="utf-8", errors="replace")
        )
    if missing:
        raise RuntimeError(f"Missing {len(missing)} detail pages: {', '.join(missing)}")

    residences = sorted(residences_by_slug.values(), key=lambda item: item.name.casefold())
    markdown = render_markdown(residences, args.snapshot_date)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(markdown, encoding="utf-8")
    print(f"Wrote {len(residences)} residences to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

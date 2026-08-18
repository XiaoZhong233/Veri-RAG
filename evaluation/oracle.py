"""Independent live-data oracle for Londonist evaluation.

The oracle reads authenticated management APIs into memory and computes eligible residences
without sending price tiers to the chat model, judges, result files, or reports.
"""
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta


PLACE_ALIASES = {
    "ucl": {"ucl", "universitycollegelondon"},
    "lse": {"lse", "londonschoolofeconomics"},
    "kcl": {"kcl", "kingscollegelondon"},
    "qmul": {"qmul", "queenmaryuniversityoflondon"},
    "ic": {"ic", "imperialcollegelondon", "imperialcollege"},
}


def _get(base_url, path, token, timeout):
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        headers={"Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
            json.JSONDecodeError) as error:
        raise RuntimeError(f"Oracle request failed for {path}: {error}") from error
    if body.get("code") != 200:
        raise RuntimeError(f"Oracle request failed for {path}: {body.get('message')}")
    return body.get("data")


def _page(base_url, path, token, timeout, size=200):
    rows = []
    page = 1
    while True:
        separator = "&" if "?" in path else "?"
        data = _get(base_url, f"{path}{separator}page={page}&size={size}", token, timeout)
        records = (data or {}).get("records") or []
        rows.extend(records)
        total = int((data or {}).get("total") or 0)
        if not records or len(rows) >= total:
            return rows
        page += 1


def capture_live_snapshot(base_url, token, timeout):
    residences = _page(
        base_url, "/api/residences?includeInactive=false", token, timeout)
    details = {}
    for residence in residences:
        residence_id = residence.get("id")
        detail = _get(base_url, f"/api/residence-details/{residence_id}", token, timeout)
        details[residence_id] = detail or {}
    offers = _page(base_url, "/api/room-offers/page", token, timeout, size=500)
    inventory_updates = [
        str(offer.get("inventoryUpdatedAt")) for offer in offers
        if offer.get("inventoryUpdatedAt")
    ]
    detail_updates = [
        str(detail.get("detailUpdatedAt")) for detail in details.values()
        if detail.get("detailUpdatedAt")
    ]
    return {
        "residences": residences,
        "details": details,
        "offers": offers,
        "summary": {
            "residenceCount": len(residences),
            "roomOfferCount": len(offers),
            "inventoryAsOf": max(inventory_updates) if inventory_updates else None,
            "detailAsOf": max(detail_updates) if detail_updates else None,
        },
    }


def _canonical(value):
    return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


def _place_matches(name, query):
    name_value, query_value = _canonical(name), _canonical(query)
    if not query_value:
        return True
    if name_value in query_value or query_value in name_value:
        return True
    return any(
        any(alias in query_value for alias in aliases)
        and any(alias in name_value for alias in aliases)
        for aliases in PLACE_ALIASES.values()
    )


def _parse_date(value):
    return date.fromisoformat(value) if value else None


def _tier_price(offer, stay_weeks):
    prices = []
    for tier in offer.get("priceTiers") or []:
        minimum = int(tier.get("minWeeks") or 0)
        maximum = tier.get("maxWeeks")
        if stay_weeks is None or (
                stay_weeks >= minimum
                and (maximum is None or stay_weeks <= int(maximum))):
            if tier.get("weeklyPrice") is not None:
                prices.append(float(tier["weeklyPrice"]))
    return min(prices) if prices else None


def eligible_residences(snapshot, query):
    """Return the complete eligible residence-name set for a structured test query."""
    start_from = _parse_date(query.get("startDateFrom"))
    start_to = _parse_date(query.get("startDateTo")) or start_from
    stay_weeks = query.get("stayWeeks")
    max_price = query.get("maxWeeklyPrice")
    include_sold_out = bool(query.get("includeSoldOut"))
    root_types = {_canonical(value) for value in query.get("rootTypes") or []}
    city = _canonical(query.get("city"))
    requested_name = _canonical(query.get("residenceKeyword"))
    nearby_query = query.get("nearbyPlaceKeyword")
    max_minutes = query.get("maxTravelMinutes")

    residences = {}
    for residence in snapshot["residences"]:
        if city and _canonical(residence.get("city")) != city:
            continue
        searchable = " ".join(str(residence.get(key) or "") for key in (
            "name", "sourceId", "address", "station"))
        if requested_name and requested_name not in _canonical(searchable):
            continue
        if nearby_query:
            nearby = (snapshot["details"].get(residence.get("id"), {})
                      .get("nearbyPlaces") or [])
            matching_places = [place for place in nearby
                               if _place_matches(place.get("placeName"), nearby_query)]
            if max_minutes is not None:
                matching_places = [place for place in matching_places
                                   if place.get("maxMinutes") is not None
                                   and int(place["maxMinutes"]) <= int(max_minutes)]
            if not matching_places:
                continue
        residences[residence.get("id")] = residence

    matched = set()
    for offer in snapshot["offers"]:
        residence = residences.get(offer.get("residenceId"))
        if not residence:
            continue
        status = offer.get("inventoryStatus")
        if not include_sold_out and status == "SOLD_OUT":
            continue
        if root_types and _canonical(offer.get("rootType")) not in root_types:
            continue
        earliest = _parse_date(offer.get("earliestStartDate"))
        latest = _parse_date(offer.get("latestEndDate"))
        if start_from:
            matched_start = max(start_from, earliest)
            if matched_start > start_to:
                continue
            if stay_weeks is not None and matched_start + timedelta(weeks=stay_weeks) > latest:
                continue
            if stay_weeks is None and matched_start > latest:
                continue
        effective_price = _tier_price(offer, stay_weeks)
        if stay_weeks is not None and effective_price is None:
            continue
        if max_price is not None and (
                effective_price is None or effective_price > float(max_price)):
            continue
        matched.add(str(residence.get("name")))
    return sorted(matched)

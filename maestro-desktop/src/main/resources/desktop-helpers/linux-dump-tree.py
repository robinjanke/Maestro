#!/usr/bin/env python3
"""Dump AT-SPI hierarchy for a Linux process as JSON (stdout).

Usage: linux-dump-tree.py <pid>
"""
from __future__ import annotations

import json
import sys


def load_atspi():
    try:
        import gi

        gi.require_version("Atspi", "2.0")
        from gi.repository import Atspi

        Atspi.init()
        return Atspi
    except Exception as first:
        try:
            import pyatspi  # type: ignore

            return pyatspi
        except Exception as second:
            raise RuntimeError(
                f"AT-SPI unavailable (gi.Atspi: {first}; pyatspi: {second})"
            ) from second


def role_name(Atspi, accessible) -> str:
    try:
        role = accessible.get_role()
        if hasattr(Atspi, "role_name"):
            return str(Atspi.role_name(role)).lower()
        return str(role).lower().replace("role_", "")
    except Exception:
        return ""


def bounds_str(accessible) -> str:
    try:
        comp = accessible.get_component()
        if comp is None:
            return "[0,0][0,0]"
        ext = comp.get_extents(0)  # ATSPI_COORD_TYPE_SCREEN
        left = int(ext.x)
        top = int(ext.y)
        right = left + int(ext.width)
        bottom = top + int(ext.height)
        return f"[{left},{top}][{right},{bottom}]"
    except Exception:
        return "[0,0][0,0]"


def convert(Atspi, accessible, depth: int = 0):
    if depth > 40 or accessible is None:
        return None
    try:
        name = accessible.get_name() or ""
    except Exception:
        name = ""
    try:
        # Flutter Semantics(identifier) often maps to accessible id / attributes.
        aid = ""
        if hasattr(accessible, "get_accessible_id"):
            aid = accessible.get_accessible_id() or ""
        if not aid and hasattr(accessible, "get_attributes"):
            attrs = accessible.get_attributes() or {}
            aid = attrs.get("id") or attrs.get("identifier") or ""
    except Exception:
        aid = ""

    role = role_name(Atspi, accessible)
    clickable_roles = {
        "push_button",
        "button",
        "link",
        "check_box",
        "radio_button",
        "menu_item",
        "toggle_button",
        "page_tab",
        "entry",
        "text",
        "combo_box",
        "list_item",
    }
    children = []
    try:
        count = accessible.get_child_count()
        for i in range(count):
            child = accessible.get_child_at_index(i)
            converted = convert(Atspi, child, depth + 1)
            if converted is not None:
                children.append(converted)
    except Exception:
        pass

    try:
        states = set()
        state_set = accessible.get_state_set()
        if state_set is not None and hasattr(state_set, "get_states"):
            for st in state_set.get_states():
                states.add(str(st).lower())
        enabled = "state_enabled" in states or "enabled" in "".join(states)
        focused = "state_focused" in states or "focused" in "".join(states)
        selected = "state_selected" in states or "selected" in "".join(states)
    except Exception:
        enabled, focused, selected = True, False, False

    return {
        "id": aid or None,
        "text": name,
        "role": role.replace("role_", ""),
        "bounds": bounds_str(accessible),
        "enabled": bool(enabled),
        "focused": bool(focused),
        "selected": bool(selected),
        "clickable": role.replace("role_", "") in clickable_roles,
        "children": children,
    }


def find_app(Atspi, pid: int):
    desktop = Atspi.get_desktop(0)
    count = desktop.get_child_count()
    for i in range(count):
        app = desktop.get_child_at_index(i)
        try:
            if int(app.get_process_id()) == pid:
                return app
        except Exception:
            continue
    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: linux-dump-tree.py <pid>", file=sys.stderr)
        return 2
    pid = int(sys.argv[1])
    Atspi = load_atspi()
    app = find_app(Atspi, pid)
    if app is None:
        print(f"No AT-SPI application for PID {pid}", file=sys.stderr)
        return 2
    payload = {"pid": pid, "root": convert(Atspi, app)}
    print(json.dumps(payload, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

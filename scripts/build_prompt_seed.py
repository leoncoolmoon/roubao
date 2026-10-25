#!/usr/bin/env python3
"""
Build prompt seed source + prompts_seed.db from the current v13 device database.

Usage:
  python scripts/build_prompt_seed.py \
    --db ../dist/device-v13c.db \
    --src prompt-seed \
    --out app/src/main/assets/prompts_seed.db \
    --profile release

The device DB is only a content baseline. enabled/source/executable are normalized;
feedback release profile keeps one no-dependency example.
"""

import argparse
import json
import os
import shutil
import sqlite3
import time

# id -> (dependence, risk, executable)
TOOL_META = {
    "tap": ([], "low", 1),
    "swipe": ([], "low", 1),
    "type": ([], "low", 1),
    "press_key": (["adb"], "low", 1),
    "open_app": ([], "low", 1),
    "deep_link": ([], "low", 1),
    "search_apps": ([], "low", 1),
    "clipboard": ([], "low", 0),
    "http": ([], "low", 0),
    "screenshot": ([], "low", 0),
    "shell": (["adb"], "medium", 1),
}

SKILL_META = {
    "shell_calc": (["adb"], "low", 0),
}

PROVIDERS = [
    {"id": "accessibility", "display_name": "无障碍", "level": "user", "priority": 10},
    {"id": "accessibility-screenshot", "display_name": "无障碍截图", "level": "user", "priority": 20},
    {"id": "mediaprojection", "display_name": "MediaProjection", "level": "user", "priority": 30},
    {"id": "shizuku-adb", "display_name": "Shizuku ADB", "level": "adb", "priority": 10},
    {"id": "su-user", "display_name": "su-user", "level": "adb", "priority": 20},
    {"id": "shizuku-root", "display_name": "Shizuku Root", "level": "root", "priority": 10},
    {"id": "su", "display_name": "su", "level": "root", "priority": 20},
]

PACKS = [
    {"id": "basic", "display_name": "基础包（仅无障碍）", "level": "user",
     "description": "只用无障碍：UI 树 + 截图 + 手势", "sort_order": 10},
    {"id": "standard", "display_name": "标准包（Shizuku / su-user）", "level": "adb",
     "description": "可用 adb 级 shell；system/file 优先 shell", "sort_order": 20},
    {"id": "advanced", "display_name": "高级包（Root）", "level": "root",
     "description": "可用 root 级工具；高风险动作需确认", "sort_order": 30},
]

RELEASE_FEEDBACK = "同一操作连续失败两次后必须更换策略，不要重复点击同一位置。"


def rows(conn, sql, args=()):
    cur = conn.execute(sql, args)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, r)) for r in cur.fetchall()]


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def build_source(db_path, src_dir, profile):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row

    shutil.rmtree(src_dir, ignore_errors=True)
    os.makedirs(src_dir, exist_ok=True)

    agents = rows(conn, "select * from agent_prompts order by id")
    for agent in agents:
        write_text(os.path.join(src_dir, "agents", "base", f"{agent['id']}.md"), agent["prompt"])

    overlays = rows(conn, "select * from agent_pack_overlays order by pack_id, agent_id")
    for overlay in overlays:
        write_text(
            os.path.join(src_dir, "agents", "overlays", overlay["pack_id"], f"{overlay['agent_id']}.md"),
            overlay["prompt"],
        )

    items = rows(conn, "select * from tool_skill_prompts order by category, id")
    for item in items:
        meta = TOOL_META.get(item["id"]) if item["category"] == "tool" else SKILL_META.get(item["id"])
        dependence, risk, executable = meta if meta else ([], "low", 0)
        obj = {
            "id": item["id"],
            "kind": item["category"],
            "name": item["name"],
            "display_name": item["display_name"],
            "description": item["description"],
            "prompt": item["prompt"],
            "usage": "",
            "dependence": dependence,
            "risk_level": risk,
            "requires_confirmation": 1 if risk == "high" else 0,
            "executable": executable,
            "params": json.loads(item["params_json"] or "[]"),
            "keywords": json.loads(item["keywords_json"] or "[]"),
            "related_apps": json.loads(item["related_apps_json"] or "[]"),
            "prompt_hint": item["prompt_hint"],
            "domain": item["domain"],
        }
        folder = "tools" if item["category"] == "tool" else "skills"
        write_json(os.path.join(src_dir, "items", folder, f"{item['id']}.json"), obj)

    write_json(os.path.join(src_dir, "providers.json"), PROVIDERS)
    write_json(os.path.join(src_dir, "packs.json"), PACKS)
    write_json(os.path.join(src_dir, "meta.json"), {
        "schema_version": 19,
        "seed_version": 1,
        "default_pack_id": "standard",
        "profile": profile,
    })

    feedback = rows(conn, "select * from user_feedback order by id")
    write_json(os.path.join(src_dir, "feedback", "release", "example.json"), [{
        "text": RELEASE_FEEDBACK,
        "kind": "complaint",
        "enabled": True,
        "dependence": [],
    }])
    for fb in feedback:
        write_json(
            os.path.join(src_dir, "feedback", "test", f"feedback-{fb['id']}.json"),
            {
                "text": fb["text"],
                "kind": fb["kind"],
                "enabled": bool(fb["enabled"]),
                "dependence": json.loads(fb["dependence_json"] or "[]"),
            },
        )

    write_json(os.path.join(src_dir, "legacy-map.json"), {
        "note": "id -> {dependence, risk, executable}; generated from v13 device DB",
        **{
            item["id"]: {
                "dependence": (TOOL_META.get(item["id"]) if item["category"] == "tool" else SKILL_META.get(item["id"]))[0]
                if (TOOL_META.get(item["id"]) if item["category"] == "tool" else SKILL_META.get(item["id"]))
                else [],
            }
            for item in items
        },
    })
    conn.close()


def build_db(db_path, out_path, profile):
    if os.path.exists(out_path):
        os.remove(out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    src = sqlite3.connect(db_path)
    out = sqlite3.connect(out_path)
    out.execute("PRAGMA foreign_keys=OFF")
    out.execute("ATTACH DATABASE ? AS src", (db_path,))

    tables = [
        "agent_prompts",
        "tool_skill_prompts",
        "user_feedback",
        "prompt_packs",
        "agent_pack_overlays",
        "action_audit",
        "meta",
    ]
    for table in tables:
        row = src.execute(
            "select sql from sqlite_master where type='table' and name=?", (table,)
        ).fetchone()
        if not row or not row[0]:
            raise SystemExit(f"missing table {table} in {db_path}")
        out.execute(row[0])
        out.execute(f"INSERT INTO {table} SELECT * FROM src.{table}")

    out.execute("UPDATE agent_prompts SET enabled=1")
    out.execute("UPDATE tool_skill_prompts SET enabled=1")
    out.execute("UPDATE tool_skill_prompts SET enabled=0 WHERE id = 'music_player'")
    for item_id, (dependence, risk, executable) in TOOL_META.items():
        out.execute(
            "UPDATE tool_skill_prompts SET dependence_json=?, executable=?, source='builtin' WHERE id=?",
            (json.dumps(dependence), executable, item_id),
        )
    for item_id, (dependence, risk, executable) in SKILL_META.items():
        out.execute(
            "UPDATE tool_skill_prompts SET dependence_json=?, executable=?, source='builtin' WHERE id=?",
            (json.dumps(dependence), executable, item_id),
        )

    if profile == "release":
        out.execute("DELETE FROM user_feedback")
        out.execute(
            "INSERT INTO user_feedback(text, created_at, enabled, kind, dependence_json) "
            "VALUES (?, ?, 1, 'complaint', '[]')",
            (RELEASE_FEEDBACK, int(time.time() * 1000)),
        )
    else:
        out.execute("UPDATE user_feedback SET enabled=1")

    out.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('schema_version','19')")
    out.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('seed_version','1')")
    out.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('seed_built_at',?)", (str(int(time.time())),))
    out.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('default_pack_id','standard')")
    out.execute("INSERT OR REPLACE INTO meta(key,value) VALUES ('profile',?)", (profile,))

    out.execute("PRAGMA user_version=19")
    out.commit()
    out.execute("DETACH DATABASE src")
    out.execute("VACUUM")
    out.close()
    src.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True)
    parser.add_argument("--src", default="prompt-seed")
    parser.add_argument("--out", default="app/src/main/assets/prompts_seed.db")
    parser.add_argument("--profile", choices=["release", "test"], default="release")
    args = parser.parse_args()

    build_source(args.db, args.src, args.profile)
    build_db(args.db, args.out, args.profile)
    print(f"seed source: {args.src}")
    print(f"seed db: {args.out} ({os.path.getsize(args.out)} bytes)")


if __name__ == "__main__":
    main()

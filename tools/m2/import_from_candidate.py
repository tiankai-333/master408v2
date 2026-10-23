"""master408_candidate（只读）→ master408_m2_candidate 数据导入（M2 初始版本 v1）。

- 源库只读 SELECT；仅写 m2_candidate；
- 生成 import_batch('m2-import-v1') + 每题 v1 不可变版本（edit_source=import）；
- 幂等：按 source_ref 已存在跳过；重导保护——m2 库中 edited_manual=1 的题永不
  覆盖（人工编辑历史保留）；
- 凭据走环境变量 M3_DB_USER / M3_DB_PASSWORD（与 M3 导出同一本地忽略文件口径）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys

import pymysql


def sha(text: str) -> str:
    return hashlib.sha256((text or "").encode("utf-8")).hexdigest()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="candidate → m2_candidate 导入")
    ap.add_argument("--db-url", required=True)
    ap.add_argument("--out-db", default="master408_m2_candidate")
    args = ap.parse_args(argv)

    import os
    import re
    db_user = os.environ.get("M3_DB_USER")
    db_password = os.environ.get("M3_DB_PASSWORD")
    if not db_user or db_password is None:
        print("missing credentials: set M3_DB_USER / M3_DB_PASSWORD", file=sys.stderr)
        return 2
    m = re.match(r"jdbc:mysql://([^/:]+)(?::(\d+))?/(\w+)", args.db_url)
    if not m:
        print("cannot parse db url", file=sys.stderr)
        return 2
    host, port, src_db = m.group(1), int(m.group(2) or 3306), m.group(3)

    conn = pymysql.connect(host=host, port=port, user=db_user,
                           password=db_password, charset="utf8mb4",
                           database=src_db, cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT q.id, q.question_type, q.source_year, q.source_question_no, "
                "       q.stem_html, q.analysis_html, q.correct, q.answer_text, "
                "       q.assets_json, q.source_ref, q.source_name, "
                "       s.name AS subject_name "
                "FROM question q LEFT JOIN subject s ON s.id = q.subject_id "
                "ORDER BY q.source_year, CAST(q.source_question_no AS UNSIGNED)")
            rows = cur.fetchall()
    finally:
        conn.close()

    # paper 及其 question_ids 需要跨库 id 映射：源 question.id → source_ref → m2 id
    conn = pymysql.connect(host=host, port=port, user=db_user,
                           password=db_password, charset="utf8mb4",
                           database=src_db, cursorclass=pymysql.cursors.DictCursor)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id, source_ref FROM question")
            src_id_to_ref = {r["id"]: r["source_ref"] for r in cur.fetchall()}
            cur.execute("SELECT id, name, paper_type, source_year, description,"
                        " question_ids, question_count, source_sha256, source_url,"
                        " warning_count, warnings_json FROM paper ORDER BY source_year")
            src_papers = cur.fetchall()
    finally:
        conn.close()

    m2 = pymysql.connect(host=host, port=port, user=db_user,
                         password=db_password, charset="utf8mb4",
                         database=args.out_db, autocommit=False,
                         cursorclass=pymysql.cursors.DictCursor)
    imported = skipped_protected = skipped_existing = 0
    try:
        with m2.cursor() as cur:
            cur.execute(
                "INSERT INTO import_batch (batch_tag, source_type, started_at, note) "
                "VALUES ('m2-import-v1', 'm2_import', NOW(), "
                "'从 master408_candidate 只读导入 470 题，v1=import 版本') "
                "ON DUPLICATE KEY UPDATE note=VALUES(note)")
            cur.execute("SELECT id FROM import_batch WHERE batch_tag='m2-import-v1'")
            batch_id = cur.fetchone()["id"]
            cur.execute("SELECT id, name FROM subject")
            subjects = {r["name"]: r["id"] for r in cur.fetchall()}
            cur.execute("SELECT source_ref FROM question")
            existing_refs = {r["source_ref"] for r in cur.fetchall()}
            cur.execute("SELECT source_ref FROM question WHERE edited_manual=1")
            protected = {r["source_ref"] for r in cur.fetchall()}

            for r in rows:
                ref = r["source_ref"]
                if ref in existing_refs:
                    skipped_existing += 1
                    continue
                if ref in protected:
                    skipped_protected += 1
                    continue
                subj = r["subject_name"] or "未知学科"
                if subj not in subjects:
                    cur.execute("INSERT INTO subject (name) VALUES (%s)", (subj,))
                    subjects[subj] = cur.lastrowid
                stem = r["stem_html"] or ""
                analysis = r["analysis_html"]
                correct = r["correct"]
                content_sha = sha(stem + "|" + (analysis or "") + "|" + (correct or ""))
                cur.execute(
                    "INSERT INTO question (question_type, subject_id, source_name,"
                    " source_year, source_question_no, source_ref, stem_html,"
                    " analysis_html, correct, answer_text, display_ready,"
                    " license_tag, batch_id, assets_json, current_version_no,"
                    " edited_manual)"
                    " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,'crawler-review',"
                    "%s,%s,1,0)",
                    (r["question_type"], subjects[subj], r["source_name"],
                     r["source_year"], str(r["source_question_no"]), ref,
                     stem, analysis, correct, r["answer_text"], batch_id,
                     r["assets_json"]))
                qid = cur.lastrowid
                cur.execute(
                    "INSERT INTO question_version (question_id, version_no,"
                    " edit_source, stem_html, analysis_html, correct, answer_text,"
                    " assets_json, source_ref, source_name, source_year,"
                    " source_question_no, content_sha256)"
                    " VALUES (%s, 1, 'import', %s, %s, %s, %s, %s, %s, %s, %s, %s,"
                    " %s)",
                    (qid, stem, analysis, correct, r["answer_text"],
                     r["assets_json"], ref, r["source_name"], r["source_year"],
                     str(r["source_question_no"]), content_sha))
                version_id = cur.lastrowid
                cur.execute(
                    "UPDATE question SET current_version_id=%s,"
                    " current_version_no=1 WHERE id=%s", (version_id, qid))
                imported += 1
        # paper：question_ids 按源 id→ref→m2 id 映射重建；幂等按 name upsert
        m2_ref_to_id = {}
        with m2.cursor() as cur:
            cur.execute("SELECT id, source_ref FROM question")
            for r in cur.fetchall():
                m2_ref_to_id[r["source_ref"]] = r["id"]
        for sp in src_papers:
            old_ids = json.loads(sp["question_ids"])
            new_ids = []
            ok = True
            for oid in old_ids:
                ref = src_id_to_ref.get(oid)
                nid = m2_ref_to_id.get(ref)
                if nid is None:
                    ok = False
                    break
                new_ids.append(nid)
            if not ok:
                print(f"[WARN] paper {sp['name']}: question id 映射不全，跳过",
                      file=sys.stderr)
                continue
            with m2.cursor() as cur:
                cur.execute(
                    "INSERT INTO paper (name, paper_type, source_year, description,"
                    " question_ids, question_count, batch_id, source_sha256,"
                    " source_url, warning_count, warnings_json)"
                    " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)"
                    " ON DUPLICATE KEY UPDATE question_ids=VALUES(question_ids),"
                    " question_count=VALUES(question_count), batch_id=VALUES(batch_id)",
                    (sp["name"], sp["paper_type"], sp["source_year"],
                     sp["description"], json.dumps(new_ids), len(new_ids),
                     batch_id, sp["source_sha256"], sp["source_url"],
                     sp["warning_count"], sp["warnings_json"]))
        m2.commit()
    except Exception:
        m2.rollback()
        raise
    finally:
        m2.close()
    print(f"[OK] imported={imported} existing={skipped_existing} "
          f"protected={skipped_protected}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

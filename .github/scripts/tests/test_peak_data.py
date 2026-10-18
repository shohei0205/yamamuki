"""peak_data.py の単体テスト。通信はせず、Release の取得は差し替える。

python3 -m unittest discover -s .github/scripts/tests（リポジトリの直下で実行）
"""

import gzip
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import peak_data as pd  # noqa: E402

STABLE_OLD = "osm-peaks-20261007T071401Z-37585754546-1"
STABLE_NEW = "osm-peaks-20261101T032300Z-40000000000-1"
DEV = "osm-peaks-dev-20261009T095556Z-37914319482-1"


def manifest_bytes(tag, count=14035, schema=5, data_schema=5):
    version = tag.removeprefix("osm-peaks-dev-") if tag.startswith("osm-peaks-dev-") else tag.removeprefix("osm-peaks-")
    manifest = {
        "schemaVersion": schema, "dataSchemaVersion": data_schema, "name": "山頂", "version": version,
        "fileName": "osm-peaks.json.gz",
        "downloadUrl": f"https://github.com/shohei0205/yamamuki-data/releases/download/{tag}/osm-peaks.json.gz",
        "sha256": "0" * 64, "sizeBytes": 505256, "pointCount": count,
    }
    if schema >= 6:
        # 版 6 は形式の名前が入り、件数と出典が metadata にまとまる。
        del manifest["pointCount"]
        manifest = {"schemaFormat": "yamamuki-manifest", **manifest, "dataSchemaFormat": "yamamuki-points",
                    "metadata": {"sources": [{"name": "osm-peaks", "license": "ODbL-1.0"}], "pointCount": count}}
    return (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()


class SiteTestCase(unittest.TestCase):
    """一時フォルダに site/data/osm-peaks/ を作り、stable が STABLE_OLD を指す状態から始める。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / pd.SITE_DIR / pd.MANIFESTS_NAME).mkdir(parents=True)
        data = manifest_bytes(STABLE_OLD)
        pd.manifest_path(self.root, STABLE_OLD).write_bytes(data)
        pd.write_pointer(self.root, {"schemaVersion": 1, "stable": pd.entry_for(STABLE_OLD, data)})
        self.releases = {
            STABLE_NEW: (manifest_bytes(STABLE_NEW, count=14100), {"html_url": "https://example.invalid/r", "body": ""}),
            DEV: (manifest_bytes(DEV, count=14038), {"prerelease": True, "html_url": "https://example.invalid/d"}),
        }
        patcher = mock.patch.object(pd, "release_manifest", side_effect=lambda tag: self.releases[tag])
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.tmp.cleanup)

    def pointer(self):
        return pd.read_pointer(self.root)


class ValidatePointerTest(unittest.TestCase):
    def entry(self, tag):
        return {"manifestUrl": f"{pd.MANIFEST_URL_PREFIX}{tag}.json", "manifestSha256": "a" * 64}

    def test_accepts_stable_and_dev(self):
        tags = pd.validate_pointer({"schemaVersion": 1, "stable": self.entry(STABLE_OLD), "dev": self.entry(DEV)})
        self.assertEqual({"stable": STABLE_OLD, "dev": DEV}, tags)

    def test_rejects_url_outside_manifests(self):
        bad = {"manifestUrl": "https://shohei0205.github.io/yamamuki-data/peaks/manifest.json", "manifestSha256": "a" * 64}
        with self.assertRaises(pd.PeakDataError):
            pd.validate_pointer({"schemaVersion": 1, "stable": bad})

    def test_rejects_dev_release_in_stable(self):
        with self.assertRaises(pd.PeakDataError):
            pd.validate_pointer({"schemaVersion": 1, "stable": self.entry(DEV)})

    def test_requires_stable_and_known_keys(self):
        for pointer in ({"schemaVersion": 1, "dev": self.entry(DEV)},
                        {"schemaVersion": 1, "stable": self.entry(STABLE_OLD), "beta": self.entry(DEV)},
                        {"schemaVersion": 2, "stable": self.entry(STABLE_OLD)}):
            with self.subTest(pointer=pointer), self.assertRaises(pd.PeakDataError):
                pd.validate_pointer(pointer)


class ValidateManifestTest(unittest.TestCase):
    def test_stable_rejects_newer_format_but_dev_accepts(self):
        data = manifest_bytes(DEV, schema=6, data_schema=6)
        with self.assertRaises(pd.PeakDataError):
            pd.validate_manifest(manifest_bytes(STABLE_NEW, schema=6, data_schema=6), STABLE_NEW, "stable")
        self.assertEqual(6, pd.validate_manifest(data, DEV, "dev")["schemaVersion"])

    def test_reads_count_of_v6_from_metadata(self):
        manifest = pd.validate_manifest(manifest_bytes(DEV, count=14038, schema=6, data_schema=6), DEV, "dev")
        self.assertEqual(14038, pd.point_count(manifest))
        self.assertEqual(14035, pd.point_count(json.loads(manifest_bytes(STABLE_OLD))))

    def test_rejects_v6_of_other_formats_or_without_count(self):
        def broken(change):
            manifest = json.loads(manifest_bytes(DEV, schema=6, data_schema=6))
            change(manifest)
            return json.dumps(manifest).encode()

        for name, change in (("schemaFormat", lambda m: m.update(schemaFormat="other")),
                             ("dataSchemaFormat", lambda m: m.update(dataSchemaFormat="other")),
                             ("metadata なし", lambda m: m.pop("metadata")),
                             ("件数なし", lambda m: m["metadata"].pop("pointCount")),
                             ("件数が文字列", lambda m: m["metadata"].update(pointCount="14038")),
                             ("件数が真偽値", lambda m: m["metadata"].update(pointCount=True))):
            with self.subTest(name), self.assertRaises(pd.PeakDataError):
                pd.validate_manifest(broken(change), DEV, "dev")

    def test_rejects_version_not_matching_tag(self):
        with self.assertRaises(pd.PeakDataError):
            pd.validate_manifest(manifest_bytes(STABLE_OLD), STABLE_NEW, "stable")


class SwapTest(SiteTestCase):
    def test_stable_copies_manifest_and_points_to_it(self):
        result = pd.swap(self.root, "stable", STABLE_NEW, False)
        self.assertTrue(result["changed"])
        self.assertEqual(f"data/stable/{STABLE_NEW}", result["branch"])
        data = pd.manifest_path(self.root, STABLE_NEW).read_bytes()
        self.assertEqual(self.releases[STABLE_NEW][0], data)
        self.assertEqual(hashlib.sha256(data).hexdigest(), self.pointer()["stable"]["manifestSha256"])
        self.assertIn("+65", "\n".join(result["body"]))
        # 前の版のコピーは残す。
        self.assertTrue(pd.manifest_path(self.root, STABLE_OLD).exists())

    def test_same_version_does_nothing(self):
        self.releases[STABLE_OLD] = (manifest_bytes(STABLE_OLD), {})
        self.assertFalse(pd.swap(self.root, "stable", STABLE_OLD, False)["changed"])

    def test_stable_refuses_dev_release(self):
        with self.assertRaises(pd.PeakDataError):
            pd.swap(self.root, "stable", DEV, False)

    def test_dev_then_stable_with_remove_dev(self):
        pd.swap(self.root, "dev", DEV, False)
        self.assertEqual(DEV, pd.validate_pointer(self.pointer())["dev"])
        result = pd.swap(self.root, "stable", STABLE_NEW, True)
        self.assertNotIn("dev", self.pointer())
        self.assertIn("dev", "\n".join(result["body"]))

    def test_remove_dev(self):
        self.assertFalse(pd.swap(self.root, "remove-dev", "", False)["changed"])
        pd.swap(self.root, "dev", DEV, False)
        self.assertTrue(pd.swap(self.root, "remove-dev", "", False)["changed"])
        self.assertEqual(["schemaVersion", "stable"], list(self.pointer()))

    def test_refuses_to_overwrite_existing_copy(self):
        pd.manifest_path(self.root, STABLE_NEW).write_bytes(b"{}\n")
        with self.assertRaises(pd.PeakDataError):
            pd.swap(self.root, "stable", STABLE_NEW, False)

    def test_latest_tag_is_used_when_tag_is_empty(self):
        with mock.patch.object(pd, "latest_tag", return_value=STABLE_NEW) as latest:
            pd.swap(self.root, "stable", "", False)
        latest.assert_called_once_with("stable")


class CheckDataTest(unittest.TestCase):
    def check(self, document):
        body = gzip.compress(json.dumps(document).encode())
        manifest = {"downloadUrl": "https://example.invalid/osm-peaks.json.gz", "sizeBytes": len(body),
                    "sha256": hashlib.sha256(body).hexdigest()}
        with mock.patch.object(pd, "http_get", return_value=body):
            return pd.check_data(manifest, DEV)

    def test_counts_array_of_v5_and_points_of_v6(self):
        self.assertEqual(2, self.check([{"osmId": 1}, {"osmId": 2}]))
        self.assertEqual(3, self.check({"schemaFormat": "yamamuki-points", "schemaVersion": 6, "metadata": {},
                                        "points": [{"id": "osm:1"}, {"id": "osm:2"}, {"id": "gsi-vector:3"}]}))

    def test_rejects_document_without_points(self):
        for document in ({"schemaVersion": 6}, {"points": {}}, "文字列"):
            with self.subTest(document=document), self.assertRaises(pd.PeakDataError):
                self.check(document)


class SwapV6Test(SiteTestCase):
    def test_dev_accepts_v6_and_compares_count_with_v5(self):
        self.releases[DEV] = (manifest_bytes(DEV, count=14000, schema=6, data_schema=6), {"prerelease": True})
        result = pd.swap(self.root, "dev", DEV, False)
        self.assertTrue(result["changed"])
        body = "\n".join(result["body"])
        self.assertIn("14,000 件", body)
        self.assertIn("schemaVersion 6", body)
        self.assertEqual(DEV, pd.validate_pointer(self.pointer())["dev"])

    def test_stable_still_refuses_v6(self):
        self.releases[STABLE_NEW] = (manifest_bytes(STABLE_NEW, schema=6, data_schema=6), {})
        with self.assertRaises(pd.PeakDataError):
            pd.swap(self.root, "stable", STABLE_NEW, False)


class ReleasesTest(unittest.TestCase):
    def test_reads_every_page(self):
        pages = {1: [{"tag_name": f"gsi-peaks-dev-{i}"} for i in range(100)], 2: [{"tag_name": STABLE_OLD}]}
        with mock.patch.object(pd, "api", side_effect=lambda path: pages[int(path.rsplit("=", 1)[1])]) as api:
            listing = pd.releases()
        self.assertEqual(101, len(listing))
        self.assertEqual(STABLE_OLD, listing[-1]["tag_name"])
        self.assertEqual(2, api.call_count)

    def test_gives_up_after_max_pages(self):
        with mock.patch.object(pd, "api", return_value=[{}] * 100), self.assertRaises(pd.PeakDataError):
            pd.releases(max_pages=2)


class LatestTagTest(unittest.TestCase):
    def test_picks_newest_of_the_channel(self):
        listing = [
            {"tag_name": STABLE_OLD, "published_at": "2026-10-07T07:20:00Z"},
            {"tag_name": STABLE_NEW, "published_at": "2026-11-01T03:40:00Z"},
            {"tag_name": DEV, "prerelease": True, "published_at": "2026-12-01T00:00:00Z"},
            {"tag_name": "osm-peaks-20261201T000000Z-1-1", "draft": True, "published_at": None},
            {"tag_name": "peaks-20261007T084736Z-37595744056-1", "published_at": "2026-12-02T00:00:00Z"},
            {"tag_name": "gsi-peaks-20261202T000000Z-1-1", "published_at": "2026-12-03T00:00:00Z"},
            {"tag_name": "gsi-peaks-dev-20261202T000000Z-1-1", "prerelease": True, "published_at": "2026-12-03T00:00:00Z"},
        ]
        with mock.patch.object(pd, "releases", return_value=listing):
            self.assertEqual(STABLE_NEW, pd.latest_tag("stable"))
            self.assertEqual(DEV, pd.latest_tag("dev"))


class CheckHelpersTest(unittest.TestCase):
    def test_manifest_changes_separates_additions(self):
        prefix = "site/data/osm-peaks/manifests/"
        added, others = pd.manifest_changes(
            f"A\t{prefix}{STABLE_NEW}.json\nM\t{prefix}{STABLE_OLD}.json\nD\t{prefix}x.json\nM\tsite/data/osm-peaks/current.json\n")
        self.assertEqual([f"{prefix}{STABLE_NEW}.json"], added)
        self.assertEqual(2, len(others))

    def test_count_drop(self):
        self.assertEqual(0.0, pd.count_drop(14000, 14100))
        self.assertAlmostEqual(0.1, pd.count_drop(14000, 12600))
        self.assertEqual(0.0, pd.count_drop(0, 10))


if __name__ == "__main__":
    unittest.main()

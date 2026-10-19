"""peak_data.py の単体テスト。通信はせず、Release の取得は差し替える。

python3 -m unittest discover -s .github/scripts/tests（リポジトリの直下で実行）
"""

import gzip
import hashlib
import json
import subprocess
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
GSI_DEV = "gsi-peaks-dev-20261018T115950Z-39125416076-1"
GSI_STABLE = "gsi-peaks-20261101T000000Z-40000000001-1"


def manifest_bytes(tag, count=14035, schema=5, data_schema=5):
    dataset, channel = pd.parse_tag(tag)
    version = tag.removeprefix(f"{dataset}-dev-" if channel == "dev" else f"{dataset}-")
    manifest = {
        "schemaVersion": schema, "dataSchemaVersion": data_schema, "name": "山頂", "version": version,
        "fileName": f"{dataset}.json.gz",
        "downloadUrl": f"https://github.com/shohei0205/yamamuki-data/releases/download/{tag}/{dataset}.json.gz",
        "sha256": "0" * 64, "sizeBytes": 505256, "pointCount": count,
    }
    if schema >= 6:
        # 版 6 は形式の名前が入り、件数と出典が metadata にまとまる。
        del manifest["pointCount"]
        manifest = {"schemaFormat": "yamamuki-manifest", **manifest, "dataSchemaFormat": "yamamuki-points",
                    "metadata": {"sources": [{"name": dataset, "license": "ODbL-1.0"}], "pointCount": count}}
    return (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode()


def entry(tag, sha="a" * 64):
    return {"manifestUrl": f"{pd.manifest_url_prefix(pd.parse_tag(tag)[0])}{tag}.json", "manifestSha256": sha}


def catalog(**datasets):
    return {"schemaVersion": 1, "datasets": {name.replace("_", "-"): value for name, value in datasets.items()}}


class SiteTestCase(unittest.TestCase):
    """一時フォルダに site/data/ を作り、カタログと current.json の stable が STABLE_OLD を指す状態から始める。"""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        for dataset in pd.DATASETS:
            (self.root / pd.manifests_dir(dataset)).mkdir(parents=True)
        data = manifest_bytes(STABLE_OLD)
        pd.manifest_path(self.root, STABLE_OLD).write_bytes(data)
        pd.write_catalog(self.root, catalog(osm_peaks={"stable": pd.entry_for(STABLE_OLD, data)}))
        pd.write_pointer(self.root, {"schemaVersion": 1, "stable": pd.entry_for(STABLE_OLD, data)})
        self.releases = {
            STABLE_NEW: (manifest_bytes(STABLE_NEW, count=14100), {"html_url": "https://example.invalid/r", "body": ""}),
            DEV: (manifest_bytes(DEV, count=14038), {"prerelease": True, "html_url": "https://example.invalid/d"}),
            GSI_DEV: (manifest_bytes(GSI_DEV, count=17225, schema=6, data_schema=6), {"prerelease": True}),
            GSI_STABLE: (manifest_bytes(GSI_STABLE, count=17300, schema=6, data_schema=6), {}),
        }
        patcher = mock.patch.object(pd, "release_manifest", side_effect=lambda tag: self.releases[tag])
        patcher.start()
        self.addCleanup(patcher.stop)
        self.addCleanup(self.tmp.cleanup)

    def catalog(self):
        return pd.read_catalog(self.root)

    def tags(self):
        return pd.validate_catalog(self.catalog())

    def pointer(self):
        return pd.read_pointer(self.root)


class ParseTagTest(unittest.TestCase):
    def test_reads_dataset_and_channel(self):
        self.assertEqual(("osm-peaks", "stable"), pd.parse_tag(STABLE_OLD))
        self.assertEqual(("osm-peaks", "dev"), pd.parse_tag(DEV))
        self.assertEqual(("gsi-peaks", "dev"), pd.parse_tag(GSI_DEV))
        self.assertEqual(("gsi-peaks", "stable"), pd.parse_tag(GSI_STABLE))

    def test_rejects_other_releases(self):
        for tag in ("peaks-20261007T084736Z-37595744056-1", "issue-60-gsi-json-20261014-3d694f2", "osm-peaks-dev-x"):
            with self.subTest(tag=tag):
                self.assertIsNone(pd.parse_tag(tag))


class ValidateCatalogTest(unittest.TestCase):
    def test_accepts_osm_and_gsi(self):
        tags = pd.validate_catalog(catalog(osm_peaks={"stable": entry(STABLE_OLD), "dev": entry(DEV)},
                                           gsi_peaks={"dev": entry(GSI_DEV)}))
        self.assertEqual({"osm-peaks": {"stable": STABLE_OLD, "dev": DEV}, "gsi-peaks": {"dev": GSI_DEV}}, tags)

    def test_rejects_url_outside_manifests_of_the_dataset(self):
        bad = {"manifestUrl": "https://shohei0205.github.io/yamamuki-data/peaks/manifest.json", "manifestSha256": "a" * 64}
        # gsi-peaks の manifest を osm-peaks の置き場所から指すのも、osm-peaks に gsi-peaks を書くのも受け付けない。
        wrong_dir = {"manifestUrl": f"{pd.manifest_url_prefix('osm-peaks')}{GSI_DEV}.json", "manifestSha256": "a" * 64}
        for value in (catalog(osm_peaks={"stable": bad}),
                      catalog(osm_peaks={"stable": entry(STABLE_OLD)}, gsi_peaks={"dev": wrong_dir}),
                      catalog(osm_peaks={"stable": entry(STABLE_OLD), "dev": entry(GSI_DEV)})):
            with self.subTest(catalog=value), self.assertRaises(pd.PeakDataError):
                pd.validate_catalog(value)

    def test_rejects_dev_release_in_stable(self):
        with self.assertRaises(pd.PeakDataError):
            pd.validate_catalog(catalog(osm_peaks={"stable": entry(DEV)}))

    def test_requires_osm_stable_and_known_keys(self):
        for value in (catalog(osm_peaks={"dev": entry(DEV)}),
                      catalog(gsi_peaks={"dev": entry(GSI_DEV)}),
                      catalog(osm_peaks={"stable": entry(STABLE_OLD)}, gsi_peaks={}),
                      catalog(osm_peaks={"stable": entry(STABLE_OLD), "beta": entry(DEV)}),
                      catalog(osm_peaks={"stable": entry(STABLE_OLD)}, other={"dev": entry(DEV)}),
                      {**catalog(osm_peaks={"stable": entry(STABLE_OLD)}), "schemaVersion": 2},
                      {**catalog(osm_peaks={"stable": entry(STABLE_OLD)}), "extra": 1}):
            with self.subTest(catalog=value), self.assertRaises(pd.PeakDataError):
                pd.validate_catalog(value)


class ValidatePointerTest(unittest.TestCase):
    def test_accepts_only_stable(self):
        self.assertEqual(STABLE_OLD, pd.validate_pointer({"schemaVersion": 1, "stable": entry(STABLE_OLD)}))

    def test_rejects_dev_and_other_keys(self):
        for pointer in ({"schemaVersion": 1, "dev": entry(DEV)},
                        {"schemaVersion": 1, "stable": entry(STABLE_OLD), "dev": entry(DEV)},
                        {"schemaVersion": 1, "stable": entry(DEV)},
                        {"schemaVersion": 2, "stable": entry(STABLE_OLD)}):
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

    def test_rejects_file_name_of_other_dataset(self):
        manifest = json.loads(manifest_bytes(DEV, schema=6, data_schema=6))
        manifest["fileName"] = "gsi-peaks.json.gz"
        manifest["downloadUrl"] = manifest["downloadUrl"].replace("osm-peaks.json.gz", "gsi-peaks.json.gz")
        with self.assertRaises(pd.PeakDataError):
            pd.validate_manifest(json.dumps(manifest).encode(), DEV, "dev")

    def test_gsi_stable_needs_v6_but_not_the_app_format(self):
        self.assertEqual(6, pd.validate_manifest(manifest_bytes(GSI_STABLE, schema=6, data_schema=6),
                                                 GSI_STABLE, "stable")["schemaVersion"])
        with self.assertRaises(pd.PeakDataError):
            pd.validate_manifest(manifest_bytes(GSI_DEV), GSI_DEV, "dev")


class SwapTest(SiteTestCase):
    def test_stable_copies_manifest_and_points_to_it(self):
        result = pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)
        self.assertTrue(result["changed"])
        self.assertEqual(f"data/stable/{STABLE_NEW}", result["branch"])
        data = pd.manifest_path(self.root, STABLE_NEW).read_bytes()
        self.assertEqual(self.releases[STABLE_NEW][0], data)
        self.assertEqual(pd.entry_for(STABLE_NEW, data), self.catalog()["datasets"]["osm-peaks"]["stable"])
        # 0.6.0・0.7.0 が読む current.json の stable も同じ manifest にする。
        self.assertEqual(hashlib.sha256(data).hexdigest(), self.pointer()["stable"]["manifestSha256"])
        self.assertIn("+65", "\n".join(result["body"]))
        self.assertIn("current.json", "\n".join(result["body"]))
        # 前の版のコピーは残す。
        self.assertTrue(pd.manifest_path(self.root, STABLE_OLD).exists())

    def test_same_version_does_nothing(self):
        self.releases[STABLE_OLD] = (manifest_bytes(STABLE_OLD), {})
        self.assertFalse(pd.swap(self.root, "osm-peaks", "stable", STABLE_OLD, False)["changed"])

    def test_stable_refuses_dev_release(self):
        with self.assertRaises(pd.PeakDataError):
            pd.swap(self.root, "osm-peaks", "stable", DEV, False)

    def test_dev_writes_only_catalog(self):
        before = pd.pointer_path(self.root).read_bytes()
        pd.swap(self.root, "osm-peaks", "dev", DEV, False)
        self.assertEqual(DEV, self.tags()["osm-peaks"]["dev"])
        self.assertEqual(before, pd.pointer_path(self.root).read_bytes())

    def test_dev_then_stable_with_remove_dev(self):
        pd.swap(self.root, "osm-peaks", "dev", DEV, False)
        result = pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, True)
        self.assertNotIn("dev", self.catalog()["datasets"]["osm-peaks"])
        self.assertIn("dev", "\n".join(result["body"]))

    def test_remove_dev(self):
        self.assertFalse(pd.swap(self.root, "osm-peaks", "remove-dev", "", False)["changed"])
        pd.swap(self.root, "osm-peaks", "dev", DEV, False)
        self.assertTrue(pd.swap(self.root, "osm-peaks", "remove-dev", "", False)["changed"])
        self.assertEqual(["stable"], list(self.catalog()["datasets"]["osm-peaks"]))

    def test_stable_fixes_pointer_even_when_catalog_is_already_there(self):
        pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)
        pd.write_pointer(self.root, {"schemaVersion": 1, "stable": entry(STABLE_OLD, pd.sha256(manifest_bytes(STABLE_OLD)))})
        self.assertTrue(pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)["changed"])
        self.assertEqual(self.catalog()["datasets"]["osm-peaks"]["stable"], self.pointer()["stable"])

    def test_works_without_pointer(self):
        pd.pointer_path(self.root).unlink()
        self.assertTrue(pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)["changed"])
        self.assertIsNone(self.pointer())

    def test_refuses_to_overwrite_existing_copy(self):
        pd.manifest_path(self.root, STABLE_NEW).write_bytes(b"{}\n")
        with self.assertRaises(pd.PeakDataError):
            pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)

    def test_latest_tag_is_used_when_tag_is_empty(self):
        with mock.patch.object(pd, "latest_tag", return_value=STABLE_NEW) as latest:
            pd.swap(self.root, "osm-peaks", "stable", "", False)
        latest.assert_called_once_with("osm-peaks", "stable")


class SwapGsiTest(SiteTestCase):
    def test_dev_adds_gsi_without_touching_osm(self):
        before = pd.pointer_path(self.root).read_bytes()
        result = pd.swap(self.root, "gsi-peaks", "dev", GSI_DEV, False)
        self.assertTrue(result["changed"])
        self.assertEqual({"osm-peaks": {"stable": STABLE_OLD}, "gsi-peaks": {"dev": GSI_DEV}}, self.tags())
        self.assertEqual(self.releases[GSI_DEV][0], (self.root / pd.manifests_dir("gsi-peaks") / f"{GSI_DEV}.json").read_bytes())
        self.assertEqual(before, pd.pointer_path(self.root).read_bytes())
        self.assertIn("アプリはまだ読まない", "\n".join(result["body"]))

    def test_stable_does_not_touch_pointer(self):
        before = pd.pointer_path(self.root).read_bytes()
        pd.swap(self.root, "gsi-peaks", "stable", GSI_STABLE, False)
        self.assertEqual(GSI_STABLE, self.tags()["gsi-peaks"]["stable"])
        self.assertEqual(before, pd.pointer_path(self.root).read_bytes())

    def test_refuses_tag_of_other_dataset(self):
        for dataset, tag in (("gsi-peaks", DEV), ("osm-peaks", GSI_DEV)):
            with self.subTest(dataset=dataset), self.assertRaises(pd.PeakDataError):
                pd.swap(self.root, dataset, "dev", tag, False)

    def test_remove_dev_drops_empty_dataset(self):
        pd.swap(self.root, "gsi-peaks", "dev", GSI_DEV, False)
        result = pd.swap(self.root, "gsi-peaks", "remove-dev", "", False)
        self.assertEqual("data/remove-dev/gsi-peaks", result["branch"])
        self.assertNotIn("gsi-peaks", self.catalog()["datasets"])


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
        result = pd.swap(self.root, "osm-peaks", "dev", DEV, False)
        self.assertTrue(result["changed"])
        body = "\n".join(result["body"])
        self.assertIn("14,000 件", body)
        self.assertIn("schemaVersion 6", body)
        self.assertEqual(DEV, self.tags()["osm-peaks"]["dev"])

    def test_stable_still_refuses_v6(self):
        self.releases[STABLE_NEW] = (manifest_bytes(STABLE_NEW, schema=6, data_schema=6), {})
        with self.assertRaises(pd.PeakDataError):
            pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)


class CheckTest(SiteTestCase):
    """check を、git と通信を差し替えて動かす。base にはカタログも current.json も無いことにする。"""

    def run_check(self):
        def git(*args):
            if args[0] == "show":
                raise subprocess.CalledProcessError(128, "git")
            return ""

        with mock.patch.object(pd, "git", side_effect=git), mock.patch.object(pd, "check_data", return_value=1) as data, \
                mock.patch("builtins.print"):
            pd.check(self.root, "origin/main", False)
        return data

    def test_passes_and_reads_data_only_of_osm(self):
        pd.swap(self.root, "gsi-peaks", "dev", GSI_DEV, False)
        data = self.run_check()
        self.assertEqual([STABLE_OLD], [call.args[1] for call in data.call_args_list])

    def test_fails_when_pointer_differs_from_catalog(self):
        pd.swap(self.root, "osm-peaks", "stable", STABLE_NEW, False)
        pd.write_pointer(self.root, {"schemaVersion": 1, "stable": entry(STABLE_OLD, pd.sha256(manifest_bytes(STABLE_OLD)))})
        with self.assertRaises(pd.PeakDataError):
            self.run_check()

    def test_compares_count_with_pointer_of_base_before_catalog(self):
        old = manifest_bytes(STABLE_OLD, count=20000)
        shown = {f"origin/main:site/data/osm-peaks/current.json":
                 json.dumps({"schemaVersion": 1, "stable": entry(STABLE_OLD), "dev": entry(DEV)}),
                 f"origin/main:site/data/osm-peaks/manifests/{STABLE_OLD}.json": old.decode()}

        def git(*args):
            if args[0] == "show":
                if args[1] not in shown:
                    raise subprocess.CalledProcessError(128, "git")
                return shown[args[1]]
            return ""

        with mock.patch.object(pd, "git", side_effect=git), mock.patch.object(pd, "check_data", return_value=1), \
                mock.patch("builtins.print"), self.assertRaises(pd.PeakDataError) as raised:
            pd.check(self.root, "origin/main", False)
        self.assertIn("1 件", str(raised.exception))

    def test_fails_when_sha256_differs(self):
        value = self.catalog()
        value["datasets"]["osm-peaks"]["stable"]["manifestSha256"] = "b" * 64
        pd.write_catalog(self.root, value)
        with self.assertRaises(pd.PeakDataError):
            self.run_check()


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
            self.assertEqual(STABLE_NEW, pd.latest_tag("osm-peaks", "stable"))
            self.assertEqual(DEV, pd.latest_tag("osm-peaks", "dev"))
            self.assertEqual("gsi-peaks-20261202T000000Z-1-1", pd.latest_tag("gsi-peaks", "stable"))
            self.assertEqual("gsi-peaks-dev-20261202T000000Z-1-1", pd.latest_tag("gsi-peaks", "dev"))


class CheckHelpersTest(unittest.TestCase):
    def test_manifest_changes_separates_additions(self):
        prefix = "site/data/osm-peaks/manifests/"
        gsi = f"site/data/gsi-peaks/manifests/{GSI_DEV}.json"
        added, others = pd.manifest_changes(
            f"A\t{prefix}{STABLE_NEW}.json\nM\t{prefix}{STABLE_OLD}.json\nD\t{prefix}x.json\n"
            f"M\tsite/data/osm-peaks/current.json\nA\t{gsi}\nM\tsite/data/catalog.json\n")
        self.assertEqual([f"{prefix}{STABLE_NEW}.json", gsi], added)
        self.assertEqual(2, len(others))

    def test_count_drop(self):
        self.assertEqual(0.0, pd.count_drop(14000, 14100))
        self.assertAlmostEqual(0.1, pd.count_drop(14000, 12600))
        self.assertEqual(0.0, pd.count_drop(0, 10))


if __name__ == "__main__":
    unittest.main()
